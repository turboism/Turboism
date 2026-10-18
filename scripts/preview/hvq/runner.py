"""Contained Runner backend execution, supervision, admission and durable outcome checks."""
from __future__ import annotations

import json
import os
from pathlib import Path
import signal
import socket
import time
from typing import TYPE_CHECKING, Any

from .common import (
    SCHEMA,
    FileLock,
    NoHostSideEffects,
    QueueError,
    _delegate,
    account_root,
    atomic_json,
    canonical_json,
    identity_alive,
    private_directory,
    process_identity,
)
from .display import external_sessions, scope_identity, snapshot_module
from .snapshot import PreparedStore
from .store import Store, retry_transient_lock

if TYPE_CHECKING:
    pass


class RunnerBackend:
    """Production backend: only a verified prepared Runner on the fixed host."""
    def busy(self, owned_markers: set[str] | frozenset[str] = frozenset()) -> list[int]:
        return external_sessions(owned_markers)

    def run(self, store: Store, job: dict[str, Any], admission_fd: int) -> dict[str, Any]:
        if store.root != account_root():
            raise QueueError("production backend cannot run in a test queue")
        directory = private_directory(store.root / "jobs" / job["job_id"])
        evidence_dir = directory / "evidence"
        try:
            command = PreparedStore(store).command(job["prepared_id"], evidence_dir)
        except (QueueError, OSError, ValueError, KeyError, TypeError) as failure:
            raise NoHostSideEffects(str(failure)) from failure
        # Slot displays are queue allocation state, not prepared input: the
        # digest still covers the frozen descriptor, only resolved argv grows.
        injected_display = None
        if job.get("display") and job["display"] != ":0" and "--display" not in command:
            command = [*command, "--display", job["display"]]
            injected_display = job["display"]
            def record_display() -> None:
                with store.transaction() as db:
                    Store.event(db, job["job_id"], "display-injected", display=injected_display)
            retry_transient_lock(record_display)
        injected_code = {"BASH_ENV", "ENV", "PYTHONPATH", "PYTHONHOME", "LD_PRELOAD", "LD_LIBRARY_PATH",
                         "JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS", "CLASSPATH", "SHELLOPTS", "BASHOPTS"}
        environment = {key: value for key, value in os.environ.items()
                       if not key.startswith(("TURBOISM_", "BASH_FUNC_")) and key not in injected_code}
        environment.update({"TURBOISM_QUEUE_JOB_ID": job["job_id"],
            "TURBOISM_QUEUE_ATTEMPT_ID": job["attempt_id"], "TURBOISM_QUEUE_RUN_ID": job["run_id"],
            "TURBOISM_QUEUE_ADMISSION_FD": str(admission_fd), "TURBOISM_ENV_FILE": "/dev/null",
            "TURBOISM_QUEUE_SUPERVISOR_PID": str(os.getpid()), "PYTHONDONTWRITEBYTECODE": "1"})
        prepared = PreparedStore(store)
        descriptor = prepared.load(job["prepared_id"])
        from host_validation_retention import check_space
        argv = descriptor["argv"]
        host_root = Path(argv[argv.index("--host-root") + 1])
        try:
            check_space(store.root, (host_root,))
        except QueueError as failure:
            raise NoHostSideEffects(str(failure)) from failure
        if descriptor["digest"] != job["digest"]:
            raise QueueError("job and prepared digest mismatch")
        prepared_root = store.root / "prepared" / job["prepared_id"]
        containment = snapshot_module(prepared_root, "host_validation_containment")
        finalizer = snapshot_module(prepared_root, "host_validation_evidence")
        expected = {"jobId": job["job_id"], "attemptId": job["attempt_id"],
                    "runId": job["run_id"], "preparedDigest": job["digest"]}
        deadline = time.monotonic() + job["timeout_seconds"]
        contained = None
        proof = None
        requested = None
        cleanup_started = None
        with (directory / "runner.log").open("ab", buffering=0) as log:
            try:
                contained = containment.start(command, directory=directory, environment=environment,
                    admission_fd=admission_fd, identity=expected, output=log)
                process = contained.process
                identity = scope_identity(contained.entry_identity)
                atomic_json(directory / "runner-identity.json", identity)
                heartbeat_at = 0.0
                while process.poll() is None:
                    now = time.monotonic()
                    if now >= heartbeat_at:
                        atomic_json(directory / "heartbeat.json", {
                            "schemaVersion": 1, **expected, "observedAt": time.time(),
                            "supervisor": process_identity(os.getpid()), "runner": identity,
                        })
                        heartbeat_at = now + 5.0  # Diagnostics, never an expiring safety lease.
                    cancel = store.jobs_retry(job["job_id"])[0]["cancel_requested"]
                    if requested is None and (cancel or now >= deadline):
                        requested = "cancelled" if cancel else "timed_out"
                        cleanup_started = now
                        # Give this exact Runner a short chance to archive its phase
                        # evidence. The pinned cgroup handles, not this PID, own cleanup.
                        if hasattr(os, "pidfd_open") and hasattr(signal, "pidfd_send_signal"):
                            try:
                                pidfd = os.pidfd_open(identity["pid"])
                                try:
                                    if identity_alive(identity):
                                        signal.pidfd_send_signal(pidfd, signal.SIGTERM)
                                finally:
                                    os.close(pidfd)
                            except ProcessLookupError:
                                pass
                    if not identity_alive(identity) or (cleanup_started is not None and now >= cleanup_started + 5):
                        break
                    time.sleep(0.1)
                remaining = 30.0 if cleanup_started is None else max(0.1, 30.0 - (time.monotonic() - cleanup_started))
                proof = contained.finish(timeout_seconds=remaining)
                if proof.get("cleanup") != "safe":
                    raise QueueError("kernel containment cleanup is unknown; host quarantined")
                # Revalidate fixed snapshots after the contained processes are gone.
                descriptor = prepared.load(job["prepared_id"])
                path = evidence_dir / "lifecycle-result.json"
                preliminary = json.loads(path.read_text()) if path.is_file() else None
                if preliminary is not None and not isinstance(preliminary, dict):
                    raise QueueError("lifecycle evidence must be an object")
                if requested is None and store.jobs_retry(job["job_id"])[0]["cancel_requested"]:
                    requested = "cancelled"
                evidence = finalizer.finalize(job, descriptor, prepared_root, directory, preliminary,
                                              proof, process.returncode, requested)
                if injected_display is not None and isinstance(evidence, dict):
                    evidence["queueDisplay"] = injected_display
                return evidence
            finally:
                if contained is not None:
                    try:
                        if proof is None:
                            contained.finish(timeout_seconds=30)
                    finally:
                        contained.close()


