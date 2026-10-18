"""Queue worker lifecycle, attempt supervision, slot monitoring, and crash recovery."""
from __future__ import annotations

import json
import multiprocessing
import os
from pathlib import Path
import select
import socket
import sqlite3
import stat
import time
from typing import Any

from .common import (
    ACTIVE,
    ADMINISTRATIVE,
    MAX_SLOTS,
    QUEUE_PROTOCOL,
    RUNNING,
    FileLock,
    QueueBusy,
    QueueError,
    _entry_constant,
    atomic_json,
    identity_alive,
    process_identity,
)
from .display import terminate_process, terminate_xvfb, wake
from .runner import (
    RunnerBackend,
    durable_outcome,
    supervised_attempt,
)
from .store import Store, retry_transient_lock


class Worker:
    def __init__(self, store: Store, backend: Any | None = None):
        self.store = store
        self.backend = RunnerBackend() if backend is None else backend

    def reconcile(self) -> None:
        # Reattach observation to live acknowledged supervisors, not execution.
        # Keep their admission valid while they finish, without ever rerunning.
        # Attempt rows whose recorded identity is dead are finished/failed and
        # their displays and Xvfb processes are released.
        for job in self.store.jobs():
            xvfb_pid = job["xvfb_pid"]
            if job["state"] not in RUNNING:
                # Per-attempt admission lock files left by a dead worker are
                # stale proofs; remove them so they cannot outlive the attempt.
                (self.store.root / f"admission-{job['job_id']}.lock").unlink(missing_ok=True)
            if xvfb_pid is not None and job["state"] not in RUNNING:
                process = self.store.xvfb_processes.pop(job["job_id"], None)
                if process is not None:
                    terminate_process(process)
                else:
                    terminate_xvfb(xvfb_pid, job["display"])
                retry_transient_lock(lambda: self.store.release_display(job["job_id"]))
            if job["state"] not in RUNNING | {"quarantined"}:
                continue
            if job["state"] in RUNNING and job["identity_json"] \
                    and identity_alive(json.loads(job["identity_json"])):
                continue  # Live attempt in this or another dead worker's slot.
            if job["state"] in RUNNING and xvfb_pid is not None:
                process = self.store.xvfb_processes.pop(job["job_id"], None)
                if process is not None:
                    terminate_process(process)
                else:
                    terminate_xvfb(xvfb_pid, job["display"])
                retry_transient_lock(lambda: self.store.release_display(job["job_id"]))
            directory = self.store.root / "jobs" / job["job_id"]
            if job["state"] != "quarantined" or (directory / "outcome.json").is_file() or (directory / "evidence/lifecycle-result.json").is_file():
                try:
                    self.finish(job)
                except (OSError, ValueError, KeyError, TypeError, QueueError, sqlite3.Error):
                    pass  # finish() already quarantined, or a durable verdict
                    # still awaits a writable database; keep scanning siblings.

    def finish(self, job: dict[str, Any]) -> None:
        directory = self.store.root / "jobs" / job["job_id"]
        try:
            evidence = durable_outcome(self.store, job)
            identity = json.loads(job["identity_json"]) if job["identity_json"] else None
            if identity is not None and identity_alive(identity):
                raise QueueError("supervisor still active")
            runner_identity = directory / "runner-identity.json"
            if runner_identity.exists() and identity_alive(json.loads(runner_identity.read_text())):
                raise QueueError("runner still active")
            # Unknown external sessions are handled by the next idle dispatch
            # check; they are not evidence that this contained attempt leaked.
            self.store.complete_retry(job["job_id"], evidence["terminalState"], evidence)
        except sqlite3.Error:
            # The durable verdict stays in the attempt directory; a transient
            # persistence failure must not quarantine a proven attempt. The
            # next reconcile pass consumes the same durable verdict.
            raise
        except (OSError, ValueError, KeyError, TypeError, QueueError) as failure:
            reason = str(failure)
            retry_transient_lock(lambda: self.store.quarantine(job["job_id"], reason))

    def _worker_status(self, running: dict[int, dict[str, Any]], max_slots: int) -> None:
        atomic_json(self.store.root / "worker.json", {**process_identity(os.getpid()),
            "slots": [{"jobId": attempt["job"]["job_id"],
                       "version": attempt["job"]["version"],
                       "display": attempt["job"]["display"]}
                      for attempt in running.values()]})

    def serve(self, *, stop: Any | None = None, max_jobs: int | None = None) -> None:
        count = 0
        running: dict[int, dict[str, Any]] = {}
        try:
            max_slots = int(os.environ.get("TURBOISM_QUEUE_MAX_SLOTS") or MAX_SLOTS)
        except ValueError:
            max_slots = MAX_SLOTS
        max_slots = min(MAX_SLOTS, max(1, max_slots))
        def stopped() -> bool:
            return stop is not None and stop.is_set()

        with FileLock(self.store.root / "worker.lock") as worker_lock:
            retry_transient_lock(self.reconcile)
            socket_path = self.store.root / "wake.sock"
            if socket_path.exists() or socket_path.is_symlink():
                if not stat.S_ISSOCK(socket_path.lstat().st_mode):
                    raise QueueError("unsafe worker socket")
                socket_path.unlink()
            with socket.socket(socket.AF_UNIX, socket.SOCK_DGRAM) as listener:
                listener.bind(str(socket_path))
                os.chmod(socket_path, 0o600)
                self._worker_status(running, max_slots)
                try:
                    while True:
                        if not running and (stopped() or (max_jobs is not None and count >= max_jobs)):
                            break
                        # Reap exited children before claiming: their slots,
                        # displays and per-job admission fds free immediately.
                        for pid, attempt in list(running.items()):
                            if not attempt["process"].is_alive():
                                self.reap(attempt)
                                del running[pid]
                        retry_transient_lock(self.reconcile)
                        foreign = list(self.backend.busy(
                            retry_transient_lock(self.store.active_run_ids)))
                        host = retry_transient_lock(self.store.host)
                        if host["state"] in {"idle", "external-busy"}:
                            retry_transient_lock(lambda: self.store.external_busy(foreign))
                            host = retry_transient_lock(self.store.host)
                        if (host["state"] in {"idle", "owned"} and not foreign and not stopped()
                                and len(running) < max_slots
                                and (max_jobs is None or count < max_jobs)):
                            # The shared admission lock keeps collector exclusion:
                            # a collector holding it pauses this whole pass.
                            gate = FileLock(self.store.root / "admission.lock")
                            try:
                                gate.acquire()
                            except QueueBusy:
                                # An idle-window collector owns admission. Do not stop the worker.
                                time.sleep(0.2)
                            else:
                                try:
                                    while (len(running) < max_slots and not stopped()
                                           and (max_jobs is None or count < max_jobs)):
                                        if self.store.production:
                                            from host_validation_retention import check_space
                                            try:
                                                check_space(self.store.root)
                                            except QueueError:
                                                break
                                        job = retry_transient_lock(self.store.claim)
                                        if job is None:
                                            break
                                        try:
                                            attempt = self.spawn(job, gate, worker_lock.fd)
                                        except BaseException as failure:
                                            reason = f"attempt interrupted: {failure}"
                                            retry_transient_lock(lambda: self.store.quarantine(
                                                job["job_id"], reason))
                                            break
                                        running[attempt["process"].pid] = attempt
                                        count += 1
                                finally:
                                    gate.close()
                        watch = [listener]
                        sentinels = {}
                        for pid, attempt in running.items():
                            sentinels[attempt["process"].sentinel] = pid
                            watch.append(attempt["process"].sentinel)
                        ready, _, _ = select.select(watch, [], [], 1)
                        for source in ready:
                            if source is listener:
                                listener.recv(4096)
                            else:
                                attempt = running.pop(sentinels[source], None)
                                if attempt is not None:
                                    self.reap(attempt)
                        self._worker_status(running, max_slots)
                finally:
                    socket_path.unlink(missing_ok=True)
                    (self.store.root / "worker.json").unlink(missing_ok=True)

    def spawn(self, job: dict[str, Any], gate: FileLock, worker_fd: int) -> dict[str, Any]:
        """Start the supervised attempt child without blocking on its run."""
        _, _, protocol = self.store._job_profile(job["prepared_id"])
        if protocol >= _entry_constant("QUEUE_PROTOCOL", QUEUE_PROTOCOL):
            # Per-execution admission proof; never share one fd across children.
            admission = FileLock(self.store.root / f"admission-{job['job_id']}.lock")
            admission.acquire()
            admission_path: Path | None = admission.path
        else:
            admission = gate  # Legacy snapshots only prove admission.lock.
            admission_path = None
        parent, child = socket.socketpair()
        parent.settimeout(10)
        process = multiprocessing.get_context("fork").Process(target=supervised_attempt,
            args=(self.store.root, job, self.backend, admission.fd, worker_fd, child, parent))
        try:
            process.start()
            child.close()
            identity = json.loads(parent.recv(4096))
            if identity != process_identity(process.pid):
                raise QueueError("child handshake identity mismatch")
            retry_transient_lock(lambda: self.store.acknowledge(
                job["job_id"], job["attempt_id"], identity))
            parent.sendall(b"A")
        except BaseException:
            parent.close()
            child.close()
            if process.pid is not None:
                if process.is_alive():
                    process.terminate()
                process.join(5)
            if admission_path is not None:
                admission.close()
                admission_path.unlink(missing_ok=True)
            raise
        return {"job": job, "process": process, "socket": parent,
                "admission": admission, "admission_path": admission_path}

    def reap(self, attempt: dict[str, Any]) -> None:
        """Collect a finished child: outcome first, then release its slot."""
        job = attempt["job"]
        process = attempt["process"]
        process.join()
        attempt["socket"].close()
        xvfb = self.store.xvfb_processes.pop(job["job_id"], None)
        if xvfb is not None:
            terminate_process(xvfb)
        try:
            row = retry_transient_lock(lambda: self.store.jobs(job["job_id"])[0])
        except sqlite3.Error:
            row = job  # Claim-time row; finish()/reconcile re-read anyway.
        if xvfb is None and row["xvfb_pid"] is not None:
            terminate_xvfb(row["xvfb_pid"], row["display"])
        try:
            self.finish(row)
        except (OSError, ValueError, KeyError, TypeError, QueueError, sqlite3.Error):
            pass  # finish() quarantines internally, or a durable verdict still
            # awaits a writable database; reaping must continue.
        try:
            retry_transient_lock(lambda: self.store.release_display(job["job_id"]))
        except sqlite3.Error:
            pass  # Stale display fields are retried by the next reconcile pass.
        attempt["admission"].close()
        if attempt["admission_path"] is not None:
            attempt["admission_path"].unlink(missing_ok=True)


