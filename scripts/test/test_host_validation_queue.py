#!/usr/bin/env python3
"""Queue contracts use private temporary roots and never launch Cubism."""
from __future__ import annotations

import concurrent.futures
import multiprocessing
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
import json
import signal
import time
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "preview"))
import host_validation_queue as queue


class IsolatedBackend:
    """Only runs in test temporary directories; never invokes a host executable."""
    def busy(self):
        return []

    def run(self, store, job, admission_fd):
        if store.root == queue.account_root():
            raise AssertionError("test backend reached production state")
        if job["request_key"] == "preflight-failure":
            raise queue.NoHostSideEffects("synthetic read-only input verification failure")
        sentinel = store.root / "test-host-active"
        fd = os.open(sentinel, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
        os.close(fd)
        if job["request_key"] == "crash-after-launch":
            os._exit(91)  # Only this isolated test supervisor; no host executable exists.
        started = time.monotonic()
        duration = 0.02 if job["request_key"] != "slow" else 0.4
        time.sleep(duration)
        if job["request_key"] == "cleanup-failure":
            return {"cleanup": "unknown", "terminalState": "failed"}
        sentinel.unlink()
        if job["request_key"] == "crash-after-cleanup":
            os._exit(92)  # No durable proof: even an actually empty fake host must quarantine.
        finished = time.monotonic()
        with (store.root / "intervals.jsonl").open("a") as stream:
            stream.write(json.dumps({"jobId": job["job_id"], "start": started, "end": finished}) + "\n")
        cancelled = store.jobs(job["job_id"])[0]["cancel_requested"]
        return {"schemaVersion": 1, "jobId": job["job_id"], "attemptId": job["attempt_id"], "runId": job["run_id"],
                "preparedDigest": job["digest"], "cleanup": "safe", "validationStatus": "FAIL",
                "terminalState": "cancelled" if cancelled else "failed"}


def worker_process(root, stop, maximum):
    queue.Worker(queue.Store(Path(root)), IsolatedBackend()).serve(stop=stop, max_jobs=maximum)


def submit_process(root: str, number: int) -> str:
    return queue.Store(Path(root)).submit("prepared", "digest", f"request-{number}")["job_id"]


class StoreFixture:
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name) / "queue"
        self.store = queue.Store(self.root)

    def job(self, name: str = "request") -> dict:
        return self.store.submit("prepared", "digest", name)

    @staticmethod
    def evidence(job: dict) -> dict:
        return {"schemaVersion": 1, "jobId": job["job_id"], "attemptId": job["attempt_id"],
                "runId": job["run_id"], "preparedDigest": job["digest"],
                "cleanup": "safe", "validationStatus": "PASS",
                "identityVerified": True, "fixtureUnchanged": True, "normalExit": True}