def supervised_attempt(root: Path, job: dict[str, Any], backend: Any, admission_fd: int,
                       worker_fd: int, child_socket: socket.socket, parent_socket: socket.socket) -> None:
    """No backend side effect until the parent persists identity and sends ACK."""
    parent_socket.close()
    os.close(worker_fd)
    child_socket.settimeout(10)
    directory = private_directory(root / "jobs" / job["job_id"])
    try:
        store = Store(root)
        child_socket.sendall(canonical_json(process_identity(os.getpid())).encode())
        if child_socket.recv(1) != b"A":
            return
        if store.jobs_retry(job["job_id"])[0]["cancel_requested"]:
            evidence = {"schemaVersion": 1, "jobId": job["job_id"], "attemptId": job["attempt_id"],
                "runId": job["run_id"], "preparedDigest": job["digest"],
                "cleanup": "safe", "validationStatus": "UNKNOWN", "terminalState": "cancelled",
                "noHostSideEffects": True}
        else:
            evidence = backend.run(store, job, admission_fd)
        atomic_json(directory / "outcome.json", evidence)
    except NoHostSideEffects as failure:
        atomic_json(directory / "outcome.json", {"schemaVersion": 1,
            "jobId": job["job_id"], "attemptId": job["attempt_id"], "runId": job["run_id"],
            "preparedDigest": job["digest"], "cleanup": "safe", "validationStatus": "UNKNOWN",
            "terminalState": "failed", "noHostSideEffects": True, "reason": str(failure)})
    except BaseException as failure:
        atomic_json(directory / "supervisor-error.json", {"error": str(failure), "at": time.time()})
    finally:
        child_socket.close()
        os.close(admission_fd)


