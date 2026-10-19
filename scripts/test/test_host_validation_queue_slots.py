#!/usr/bin/env python3
"""Version-scoped slot contracts use private temporary roots and fake Xvfb
processes; nothing here launches Cubism or touches the production state root."""
from __future__ import annotations

import multiprocessing
import os
from pathlib import Path
import sqlite3
import subprocess
import sys
import tempfile
import unittest
import json
import time
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "preview"))
import host_validation_queue as queue
import hvq.common
import hvq.display
import hvq.snapshot


FAKE_XVFB_CODE = (
    "import ctypes,sys,time;"
    "ctypes.CDLL(None).prctl(15, sys.argv[1].encode());"  # PR_SET_NAME -> comm
    "time.sleep(60)")


def fake_xvfb(sink, display):
    """A harmless stand-in whose comm/cmdline look like an Xvfb on `display`."""
    process = subprocess.Popen([sys.executable, "-c", FAKE_XVFB_CODE, "Xvfb", display])
    sink.append(process)
    return process


class SlotBackend:
    """Isolated backend; records execution windows, never invokes a host."""
    def busy(self, owned_markers=frozenset()):
        return []

    def run(self, store, job, admission_fd):
        if store.root == queue.account_root():
            raise AssertionError("test backend reached production state")
        started = time.monotonic()
        time.sleep(0.4 if job["request_key"].startswith("slow") else 0.02)
        with (store.root / "slots.jsonl").open("a") as stream:
            stream.write(json.dumps({"jobId": job["job_id"], "key": job["request_key"],
                "display": job["display"], "start": started, "end": time.monotonic()}) + "\n")
        return {"schemaVersion": 1, "jobId": job["job_id"], "attemptId": job["attempt_id"],
                "runId": job["run_id"], "preparedDigest": job["digest"], "cleanup": "safe",
                "validationStatus": "FAIL", "terminalState": "failed"}


def slot_worker(root: str, maximum: int) -> None:
    queue.Worker(queue.Store(Path(root)), SlotBackend()).serve(max_jobs=maximum)


class SlotFixture:
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.base = Path(self.temp.name)
        self.store = queue.Store(self.base / "queue")
        self.root = self.store.root
        self.prepared = queue.PreparedStore(self.store)
        self.source = self.base / "source"
        self.preview = self.source / "scripts/preview"
        self.preview.mkdir(parents=True)
        for name in ("run-cubism-host-validation.sh", "host-validation-env.sh", "host-validation-transport.sh",
                     "archive-cubism-host-evidence.sh", "host_validation.py", "host_validation_queue.py",
                     "host_validation_containment.py", "host_validation_evidence.py"):
            (self.preview / name).write_text("# test fixture, never executed\n")
        (self.preview / "hvq").mkdir()
        (self.preview / "hvq/__init__.py").write_text("# test fixture, never executed\n")
        subprocess.run(["git", "init", "-q", str(self.source)], check=True)
        subprocess.run(["git", "add", "."], cwd=self.source, check=True)
        subprocess.run(["git", "-c", "user.name=Slot Test", "-c", "user.email=test@example.invalid",
                        "-c", "core.hooksPath=/dev/null", "-c", "commit.gpgsign=false", "commit", "-qm", "fixture"],
                       cwd=self.source, check=True)
        self.input = self.base / "input.jar"
        self.input.write_bytes(b"synthetic input")
        self.displays = self.base / "x11-sockets"
        self.displays.mkdir()
        self.spawned: list[subprocess.Popen] = []
        patches = [
            mock.patch.object(hvq.display, "xvfb_available", return_value=True),
            mock.patch.object(hvq.display, "spawn_xvfb",
                              side_effect=lambda display: fake_xvfb(self.spawned, display)),
            mock.patch.object(hvq.common, "X11_SOCKET_DIR", str(self.displays)),
            mock.patch.object(queue, "xvfb_available", return_value=True),
            mock.patch.object(queue, "spawn_xvfb",
                              side_effect=lambda display: fake_xvfb(self.spawned, display)),
            mock.patch.object(queue, "X11_SOCKET_DIR", str(self.displays)),
        ]
        for patcher in patches:
            self.addCleanup(patcher.stop)
            patcher.start()
        self.addCleanup(self._kill_spawned)

    def _kill_spawned(self) -> None:
        for process in self.spawned:
            if process.poll() is None:
                process.kill()
                process.wait(5)

    def submit(self, task: str = "parameter", version: str = "5302", key: str | None = None) -> dict:
        request = {"schemaVersion": 1, "environment": {},
                   "argv": ["--name", "test", "--agent", str(self.input), "--version", version]}
        prepared = self.prepared.capture(request, self.source, f"{task}:{version}")
        return self.store.submit(prepared["digest"], prepared["digest"],
                                 key or f"{task}-{version}-{len(self.store.jobs())}")

    @staticmethod
    def evidence(job: dict) -> dict:
        return {"schemaVersion": 1, "jobId": job["job_id"], "attemptId": job["attempt_id"],
                "runId": job["run_id"], "preparedDigest": job["digest"],
                "cleanup": "safe", "validationStatus": "PASS",
                "identityVerified": True, "fixtureUnchanged": True, "normalExit": True}