class FinalVerdictRecoveryTest(StoreFixture, unittest.TestCase):
    def final_fixture(self):
        self.job()
        job = self.store.claim()
        identity = {"jobId": job["job_id"], "attemptId": job["attempt_id"], "runId": job["run_id"], "preparedDigest": job["digest"]}
        entry = {"pid": 999999999, "startTicks": "1", "bootId": "synthetic-boot", "uid": os.getuid(), "gid": os.getgid()}
        kernel = {"originalCgroupBound": True, "errors": [], "bootId": "synthetic-boot",
            "cgroupPath": "/test-only/scope", "cgroupDevice": 1, "cgroupInode": 2,
            "finalReading": {"kind": "destroyed"}}
        proof = {"schemaVersion": 1, **identity, "cleanup": "safe", "bootId": "synthetic-boot",
            "scopeUnit": "test-only.scope", "cgroupPath": "/test-only/scope", "cgroupDevice": 1,
            "cgroupInode": 2, "entryIdentity": entry, "kernelProof": kernel}
        final = {**self.evidence(job), "finalizedBy": "contained-supervisor", "terminalState": "succeeded",
            "containment": proof, "details": {"taskOwnedCleanup": True,
            "postContainmentChecks": {"terminalResult": {"passed": True}}}}
        directory = self.root / "jobs" / job["job_id"]
        (directory / "evidence").mkdir(parents=True)
        queue.atomic_json(directory / "evidence/lifecycle-result.json", final)
        queue.atomic_json(directory / "containment.json", {**proof, "state": "FINISHED"})
        queue.atomic_json(directory / "runner-identity.json", queue.scope_identity(entry))
        return job, directory, final

    def test_reconcile_consumes_final_verdict_after_outcome_write_window_crash(self):
        job, directory, final = self.final_fixture()
        queue.Worker(self.store, IsolatedBackend()).reconcile()
        self.assertEqual("succeeded", self.store.jobs(job["job_id"])[0]["state"])
        self.assertEqual("idle", self.store.host()["state"])
        self.assertFalse((directory / "outcome.json").exists())  # Consumed, not fabricated.
        self.assertEqual(final, json.loads(self.store.jobs(job["job_id"])[0]["evidence_json"]))

    def test_operator_recovery_uses_same_complete_final_verdict(self):
        job, directory, _ = self.final_fixture()
        self.store.quarantine(job["job_id"], "simulated outcome write failure")
        with mock.patch.object(queue, "external_sessions", return_value=[]):
            self.assertTrue(queue.recover(self.store, job["job_id"])["safe"])
            self.assertTrue(queue.recover(self.store, job["job_id"], "Reviewed durable final proof")["safe"])
        self.assertEqual("idle", self.store.host()["state"])
        self.assertFalse((directory / "outcome.json").exists())

    def test_preliminary_or_incomplete_kernel_proof_cannot_recover(self):
        job, directory, final = self.final_fixture()
        original = json.loads((directory / "containment.json").read_text())
        for replacement in ({**final, "finalizedBy": "runner"},
                {**final, "details": {"taskOwnedCleanup": True}},
                {**final, "attemptId": "other"}):
            queue.atomic_json(directory / "evidence/lifecycle-result.json", replacement)
            with self.assertRaises(queue.QueueError):
                queue.durable_outcome(self.store, job)
        queue.atomic_json(directory / "evidence/lifecycle-result.json", final)
        for change in ({"state": "BOUND"}, {"cleanup": "unknown"}, {"kernelProof": {}}):
            queue.atomic_json(directory / "containment.json", {**original, **change})
            with self.assertRaises(queue.QueueError):
                queue.durable_outcome(self.store, job)
        self.assertEqual("owned", self.store.host()["state"])

    def test_existing_null_or_non_object_outcome_never_falls_back(self):
        job, directory, _ = self.final_fixture()
        for raw in ("null", "[]", "false", "42", '"text"'):
            (directory / "outcome.json").write_text(raw)
            with self.assertRaises(queue.QueueError):
                queue.durable_outcome(self.store, job)
        self.assertEqual("owned", self.store.host()["state"])

    def test_conflicting_outcome_and_live_runner_do_not_release(self):
        job, directory, final = self.final_fixture()
        queue.atomic_json(directory / "outcome.json", {**final, "validationStatus": "FAIL"})
        with self.assertRaises(queue.QueueError):
            queue.durable_outcome(self.store, job)
        (directory / "outcome.json").unlink()
        queue.atomic_json(directory / "runner-identity.json", queue.process_identity(os.getpid()))
        with self.assertRaises(queue.QueueError):
            queue.durable_outcome(self.store, job)
        self.assertEqual("owned", self.store.host()["state"])