def _default_verify_containment_membership(store: Store, job: dict[str, Any]) -> dict[str, Any]:
    metadata = json.loads((store.root / "jobs" / job["job_id"] / "containment.json").read_text())
    expected = {"jobId": job["job_id"], "attemptId": job["attempt_id"],
                "runId": job["run_id"], "preparedDigest": job["digest"]}
    if metadata.get("schemaVersion") != 1 or any(metadata.get(k) != v for k, v in expected.items()):
        raise QueueError("containment metadata identity mismatch")
    cgroup = Path(metadata["cgroupPath"])
    if not cgroup.is_absolute() or ".." in cgroup.parts or cgroup == Path("/"):
        raise QueueError("invalid containment cgroup path")
    current_groups = Path("/proc/self/cgroup").read_text().splitlines()
    if current_groups != ["0::" + str(cgroup)]:
        raise QueueError("Runner is not in its admitted cgroup")
    kernel_path = Path("/sys/fs/cgroup") / cgroup.relative_to("/")
    current = kernel_path.stat()
    if (current.st_dev, current.st_ino) != (metadata["cgroupDevice"], metadata["cgroupInode"]):
        raise QueueError("containment cgroup identity changed")
    if not identity_alive(scope_identity(metadata["entryIdentity"])):
        raise QueueError("containment entry identity is no longer live")
    return metadata


def verify_containment_membership(store: Store, job: dict[str, Any]) -> dict[str, Any]:
    target = _delegate("verify_containment_membership", verify_containment_membership, _default_verify_containment_membership)
    return target(store, job)


def validate_admission() -> dict[str, Any]:
    """Runner's mandatory gate. Environment labels alone never authorize launch."""
    required = ("TURBOISM_QUEUE_JOB_ID", "TURBOISM_QUEUE_ATTEMPT_ID", "TURBOISM_QUEUE_RUN_ID",
                "TURBOISM_QUEUE_ADMISSION_FD", "TURBOISM_QUEUE_SUPERVISOR_PID")
    if any(not os.environ.get(key) for key in required):
        raise QueueError("missing worker admission labels")
    root = account_root()
    if not (root / "queue.sqlite3").is_file():
        raise QueueError("no local queue admission exists")
    store = Store()
    try:
        job = store.jobs(os.environ["TURBOISM_QUEUE_JOB_ID"])[0]
        fd = int(os.environ["TURBOISM_QUEUE_ADMISSION_FD"])
        identity = json.loads(job["identity_json"] or "null")
        if (job["state"] != "running" or job["cancel_requested"] or job["attempt_id"] != os.environ["TURBOISM_QUEUE_ATTEMPT_ID"]
                or job["run_id"] != os.environ["TURBOISM_QUEUE_RUN_ID"]
                or identity is None or not identity_alive(identity)
                or identity["pid"] != int(os.environ["TURBOISM_QUEUE_SUPERVISOR_PID"])):
            raise QueueError("attempt identity is not authorized")
        # Per-attempt lock for queueProtocol>=2 workers; the shared lock remains
        # the proof for jobs dispatched by an older worker or legacy slot.
        actual = os.fstat(fd)
        matched = None
        for candidate in (root / f"admission-{job['job_id']}.lock", root / "admission.lock"):
            try:
                expected = candidate.stat()
            except FileNotFoundError:
                continue
            if (expected.st_dev, expected.st_ino) == (actual.st_dev, actual.st_ino):
                matched = candidate
                break
        if matched is None:
            raise QueueError("inherited admission descriptor mismatch")
        # A separate open description must be busy; an unlocked matching file is insufficient.
        probe = FileLock(matched)
        try:
            probe.acquire()
        except QueueError as failure:
            if "already owned" not in str(failure):
                raise
        else:
            probe.close()
            raise QueueError("admission descriptor is not locked")
        current = os.getppid()
        ancestor_found = False
        for _ in range(16):
            if current == identity["pid"]:
                ancestor_found = True
                break
            if current <= 1:
                break
            fields = (Path("/proc") / str(current) / "stat").read_text().rsplit(")", 1)[1].split()
            current = int(fields[1])
        if not ancestor_found:
            raise QueueError("runner is not a descendant of its recorded supervisor")
        host = store.host()
        if host["state"] != "owned":
            raise QueueError("host is quarantined or owned by another attempt")
        containment = verify_containment_membership(store, job)
        return {"schemaVersion": 1, "jobId": job["job_id"], "attemptId": job["attempt_id"],
                "runId": job["run_id"], "preparedDigest": job["digest"],
                "cleanupOwner": "supervisor", "containment": containment}
    except (KeyError, ValueError, OSError) as failure:
        raise QueueError("missing or invalid worker admission") from failure