class VersionedSlotClaimTest(SlotFixture, unittest.TestCase):
    def test_different_versions_claim_same_version_serializes(self) -> None:
        first = self.submit("parameter", "5203", "a")
        second = self.submit("parameter", "5302", "b")
        duplicate = self.submit("parameter", "5302", "c")
        a = self.store.claim()
        b = self.store.claim()
        self.assertEqual((first["job_id"], second["job_id"]), (a["job_id"], b["job_id"]))
        self.assertEqual(("5203", "5302"), (a["version"], b["version"]))
        self.assertNotEqual(a["display"], b["display"])
        self.assertIsNone(self.store.claim())
        self.assertEqual("queued", self.store.jobs(duplicate["job_id"])[0]["state"])
        # A finished attempt frees its version slot for the FIFO waiter.
        self.store.complete(b["job_id"], "failed", self.evidence(b))
        claimed = self.store.claim()
        self.assertEqual(duplicate["job_id"], claimed["job_id"])

    def test_fifo_order_preserved_within_constraints(self) -> None:
        submitted = [self.submit("parameter", version, key)
                     for version, key in (("5203", "a"), ("5203", "b"), ("5302", "c"))]
        first = self.store.claim()
        second = self.store.claim()
        self.assertEqual(submitted[0]["job_id"], first["job_id"])
        self.assertEqual(submitted[2]["job_id"], second["job_id"])
        self.assertEqual("queued", self.store.jobs(submitted[1]["job_id"])[0]["state"])

    def test_performance_host_excludes_and_also_waits_for_empty(self) -> None:
        perf = self.submit("fps", "5203", "perf")
        other = self.submit("parameter", "5302", "other")
        claimed = self.store.claim()
        self.assertEqual(perf["job_id"], claimed["job_id"])
        self.assertIsNone(self.store.claim())
        self.store.complete(perf["job_id"], "failed", self.evidence(claimed))
        follow = self.store.claim()
        self.assertEqual(other["job_id"], follow["job_id"])

    def test_performance_host_waits_for_empty_host(self) -> None:
        first = self.submit("parameter", "5203", "a")
        self.submit("fps", "5302", "perf")
        claimed = self.store.claim()
        self.assertEqual(first["job_id"], claimed["job_id"])
        self.assertIsNone(self.store.claim())

    def test_interactive_desktop_gets_real_display_and_blocks_all(self) -> None:
        desk = self.submit("backup-interactive", "5302", "desk")
        self.submit("parameter", "5203", "other")
        claimed = self.store.claim()
        self.assertEqual(desk["job_id"], claimed["job_id"])
        self.assertEqual(":0", claimed["display"])
        self.assertIsNone(claimed["xvfb_pid"])
        self.assertEqual([], self.spawned)
        self.assertIsNone(self.store.claim())

    def test_private_displays_are_distinct_and_released_on_finish(self) -> None:
        first = self.submit("edit", "5203", "a")
        second = self.submit("edit", "5302", "b")
        claimed_a = self.store.claim()
        claimed_b = self.store.claim()
        self.assertEqual(second["job_id"], claimed_b["job_id"])
        for claimed in (claimed_a, claimed_b):
            self.store.acknowledge(claimed["job_id"], claimed["attempt_id"],
                                   queue.process_identity(os.getpid()))
        self.assertNotEqual(claimed_a["display"], claimed_b["display"])
        self.assertTrue(claimed_a["display"].startswith(":"))
        self.assertIsNotNone(claimed_a["xvfb_pid"])
        process = self.store.xvfb_processes[claimed_a["job_id"]]
        freed = claimed_a["display"]
        self.store.complete(claimed_a["job_id"], "failed", self.evidence(claimed_a))
        queue.Worker(self.store, SlotBackend()).reconcile()
        self.assertIsNotNone(process.poll())  # Xvfb terminated with the job.
        row = self.store.jobs(first["job_id"])[0]
        self.assertIsNone(row["display"])
        self.assertIsNone(row["xvfb_pid"])
        third = self.submit("edit", "5303", "c")
        claimed_c = self.store.claim()
        self.assertEqual(third["job_id"], claimed_c["job_id"])
        self.assertEqual(freed, claimed_c["display"])  # Slot returned to the pool.

    def test_missing_xvfb_defers_display_jobs_but_desktop_still_runs(self) -> None:
        display_job = self.submit("parameter", "5203", "d")
        desk = self.submit("backup-interactive", "5302", "desk")
        with mock.patch.object(hvq.display, "xvfb_available", return_value=False), \
                mock.patch.object(queue, "xvfb_available", return_value=False):
            claimed = self.store.claim()
            self.assertEqual(desk["job_id"], claimed["job_id"])
            self.assertEqual(":0", claimed["display"])
            self.assertIsNone(self.store.claim())
        self.assertEqual("queued", self.store.jobs(display_job["job_id"])[0]["state"])
        deferred = [event for event in self.store.events(job_id=display_job["job_id"])
                    if event["kind"] == "display-pool-unavailable"]
        self.assertEqual(1, len(deferred))  # Logged once, not every scan pass.

    def test_legacy_row_is_serial_only_and_blocks_parallel(self) -> None:
        legacy = self.store.submit("prepared", "digest", "legacy")
        versioned = self.submit("parameter", "5302", "v")
        first = self.store.claim()
        self.assertEqual(legacy["job_id"], first["job_id"])
        self.assertIsNone(first["version"])
        self.assertIsNone(self.store.claim())  # Unversioned attempt blocks all.
        self.store.complete(legacy["job_id"], "failed", self.evidence(first))
        claimed = self.store.claim()
        self.assertEqual(versioned["job_id"], claimed["job_id"])

    def test_versioned_running_blocks_legacy_claim(self) -> None:
        self.submit("parameter", "5302", "v")
        legacy = self.store.submit("prepared", "digest", "legacy")
        claimed = self.store.claim()
        self.assertEqual("5302", claimed["version"])
        self.assertIsNone(self.store.claim())
        self.assertEqual("queued", self.store.jobs(legacy["job_id"])[0]["state"])

    def test_old_protocol_descriptor_is_serial_only(self) -> None:
        # A descriptor prepared before per-job admission cannot parallel claim.
        with mock.patch.object(hvq.snapshot, "QUEUE_PROTOCOL", 1), \
                mock.patch.object(hvq.common, "QUEUE_PROTOCOL", 1), \
                mock.patch.object(queue, "QUEUE_PROTOCOL", 1):
            request = {"schemaVersion": 1, "environment": {}, "argv": [
                "--name", "test", "--agent", str(self.input), "--version", "5203"]}
            prepared = self.prepared.capture(request, self.source, "parameter:5203")
        legacy = self.store.submit(prepared["digest"], prepared["digest"], "old")
        self.submit("parameter", "5302", "v")
        claimed = self.store.claim()
        self.assertEqual(legacy["job_id"], claimed["job_id"])
        self.assertIsNone(claimed["version"])
        self.assertIsNone(self.store.claim())

    def test_schema_migration_is_additive_for_old_rows(self) -> None:
        old_root = self.base / "old-queue"
        old_root.mkdir(mode=0o700)
        database = sqlite3.connect(old_root / "queue.sqlite3")
        database.executescript("""
            CREATE TABLE retention_objects(
                kind TEXT NOT NULL, object_id TEXT NOT NULL, created_at REAL NOT NULL,
                pin TEXT NOT NULL DEFAULT '', retired INTEGER NOT NULL DEFAULT 0,
                metadata TEXT NOT NULL DEFAULT '{}', PRIMARY KEY(kind,object_id));
            CREATE TABLE metadata(version INTEGER NOT NULL);
            INSERT INTO metadata VALUES(1);
            CREATE TABLE jobs(
                sequence INTEGER PRIMARY KEY AUTOINCREMENT,
                job_id TEXT NOT NULL UNIQUE, request_key TEXT NOT NULL UNIQUE,
                prepared_id TEXT NOT NULL, digest TEXT NOT NULL,
                timeout_seconds INTEGER NOT NULL, state TEXT NOT NULL,
                reason TEXT NOT NULL DEFAULT '', created_at REAL NOT NULL,
                updated_at REAL NOT NULL, cancel_requested INTEGER NOT NULL DEFAULT 0,
                attempt_id TEXT UNIQUE, run_id TEXT UNIQUE,
                identity_json TEXT, evidence_json TEXT);
            CREATE TABLE host(
                singleton INTEGER PRIMARY KEY CHECK(singleton=1),
                state TEXT NOT NULL, job_id TEXT, reason TEXT NOT NULL);
            INSERT INTO host VALUES(1, 'idle', NULL, '');
            CREATE TABLE events(
                event_id INTEGER PRIMARY KEY AUTOINCREMENT,
                job_id TEXT, kind TEXT NOT NULL, payload TEXT NOT NULL,
                created_at REAL NOT NULL);
            INSERT INTO jobs(job_id,request_key,prepared_id,digest,timeout_seconds,
                state,created_at,updated_at) VALUES('j1','r1','p','d',100,'queued',1.0,1.0);
        """)
        database.close()
        store = queue.Store(old_root)
        with store.connection() as db:
            self.assertEqual(2, db.execute("PRAGMA user_version").fetchone()[0])
        row = store.jobs("j1")[0]
        self.assertIn("version", row)
        self.assertIsNone(row["version"])
        claimed = store.claim()  # Legacy rows still claim through the serial path.
        self.assertEqual("j1", claimed["job_id"])
        self.assertIsNone(claimed["version"])
        self.assertIsNone(store.claim())