class QueueStoreTest(StoreFixture, unittest.TestCase):
    def test_20_independent_submitters_persist_without_worker(self) -> None:
        with concurrent.futures.ProcessPoolExecutor(max_workers=20,
                mp_context=multiprocessing.get_context("spawn")) as pool:
            ids = list(pool.map(submit_process, [str(self.root)] * 20, range(20)))
        self.assertEqual(20, len(set(ids)))
        reopened = queue.Store(self.root)
        self.assertEqual(20, len(reopened.jobs()))
        self.assertEqual(list(range(1, 21)), [j["sequence"] for j in reopened.jobs()])
        self.assertTrue(all(j["state"] == "queued" for j in reopened.jobs()))

    def test_idempotency_conflict_and_client_exit(self) -> None:
        one = self.job()
        self.assertEqual(one, queue.Store(self.root).submit("prepared", "digest", "request"))
        for arguments in (("other", "digest", "request"), ("prepared", "changed", "request")):
            with self.assertRaisesRegex(queue.QueueError, "conflicts"):
                self.store.submit(*arguments)
        with self.assertRaisesRegex(queue.QueueError, "conflicts"):
            self.store.submit("prepared", "digest", "request", 20)
        self.assertEqual(1, len(self.store.events()))

    def test_fifo_exclusive_claim_and_cleanup_evidence(self) -> None:
        first = self.job("first")
        self.job("second")
        owned = self.store.claim()
        self.assertEqual(first["job_id"], owned["job_id"])
        self.assertIsNone(queue.Store(self.root).claim())
        evidence = self.evidence(owned)
        for key, bad in (("cleanup", "unknown"), ("attemptId", "other"),
                         ("normalExit", False), ("fixtureUnchanged", False)):
            with self.subTest(key=key), self.assertRaises(queue.QueueError):
                self.store.complete(owned["job_id"], "succeeded", {**evidence, key: bad})
            self.assertIsNone(self.store.claim())
        self.store.complete(owned["job_id"], "succeeded", evidence)
        self.assertEqual("second", self.store.claim()["request_key"])
        with self.assertRaises(queue.QueueError):
            self.store.complete(owned["job_id"], "succeeded", evidence)

    def test_unknown_cleanup_quarantines_even_after_restart(self) -> None:
        self.job("first"); self.job("second")
        owned = self.store.claim()
        self.store.quarantine(owned["job_id"], "unconfirmed cleanup")
        self.assertIsNone(queue.Store(self.root).claim())
        self.assertEqual("quarantined", self.store.host()["state"])
        self.assertEqual("unconfirmed cleanup", self.store.jobs(owned["job_id"])[0]["reason"])

    def test_cancel_queued_is_terminal_running_requires_cleanup(self) -> None:
        cancelled = self.job("cancel")
        self.store.cancel(cancelled["job_id"])
        self.store.cancel(cancelled["job_id"])
        owned = self.job("running")
        owned = self.store.claim()
        self.store.cancel(owned["job_id"])
        self.assertEqual("starting", self.store.jobs(owned["job_id"])[0]["state"])
        self.assertIsNone(self.store.claim())
        self.store.complete(owned["job_id"], "cancelled", self.evidence(owned))
        self.assertEqual("idle", self.store.host()["state"])

    def test_transaction_failure_rolls_back_job_and_event(self) -> None:
        with mock.patch.object(self.store, "event", side_effect=OSError("disk full")):
            with self.assertRaises(OSError):
                self.job()
        self.assertEqual([], self.store.jobs())
        self.assertEqual([], self.store.events())

    def test_events_replay_and_filter(self) -> None:
        first, second = self.job("one"), self.job("two")
        self.store.cancel(first["job_id"])
        all_events = self.store.events()
        self.assertEqual(3, len(all_events))
        self.assertEqual(all_events[1:], self.store.events(after=all_events[0]["event_id"]))
        self.assertEqual(1, len(self.store.events(job_id=second["job_id"])))

    def test_process_identity_fences_pid_reuse_and_boot_changes(self) -> None:
        current = queue.process_identity(os.getpid())
        self.assertTrue(queue.identity_alive(current))
        self.assertFalse(queue.identity_alive({**current, "startTicks": current["startTicks"] + 1}))
        self.assertFalse(queue.identity_alive({**current, "bootId": "other-boot"}))
        self.job()
        job = self.store.claim()
        with self.assertRaises(queue.QueueError):
            self.store.acknowledge(job["job_id"], job["attempt_id"], {**current, "bootId": "wrong"})
        self.store.acknowledge(job["job_id"], job["attempt_id"], current)
        self.assertEqual("running", self.store.jobs(job["job_id"])[0]["state"])

    def test_lock_owner_scope_and_no_symlink_directory(self) -> None:
        path = self.root / "admission.lock"
        with queue.FileLock(path):
            with self.assertRaises(queue.QueueError):
                queue.FileLock(path).acquire()
        with queue.FileLock(path):
            pass
        link = Path(self.temp.name) / "link"
        link.symlink_to(self.root, target_is_directory=True)
        with self.assertRaises(queue.QueueError):
            queue.Store(link)

    def test_home_override_cannot_split_production_admission(self) -> None:
        original = queue.account_root()
        with mock.patch.dict(os.environ, {"HOME": self.temp.name, "XDG_STATE_HOME": self.temp.name}):
            self.assertEqual(original, queue.account_root())

    def test_malformed_or_unknown_evidence_schema_never_releases_host(self) -> None:
        self.job()
        job = self.store.claim()
        for evidence in (None, [], {}, {**self.evidence(job), "schemaVersion": 2},
                         {**self.evidence(job), "schemaVersion": True}):
            with self.subTest(evidence=evidence), self.assertRaises(queue.QueueError):
                self.store.complete(job["job_id"], "failed", evidence)
        self.assertEqual("owned", self.store.host()["state"])

    def test_recovery_inspection_and_confirmation_share_pass_rules(self) -> None:
        self.job()
        job = self.store.claim()
        self.store.quarantine(job["job_id"], "worker interrupted")
        directory = self.root / "jobs" / job["job_id"]
        directory.mkdir()
        evidence = {**self.evidence(job), "terminalState": "succeeded", "normalExit": False}
        queue.atomic_json(directory / "outcome.json", evidence)
        with mock.patch.object(queue, "external_sessions", return_value=[]):
            self.assertFalse(queue.recover(self.store, job["job_id"])["safe"])
            self.assertFalse(queue.recover(self.store, job["job_id"], "reviewed")["safe"])
            self.assertEqual("quarantined", self.store.host()["state"])
            evidence["normalExit"] = True
            queue.atomic_json(directory / "outcome.json", evidence)
            self.assertTrue(queue.recover(self.store, job["job_id"])["safe"])
            self.assertEqual("quarantined", self.store.host()["state"])
            self.assertTrue(queue.recover(self.store, job["job_id"], "reviewed")["safe"])
        self.assertEqual("idle", self.store.host()["state"])
        self.assertEqual(1, len([e for e in self.store.events() if e["kind"] == "operator-recovery"]))

    def test_recovery_audit_failure_does_not_release_host(self) -> None:
        self.job()
        job = self.store.claim()
        with mock.patch.object(self.store, "event", side_effect=OSError("disk full")):
            with self.assertRaises(OSError):
                self.store.complete(job["job_id"], "succeeded", self.evidence(job), recovery_reason="reviewed")
        self.assertEqual("owned", self.store.host()["state"])
        self.assertEqual("starting", self.store.jobs(job["job_id"])[0]["state"])

    def test_external_busy_blocks_claim_and_is_visible_until_gone(self) -> None:
        self.job()
        self.store.external_busy([123])
        self.assertIsNone(self.store.claim())
        self.assertEqual("external-busy", self.store.host()["state"])
        self.assertIn("123", self.store.host()["reason"])
        self.store.external_busy([])
        self.assertIsNotNone(self.store.claim())


class PreparedStoreTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.base = Path(self.temp.name)
        self.store = queue.Store(self.base / "queue")
        self.prepared = queue.PreparedStore(self.store)
        self.source = self.base / "source"
        self.preview = self.source / "scripts/preview"
        self.preview.mkdir(parents=True)
        for name in ("run-cubism-host-validation.sh", "host-validation-env.sh", "host-validation-transport.sh",
                     "archive-cubism-host-evidence.sh", "host_validation.py", "host_validation_queue.py",
                     "host_validation_containment.py", "host_validation_evidence.py"):
            (self.preview / name).write_text("# test fixture, never executed\n")
        subprocess.run(["git", "init", "-q", str(self.source)], check=True)
        subprocess.run(["git", "add", "."], cwd=self.source, check=True)
        subprocess.run(["git", "-c", "user.name=Queue Test", "-c", "user.email=test@example.invalid",
                        "-c", "core.hooksPath=/dev/null", "-c", "commit.gpgsign=false", "commit", "-qm", "fixture"],
                       cwd=self.source, check=True)
        self.input = self.base / "input with spaces.jar"
        self.input.write_bytes(b"original artifact")
        self.request = {"schemaVersion": 1, "environment": {},
                        "argv": ["--name", "test", "--agent", str(self.input), "--version", "5302"]}

    def test_snapshot_survives_source_changes_and_removal(self) -> None:
        prepared = self.prepared.capture(self.request, self.source, "test:5302")
        command = self.prepared.command(prepared["digest"], self.base / "evidence")
        copied = Path(command[command.index("--agent") + 1])
        self.input.write_bytes(b"changed artifact")
        self.input.unlink()
        import shutil
        shutil.rmtree(self.source)
        self.assertEqual(b"original artifact", copied.read_bytes())
        self.assertEqual(prepared, self.prepared.load(prepared["digest"]))
        self.assertNotIn(str(self.input), command)
        self.assertIn("input with spaces.jar", str(copied))

    def test_missing_fixed_helper_rejected_before_submission(self) -> None:
        (self.preview / "archive-cubism-host-evidence.sh").unlink()
        with self.assertRaisesRegex(queue.QueueError, "missing canonical runner/helper"):
            self.prepared.capture(self.request, self.source, "test:5302")
        self.assertEqual([], self.store.jobs())

    def test_repeated_capture_is_idempotent(self) -> None:
        first = self.prepared.capture(self.request, self.source, "test:5302")
        second = self.prepared.capture(self.request, self.source, "test:5302")
        self.assertEqual(first["digest"], second["digest"])
        self.assertEqual(1, len(list((self.store.root / "prepared").iterdir())))

    def test_prepared_tampering_rejected(self) -> None:
        prepared = self.prepared.capture(self.request, self.source, "test:5302")
        command = self.prepared.command(prepared["digest"], self.base / "evidence")
        captured = Path(command[command.index("--agent") + 1])
        self.assertEqual(0, captured.stat().st_mode & 0o222)
        captured.chmod(0o600)  # Simulate explicit same-UID tampering, not ordinary writes.
        captured.write_bytes(b"tampered")
        with self.assertRaisesRegex(queue.QueueError, "digest mismatch"):
            self.prepared.load(prepared["digest"])

    def test_symlink_and_custom_hook_rejected(self) -> None:
        self.input.unlink()
        self.input.symlink_to(self.preview / "run-cubism-host-validation.sh")
        with self.assertRaisesRegex(queue.QueueError, "symlink"):
            self.prepared.capture(self.request, self.source, "test:5302")
        self.input.unlink(); self.input.write_text("# custom")
        self.request["argv"] = ["--remote-post-launch", str(self.input)]
        with self.assertRaisesRegex(queue.QueueError, "dependency inventory"):
            self.prepared.capture(self.request, self.source, "test:5302")

    def test_copy_race_fails_closed(self) -> None:
        original = queue.shutil.copyfile
        def changing(source, destination):
            result = original(source, destination)
            if source == self.input:
                self.input.write_bytes(b"raced")
            return result
        with mock.patch.object(queue.shutil, "copyfile", side_effect=changing):
            with self.assertRaisesRegex(queue.QueueError, "changed while"):
                self.prepared.capture(self.request, self.source, "test:5302")
        self.assertEqual([], list((self.store.root / "prepared").iterdir()))

    def test_unknown_arguments_and_environment_are_not_executed(self) -> None:
        for request in ({**self.request, "argv": ["--command", "touch /never"]},
                        {**self.request, "environment": {"TURBOISM_SECRET": "secret"}}):
            with self.assertRaises(queue.QueueError):
                self.prepared.capture(request, self.source, "test:5302")
        with self.assertRaises(queue.QueueError):
            self.prepared.load("../../outside")

    def test_proton_directory_inventory_and_internal_symlink_drift(self):
        runtime = Path(self.temp.name) / "proton"
        runtime.mkdir()
        (runtime / "proton").write_bytes(b"entrypoint")
        (runtime / "wine").symlink_to("proton")
        request = {**self.request, "argv": [*self.request["argv"], "--proton-runner", str(runtime)]}
        prepared = self.prepared.capture(request, self.source, "test:5302")
        self.prepared.command(prepared["digest"], self.store.root / "evidence")
        (runtime / "proton").write_bytes(b"changed entrypoint")
        with self.assertRaises(queue.QueueError):
            self.prepared.command(prepared["digest"], self.store.root / "evidence")

    def test_proton_directory_rejects_external_and_dangling_links(self):
        runtime = Path(self.temp.name) / "proton"
        runtime.mkdir()
        link = runtime / "link"
        for target in (self.input, runtime / "missing"):
            link.symlink_to(target)
            with self.assertRaises(queue.QueueError):
                queue.runtime_digest(runtime)
            link.unlink()

    def test_proton_directory_scan_errors_are_not_partial_inventory(self):
        runtime = Path(self.temp.name) / "proton"
        runtime.mkdir()
        def failing_walk(*args, **kwargs):
            kwargs["onerror"](PermissionError("unreadable subtree"))
            return iter(())
        with mock.patch.object(queue.os, "walk", side_effect=failing_walk):
            with self.assertRaises(queue.QueueError):
                queue.runtime_digest(runtime)
    def test_host_runtime_dependency_drift_blocks_execution(self) -> None:
        request = {**self.request, "argv": [*self.request["argv"], "--proton-runner", str(self.input)]}
        prepared = self.prepared.capture(request, self.source, "test:5302")
        self.input.write_bytes(b"different installed runtime")
        with self.assertRaisesRegex(queue.QueueError, "runtime dependency changed"):
            self.prepared.command(prepared["digest"], self.base / "evidence")

    def test_only_enumerated_fps_hook_is_allowed(self) -> None:
        for flag, name in (("--remote-pre-launch", "fx-validation-remote-pre-launch.sh"),
                           ("--remote-post-launch", "fps-resize-driver.sh"),
                           ("--client-script", "mcp-host-validation-client.py")):
            source = self.preview / name
            source.write_text("# unreviewed test fixture\n")
            with self.subTest(flag=flag), self.assertRaisesRegex(queue.QueueError, "dependency inventory"):
                self.prepared.capture({**self.request, "argv": [flag, str(source)]}, self.source, "test:5302")
        fps = self.preview / "fps-resize-driver.sh"
        with self.assertRaisesRegex(queue.QueueError, "background/args-only"):
            self.prepared.capture({**self.request, "argv": ["--remote-pre-launch", str(fps)]}, self.source, "fps:5302")

    def test_real_runner_prepare_snapshot_and_replay_are_host_side_effect_free(self) -> None:
        tools = Path(__file__).resolve().parents[1] / "preview"
        for name in ("run-cubism-host-validation.sh", "host-validation-env.sh", "host-validation-transport.sh",
                     "archive-cubism-host-evidence.sh", "fps-resize-driver.sh",
                     "host_validation.py", "host_validation_queue.py",
                     "host_validation_containment.py", "host_validation_evidence.py"):
            queue.copy_verified(tools / name, self.preview / name)
        fixture = self.base / "fixture.cmo3"
        fixture.write_bytes(b"test fixture, never opened by a host")
        prepared_dir = self.base / "request"
        host = self.base / "must-not-exist-host"
        env = {key: value for key, value in os.environ.items() if not key.startswith("TURBOISM_")}
        env.update(TURBOISM_ENV_FILE="/dev/null", TURBOISM_QUEUE_RUN_ID="queue-test-replay")
        (self.base / "proton").write_text("# test runtime, never executed\n")
        (self.base / "proton-wrapper").write_text("# test wrapper, never executed\n")
        command = ["bash", str(self.preview / "run-cubism-host-validation.sh"),
                   "--name", "test", "--version", "5302", "--bundle-root", str(self.base),
                   "--agent", str(self.input), "--aux-agent", str(self.input) + ":probe.jar",
                   "--fixture-host", str(fixture),
                   "--host-root", str(host), "--golden-prefix", str(self.base / "golden"),
                   "--proton-runner", str(self.base / "proton"),
                   "--proton-wrapper", str(self.base / "proton-wrapper"), "--result-file", "state/result.txt"]
        # The bundle may not contain the queue itself: use a dedicated input directory.
        bundle = self.base / "bundle"
        bundle.mkdir()
        command[command.index("--bundle-root") + 1] = str(bundle)
        preparation = subprocess.run([*command, "--prepare-dir", str(prepared_dir)], env=env, capture_output=True, text=True)
        self.assertEqual(0, preparation.returncode, preparation.stderr)
        request = json.loads((prepared_dir / "runner-request.json").read_text())
        descriptor = self.prepared.capture(request, self.source, "test:5302")
        frozen = self.prepared.command(descriptor["digest"], self.base / "evidence")
        # Exercise the ordinary shell -> actual CLI -> shared Store boundary.
        # Only this temporary interpreter gets a test root; no production flag
        # or real Runner backend is enabled, and the worker is explicitly fake.
        injection = self.base / "test-interpreter"
        injection.mkdir()
        (injection / "sitecustomize.py").write_text(
            "from pathlib import Path\nimport host_validation_queue as q\n"
            f"q.account_root = lambda: Path({str(self.store.root)!r})\n")
        client_env = {key: value for key, value in env.items() if not key.startswith("TURBOISM_QUEUE_")}
        client_env["PYTHONPATH"] = os.pathsep.join((str(injection), str(self.preview)))
        context = multiprocessing.get_context("spawn")
        stop = context.Event()
        worker = context.Process(target=worker_process, args=(str(self.store.root), stop, 1))
        worker.start()
        try:
            direct = subprocess.run(command, env=client_env, capture_output=True, text=True, timeout=15)
            self.assertEqual(1, direct.returncode, direct.stderr + direct.stdout)
            worker.join(5)
            self.assertEqual(0, worker.exitcode)
            jobs = self.store.jobs()
            self.assertEqual(1, len(jobs))
            self.assertEqual("failed", jobs[0]["state"])
            self.assertIn(jobs[0]["job_id"], direct.stdout)
        finally:
            stop.set()
            queue.wake(self.store)
            worker.join(5)
            if worker.is_alive():
                worker.terminate()  # Explicit isolated test process only.
                worker.join(5)
        fixture.unlink()
        self.input.unlink()
        queue.shutil.rmtree(self.source)
        result = subprocess.run([*frozen, "--dry-run"], env=env, capture_output=True, text=True)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("fixtureName=queue-test-replay.cmo3", result.stdout)
        self.assertIn("runId=queue-test-replay", result.stdout)
        self.assertFalse(host.exists())
        self.assertFalse((self.base / "evidence").exists())
        self.assertEqual("idle", self.store.host()["state"])