def durable_outcome(store: Store, job: dict[str, Any],
                    allow_containment_recovery: bool = False) -> dict[str, Any]:
    """Consume a final durable verdict; never synthesize one from preliminary flags."""
    directory = store.root / "jobs" / job["job_id"]
    outcome_path = directory / "outcome.json"
    final_path = directory / "evidence/lifecycle-result.json"
    outcome_exists = outcome_path.exists() or outcome_path.is_symlink()
    outcome = json.loads(outcome_path.read_text()) if outcome_exists else None
    final = json.loads(final_path.read_text()) if final_path.exists() else None
    if outcome_exists:
        if not isinstance(outcome, dict):
            raise QueueError("outcome must be an object")
        if outcome.get("finalizedBy") != "contained-supervisor":
            if isinstance(final, dict) and final.get("finalizedBy") == "contained-supervisor" and final != outcome:
                raise QueueError("outcome conflicts with final lifecycle")
            store.validate_completion(job, outcome["terminalState"], outcome)
            return outcome
        if outcome != final:
            raise QueueError("outcome conflicts with or lacks final lifecycle")
    if not isinstance(final, dict) or final.get("finalizedBy") != "contained-supervisor":
        if allow_containment_recovery:
            recovered = containment_failure_outcome(store, job, directory)
            if recovered is not None:
                return recovered
        raise QueueError("no durable final supervisor verdict")
    store.validate_completion(job, final["terminalState"], final)
    proof = final.get("containment")
    metadata = json.loads((directory / "containment.json").read_text())
    if not isinstance(proof, dict) or not isinstance(metadata, dict):
        raise QueueError("missing final containment proof")
    expected = {"jobId": job["job_id"], "attemptId": job["attempt_id"], "runId": job["run_id"], "preparedDigest": job["digest"]}
    for record in (metadata, proof):
        if type(record.get("schemaVersion")) is not int or record["schemaVersion"] != SCHEMA or record.get("cleanup") != "safe":
            raise QueueError("invalid final containment proof schema/cleanup")
        if any(record.get(key) != value for key, value in expected.items()):
            raise QueueError("final containment proof identity mismatch")
    fields = ("bootId", "scopeUnit", "cgroupPath", "cgroupDevice", "cgroupInode", "entryIdentity", "kernelProof")
    if metadata.get("state") != "FINISHED" or any(key not in proof or proof[key] != metadata.get(key) for key in fields):
        raise QueueError("final verdict disagrees with durable bound containment evidence")
    runner_identity = json.loads((directory / "runner-identity.json").read_text())
    if runner_identity != scope_identity(proof["entryIdentity"]) or identity_alive(runner_identity):
        raise QueueError("final verdict runner identity is missing, changed, or still active")
    kernel = proof["kernelProof"]
    if not isinstance(kernel, dict) or kernel.get("originalCgroupBound") is not True or kernel.get("errors") != []:
        raise QueueError("kernel cleanup proof is incomplete")
    for key in ("bootId", "cgroupPath", "cgroupDevice", "cgroupInode"):
        if kernel.get(key) != proof[key]:
            raise QueueError("kernel cleanup proof does not identify the bound scope")
    reading = kernel.get("finalReading", {})
    if not isinstance(reading, dict) or not (reading.get("kind") == "destroyed" or
            (reading.get("kind") == "same" and type(reading.get("populated")) is int and reading["populated"] == 0)):
        raise QueueError("kernel cleanup has no final empty/destroyed reading")
    details = final.get("details", {})
    if not isinstance(details, dict) or details.get("taskOwnedCleanup") is not True:
        raise QueueError("final task cleanup is incomplete")
    if final["terminalState"] == "succeeded":
        checks = details.get("postContainmentChecks")
        terminal = checks.get("terminalResult") if isinstance(checks, dict) else None
        if not isinstance(terminal, dict) or terminal.get("passed") is not True:
            raise QueueError("final terminal result was not verified after containment")
    return final