def worker_online(store: Store | Path) -> bool:
    root = store if isinstance(store, Path) else store.root
    try:
        record = json.loads((root / "worker.json").read_text())
        if not isinstance(record, dict):
            return False
        identity = {key: value for key, value in record.items() if key != "slots"}
        return identity_alive(identity)
    except (OSError, ValueError, KeyError):
        return False


def recover(store: Store, job_id: str, reason: str | None = None) -> dict[str, Any]:
    job = store.jobs(job_id)[0]
    report: dict[str, Any] = {"safe": False, "jobId": job_id, "state": job["state"]}
    if reason is not None and not reason.strip():
        raise QueueError("recovery confirmation requires an operator reason")
    if job["state"] in ADMINISTRATIVE:
        return {**report, "safe": False, "disposition": "ABANDONED_UNVERIFIED",
                "reason": "administrative disposition is not a verification result"}
    if job["state"] not in ACTIVE:
        return {**report, "safe": True, "reason": "job is already terminal or not started"}
    try:
        if job["identity_json"] and identity_alive(json.loads(job["identity_json"])):
            raise QueueError("recorded supervisor is still active")
        directory = store.root / "jobs" / job_id
        runner_identity_path = directory / "runner-identity.json"
        if runner_identity_path.exists() and identity_alive(json.loads(runner_identity_path.read_text())):
            raise QueueError("recorded runner is still active")
        from .display import external_sessions
        if external_sessions(store.active_run_ids()):
            raise QueueError("host contains active external/unknown Cubism sessions")
        evidence = durable_outcome(store, job, allow_containment_recovery=True)
        # Inspection and confirmation apply exactly the same evidence rules.
        store.validate_completion(job, evidence["terminalState"], evidence)
        report.update(safe=True, evidence=evidence)
        if reason is not None:
            with FileLock(store.root / "worker.lock"), FileLock(store.root / "admission.lock"):
                # Prevent racing a worker; revalidate process state before committing.
                fresh = store.jobs(job_id)[0]
                if fresh["identity_json"] and identity_alive(json.loads(fresh["identity_json"])):
                    raise QueueError("supervisor became active during recovery")
                if fresh["xvfb_pid"] is not None:
                    terminate_xvfb(fresh["xvfb_pid"], fresh["display"])
                store.complete_retry(job_id, evidence["terminalState"], evidence, recovery_reason=reason)
                retry_transient_lock(lambda: store.release_display(job_id))
                wake(store)
    except (OSError, ValueError, KeyError, TypeError, QueueError, sqlite3.Error) as failure:
        report.update(safe=False, reason=str(failure))
    return report