class WorkerTest(StoreFixture, unittest.TestCase):
    def test_missing_ack_cannot_execute_backend_and_quarantines(self):
        self.job()
        job = self.store.claim()
        parent, child = queue.socket.socketpair()
        with queue.FileLock(self.store.root / "worker.lock") as worker_lock, queue.FileLock(self.store.root / "admission.lock") as admission:
            process = multiprocessing.get_context("fork").Process(target=queue.supervised_attempt,
                args=(self.store.root, job, IsolatedBackend(), admission.fd, worker_lock.fd, child, parent))
            process.start()
            child.close()
            try:
                parent.settimeout(5)
                identity = json.loads(parent.recv(4096))
                self.store.acknowledge(job["job_id"], job["attempt_id"], identity)
                parent.close()  # Simulate death after identity persistence, before launch ACK.
                process.join(5)
                self.assertFalse(process.is_alive())
                self.assertFalse((self.store.root / "test-host-active").exists())
                queue.Worker(self.store, IsolatedBackend()).reconcile()
                self.assertEqual("quarantined", self.store.host()["state"])
            finally:
                parent.close()
                if process.is_alive():
                    process.terminate()
                    process.join(5)

    def test_supervisor_crash_windows_never_release_without_durable_proof(self):
        for key in ("crash-after-launch", "crash-after-cleanup"):
            with self.subTest(window=key), tempfile.TemporaryDirectory() as raw:
                store = queue.Store(Path(raw))
                first = store.submit("prepared", "digest", key)
                second = store.submit("prepared", "digest", "next")
                stop = multiprocessing.get_context("spawn").Event()
                process = multiprocessing.get_context("spawn").Process(target=worker_process, args=(str(store.root), stop, 1))
                process.start()
                try:
                    process.join(8)
                    self.assertFalse(process.is_alive())
                    self.assertEqual("quarantined", store.jobs(first["job_id"])[0]["state"])
                    self.assertEqual("queued", store.jobs(second["job_id"])[0]["state"])
                    queue.Worker(store, IsolatedBackend()).reconcile()
                    self.assertEqual("quarantined", store.host()["state"])
                    self.assertFalse((store.root / "jobs" / first["job_id"] / "outcome.json").exists())
                finally:
                    stop.set()
                    if process.is_alive():
                        process.terminate()
                        process.join(5)
    def start_worker(self, maximum=1):
        context = multiprocessing.get_context("spawn")
        stop = context.Event()
        process = context.Process(target=worker_process, args=(str(self.root), stop, maximum))
        process.start()
        def cleanup():
            stop.set()
            queue.wake(self.store)
            process.join(5)
            if process.is_alive():
                process.terminate()  # Only the explicit test process, never Cubism.
                process.join(5)
        self.addCleanup(cleanup)
        return process, stop

    def await_state(self, job_id, state):
        deadline = time.monotonic() + 8
        while time.monotonic() < deadline:
            if self.store.jobs(job_id)[0]["state"] == state:
                return
            time.sleep(0.01)
        self.fail(f"did not reach {state}: {self.store.jobs(job_id)}")

    def test_twenty_safe_failures_continue_without_overlap_or_batch_barrier(self):
        jobs = [self.job(str(i)) for i in range(21)]  # 21 jobs yield 20 handoffs.
        process, _ = self.start_worker(21)
        process.join(12)
        self.assertEqual(0, process.exitcode)
        intervals = [json.loads(line) for line in (self.root / "intervals.jsonl").read_text().splitlines()]
        self.assertEqual([job["job_id"] for job in jobs], [row["jobId"] for row in intervals])
        self.assertEqual(20, len(intervals) - 1)
        for before, after in zip(intervals, intervals[1:]):
            self.assertGreaterEqual(after["start"], before["end"])
            self.assertLessEqual(after["start"] - before["end"], 5)
        self.assertTrue(all(job["state"] == "failed" for job in self.store.jobs()))

    def test_duplicate_worker_cannot_steal_and_cancel_is_safe(self):
        job = self.job("slow")
        process, _ = self.start_worker()
        self.await_state(job["job_id"], "running")
        with self.assertRaisesRegex(queue.QueueError, "already owned"):
            queue.Worker(self.store, IsolatedBackend()).serve(max_jobs=0)
        self.store.cancel(job["job_id"])
        queue.wake(self.store)
        process.join(5)
        self.assertEqual(0, process.exitcode)
        self.assertEqual("cancelled", self.store.jobs(job["job_id"])[0]["state"])
        self.assertFalse((self.root / "test-host-active").exists())

    def test_cleanup_failure_blocks_next_job(self):
        job = self.job("cleanup-failure")
        second = self.job("second")
        process, _ = self.start_worker()
        process.join(5)
        self.assertEqual(0, process.exitcode)
        self.assertEqual("quarantined", self.store.jobs(job["job_id"])[0]["state"])
        self.assertEqual("queued", self.store.jobs(second["job_id"])[0]["state"])
        self.assertIsNone(self.store.claim())

    def test_crash_after_claim_never_requeues_attempt(self):
        self.job("first"); self.job("second")
        first = self.store.claim()
        queue.Worker(self.store, IsolatedBackend()).reconcile()
        restored = self.store.jobs(first["job_id"])[0]
        self.assertEqual(first["attempt_id"], restored["attempt_id"])
        self.assertEqual("quarantined", restored["state"])
        self.assertIsNone(self.store.claim())

    def test_proven_read_only_preflight_failure_does_not_block_next_job(self):
        first = self.job("preflight-failure")
        second = self.job("next")
        process, _ = self.start_worker(2)
        process.join(8)
        self.assertEqual(0, process.exitcode)
        first_result = self.store.jobs(first["job_id"])[0]
        self.assertEqual("failed", first_result["state"])
        self.assertTrue(json.loads(first_result["evidence_json"])["noHostSideEffects"])
        self.assertEqual("failed", self.store.jobs(second["job_id"])[0]["state"])
        self.assertEqual("idle", self.store.host()["state"])

    def test_restarted_worker_observes_live_supervisor_and_continues_fifo(self):
        first = self.job("slow")
        second = self.job("next")
        old, _ = self.start_worker(1)
        deadline = time.monotonic() + 5
        while not (self.root / "test-host-active").exists() and time.monotonic() < deadline:
            time.sleep(0.01)
        self.assertTrue((self.root / "test-host-active").exists())
        os.kill(old.pid, signal.SIGKILL)  # Only this test's known worker, not its supervisor.
        old.join(5)
        restarted, _ = self.start_worker(1)
        restarted.join(8)
        self.assertEqual(0, restarted.exitcode)
        self.assertEqual("failed", self.store.jobs(first["job_id"])[0]["state"])
        self.assertEqual("failed", self.store.jobs(second["job_id"])[0]["state"])
        self.assertEqual("idle", self.store.host()["state"])
        intervals = [json.loads(row) for row in (self.root / "intervals.jsonl").read_text().splitlines()]
        self.assertEqual([first["job_id"], second["job_id"]], [row["jobId"] for row in intervals])
        self.assertLessEqual(intervals[1]["start"] - intervals[0]["end"], 5)

    def test_supervisor_outcome_survives_worker_death_and_reconciles(self):
        job = self.job("slow")
        process, _ = self.start_worker()
        self.await_state(job["job_id"], "running")
        os.kill(process.pid, signal.SIGKILL)  # Known test worker, never a host process.
        process.join(5)
        outcome = self.root / "jobs" / job["job_id"] / "outcome.json"
        deadline = time.monotonic() + 5
        while not outcome.exists() and time.monotonic() < deadline:
            time.sleep(0.02)
        self.assertTrue(outcome.exists())
        # Child exit follows the atomic outcome; wait for its recorded identity to disappear.
        identity = json.loads(self.store.jobs(job["job_id"])[0]["identity_json"])
        while queue.identity_alive(identity) and time.monotonic() < deadline:
            time.sleep(0.02)
        queue.Worker(self.store, IsolatedBackend()).reconcile()
        self.assertEqual("failed", self.store.jobs(job["job_id"])[0]["state"])
        self.assertEqual("idle", self.store.host()["state"])