class ExternalSessionTest(unittest.TestCase):
    def proc_entry(self, proc: Path, pid: int, comm: str, cmdline: bytes, environ: bytes = b"") -> None:
        directory = proc / str(pid)
        directory.mkdir(parents=True)
        (directory / "comm").write_text(comm)
        (directory / "cmdline").write_bytes(cmdline)
        (directory / "environ").write_bytes(environ)

    def test_owned_markers_exclude_task_processes(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            proc = Path(temporary) / "proc"
            proc.mkdir()
            self.proc_entry(proc, 1111, "java", b"java\x00-jar\x00/opt/cubism/Live2D_Cubism.jar")
            self.proc_entry(proc, 2222, "java",
                b"java\x00-jar\x00/state/tasks/queue-abc123/prefix/drive_c/Live2D_Cubism.jar")
            self.proc_entry(proc, 3333, "java", b"java\x00CECubismEditorApp",
                b"WINEPREFIX=/tasks/queue-def456/pfx\x00HOME=/home/x")
            self.proc_entry(proc, 4444, "bash", b"bash\x00helper\x00CubismEditor")
            owned = {"queue-abc123", "queue-def456"}
            self.assertEqual([1111], queue.external_sessions(owned, proc_root=proc))
            self.assertEqual([1111, 2222, 3333], queue.external_sessions(proc_root=proc))

    def test_foreign_session_blocks_whole_host(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            proc = Path(temporary) / "proc"
            proc.mkdir()
            self.proc_entry(proc, 1111, "java", b"java\x00-jar\x00/opt/cubism/Live2D_Cubism.jar")
            self.assertEqual([1111], queue.external_sessions({"queue-abc"}, proc_root=proc))


class SlotWorkerTest(SlotFixture, unittest.TestCase):
    def test_worker_runs_two_versions_in_parallel(self) -> None:
        self.submit("parameter", "5203", "slow-a")
        self.submit("parameter", "5302", "slow-b")
        context = multiprocessing.get_context("fork")
        process = context.Process(target=slot_worker, args=(str(self.root), 2))
        process.start()
        try:
            process.join(15)
            self.assertFalse(process.is_alive())
            self.assertEqual(0, process.exitcode)
        finally:
            if process.is_alive():
                process.terminate()
                process.join(5)
        rows = [json.loads(line) for line in (self.root / "slots.jsonl").read_text().splitlines()]
        self.assertEqual(2, len(rows))
        earlier, later = sorted(rows, key=lambda row: row["start"])
        self.assertLess(later["start"], earlier["end"])  # Real overlap, not serialization.
        self.assertNotEqual(rows[0]["display"], rows[1]["display"])
        self.assertTrue(all(row["display"].startswith(":") for row in rows))
        self.assertEqual("idle", self.store.host()["state"])
        for job in self.store.jobs():
            self.assertIsNone(job["xvfb_pid"])
            self.assertIsNone(job["display"])
        for process_ in self.spawned:
            self.assertIsNotNone(process_.poll())

    def test_worker_crash_recovers_slot_and_kills_xvfb(self) -> None:
        job = self.submit("edit", "5203", "v")
        claimed = self.store.claim()
        process = self.store.xvfb_processes[claimed["job_id"]]
        # A real Xvfb identifies itself via comm quickly; mirror that before the
        # crash so the recovered sweep can verify ownership of the stale pid.
        comm = Path("/proc") / str(process.pid) / "comm"
        deadline = time.monotonic() + 5
        while time.monotonic() < deadline and comm.read_text().strip() != "Xvfb":
            time.sleep(0.01)
        self.assertEqual("Xvfb", comm.read_text().strip())
        # A dead worker loses its process map; reconcile uses the recorded pid.
        fresh = queue.Store(self.root)
        self.assertEqual({}, fresh.xvfb_processes)
        queue.Worker(fresh, SlotBackend()).reconcile()
        row = fresh.jobs(job["job_id"])[0]
        self.assertEqual("quarantined", row["state"])
        self.assertIsNone(row["xvfb_pid"])
        self.assertIsNone(row["display"])
        self.assertEqual("quarantined", fresh.host()["state"])
        deadline = time.monotonic() + 5
        while process.poll() is None and time.monotonic() < deadline:
            time.sleep(0.02)
        self.assertIsNotNone(process.poll())

    def test_per_job_admission_lock_and_legacy_fallback(self) -> None:
        self.submit("parameter", "5302", "v")
        job = self.store.claim()
        self.store.acknowledge(job["job_id"], job["attempt_id"], queue.process_identity(os.getpid()))
        environment = dict(os.environ)
        environment.update({"TURBOISM_QUEUE_JOB_ID": job["job_id"],
            "TURBOISM_QUEUE_ATTEMPT_ID": job["attempt_id"], "TURBOISM_QUEUE_RUN_ID": job["run_id"],
            "TURBOISM_QUEUE_SUPERVISOR_PID": str(os.getpid())})
        code = ("import sys; from pathlib import Path; sys.path.insert(0,sys.argv[1]); "
                "import host_validation_queue as q, hvq.common as c, hvq.runner as r; "
                "c.account_root=q.account_root=lambda:Path(sys.argv[2]); "
                "r.verify_containment_membership=q.verify_containment_membership=lambda store,job: {'testOnly':True}; "
                "print(q.canonical_json(q.validate_admission()))")

        def admit(fd, inherit=True):
            environment["TURBOISM_QUEUE_ADMISSION_FD"] = str(fd)
            return subprocess.run([sys.executable, "-c", code,
                str(Path(queue.__file__).parent), str(self.root)],
                pass_fds=(fd,) if inherit else (), env=environment, text=True, capture_output=True)

        per_job = self.root / f"admission-{job['job_id']}.lock"
        with queue.FileLock(per_job) as lock:
            result = admit(lock.fd)
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertNotEqual(0, admit(lock.fd, inherit=False).returncode)
        # The shared lock remains a valid proof (old worker dispatch, legacy slot).
        with queue.FileLock(self.root / "admission.lock") as lock:
            self.assertEqual(0, admit(lock.fd).returncode)
        # An unrelated open file is not an admission token.
        fd = os.open(self.root / "storage.lock", os.O_RDWR | os.O_CREAT, 0o600)
        try:
            self.assertNotEqual(0, admit(fd).returncode)
        finally:
            os.close(fd)


if __name__ == "__main__":
    unittest.main()