def containment_failure_outcome(store: Store, job: dict[str, Any],
                                directory: Path) -> dict[str, Any] | None:
    """Recover a `failed` outcome when the supervisor died after proving kernel
    cleanup but before writing its verdict (outcome.json / lifecycle-result.json).

    containment.json is itself durable supervisor evidence, written by
    ContainedProcess.finish only after the bound scope is confirmed empty. It is
    consumed here only when it is complete, identity-matched to this exact
    attempt, kernel-proven, and the recorded runner identity is dead. The result
    can never claim success: it always carries terminalState "failed".
    """
    path = directory / "containment.json"
    if not path.is_file():
        return None
    record = json.loads(path.read_text())
    if not isinstance(record, dict):
        return None
    expected = {"jobId": job["job_id"], "attemptId": job["attempt_id"],
                "runId": job["run_id"], "preparedDigest": job["digest"]}
    if (type(record.get("schemaVersion")) is not int or record["schemaVersion"] != SCHEMA
            or any(record.get(key) != value for key, value in expected.items())
            or record.get("state") != "FINISHED" or record.get("cleanup") != "safe"):
        return None
    kernel = record.get("kernelProof")
    if (not isinstance(kernel, dict)
            or kernel.get("originalCgroupBound") is not True
            or kernel.get("errors") != []):
        return None
    for key in ("bootId", "cgroupPath", "cgroupDevice", "cgroupInode"):
        if kernel.get(key) != record.get(key):
            return None
    reading = kernel.get("finalReading", {})
    if not (isinstance(reading, dict)
            and (reading.get("kind") == "destroyed"
                 or (reading.get("kind") == "same"
                     and type(reading.get("populated")) is int and reading["populated"] == 0))):
        return None
    runner_identity_path = directory / "runner-identity.json"
    if not runner_identity_path.is_file():
        return None
    runner_identity = json.loads(runner_identity_path.read_text())
    entry = record.get("entryIdentity")
    if (not isinstance(entry, dict)
            or runner_identity != scope_identity(entry)
            or identity_alive(runner_identity)):
        return None
    outcome = {"schemaVersion": 1, **expected, "cleanup": "safe",
               "terminalState": "failed", "validationStatus": "UNKNOWN",
               "normalExit": False,
               "reason": "supervisor verdict absent; bound-scope containment proven safe",
               "containment": record}
    store.validate_completion(job, outcome["terminalState"], outcome)
    return outcome