class AdmissionTest(StoreFixture, unittest.TestCase):
    def helper(self, job, fd, *, inherit=True, scope_verified=True):
        environment = dict(os.environ)
        environment.update({"TURBOISM_QUEUE_JOB_ID": job["job_id"],
            "TURBOISM_QUEUE_ATTEMPT_ID": job["attempt_id"], "TURBOISM_QUEUE_RUN_ID": job["run_id"],
            "TURBOISM_QUEUE_ADMISSION_FD": str(fd), "TURBOISM_QUEUE_SUPERVISOR_PID": str(os.getpid())})
        code = "import sys; from pathlib import Path; sys.path.insert(0,sys.argv[1]); import host_validation_queue as q; q.account_root=lambda:Path(sys.argv[2]); "
        if scope_verified:
            # Isolate queue/FD/ancestry rules. Kernel containment has its own tests.
            code += "q.verify_containment_membership=lambda store,job: {'testOnly':True}; "
        code += "print(q.canonical_json(q.validate_admission()))"
        return subprocess.run([sys.executable, "-c", code, str(Path(queue.__file__).parent), str(self.root)],
            pass_fds=(fd,) if inherit else (), env=environment, text=True, capture_output=True)

    def test_inherited_locked_fd_and_live_ancestor_are_required(self):
        self.job()
        job = self.store.claim()
        self.store.acknowledge(job["job_id"], job["attempt_id"], queue.process_identity(os.getpid()))
        with queue.FileLock(self.root / "admission.lock") as lock:
            result = self.helper(job, lock.fd)
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertEqual(job["attempt_id"], json.loads(result.stdout)["attemptId"])
            self.assertNotEqual(0, self.helper(job, lock.fd, inherit=False).returncode)
            self.assertNotEqual(0, self.helper(job, lock.fd, scope_verified=False).returncode)
            self.assertNotEqual(0, self.helper({**job, "attempt_id": "wrong"}, lock.fd).returncode)
        fd = os.open(self.root / "admission.lock", os.O_RDWR)
        try:
            self.assertNotEqual(0, self.helper(job, fd).returncode)
        finally:
            os.close(fd)

    def test_bare_environment_and_quarantined_attempt_cannot_launch(self):
        self.job(); job = self.store.claim()
        with queue.FileLock(self.root / "admission.lock") as lock:
            self.assertNotEqual(0, self.helper(job, lock.fd).returncode)  # no ACK
            self.store.acknowledge(job["job_id"], job["attempt_id"], queue.process_identity(os.getpid()))
            self.store.quarantine(job["job_id"], "lost cleanup")
            self.assertNotEqual(0, self.helper(job, lock.fd).returncode)


if __name__ == "__main__":
    unittest.main()
