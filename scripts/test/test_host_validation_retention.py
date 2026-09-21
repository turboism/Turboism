#!/usr/bin/env python3
"""Retention safety regressions: synthetic files and private queues; never Cubism."""
from pathlib import Path
import contextlib
import copy
import io
import json
import multiprocessing
import os
import shutil
import sqlite3
import stat
import sys
import tarfile
import tempfile
import time
import unittest
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "preview"))
import host_validation_queue as q
import host_validation_retention as gc
import host_validation as cli


def locked_submit(root, prepared, ready, done):
    ready.set()
    q.Store(Path(root)).submit(prepared, prepared, "concurrent")
    done.set()


class RetentionTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="turboism-retention-test-")
        self.addCleanup(self.temp.cleanup)
        self.base = Path(self.temp.name)
        self.root = self.base / "queue"
        self.store = q.Store(self.root)
        self.host = self.base / "host"
        self.host.mkdir()
        self.sources = self.base / "sources"
        self.sources.mkdir()
        (self.sources / "model.cmo3").write_bytes(b"original-model")
        self.now = time.time()
        q.atomic_json(self.root / "retention-policy.json", {"writersMigrated": True})
        self.prepared = self.make_prepared()

    def make_prepared(self, keep=False, marker=False, result_file=None):
        stage = self.root / "staging" / "synthetic"
        stage.mkdir()
        (stage / "fixture.cmo3").write_bytes(b"source")
        (stage / "agent.jar").write_bytes(b"synthetic-not-executable")
        descriptor = {"schemaVersion": 1, "taskSpec": "test", "source": {"worktree": str(self.sources)},
            "sourceInputs": [{"source": str(self.sources / "model.cmo3")}], "hostDependencies": [],
            "argv": ["--name", "test", "--version", "5302", "--run-label", "one",
                "--host-root", str(self.host), "--golden-prefix", str(self.base / "golden"),
                "--fixture-host", "@INPUT@/fixture.cmo3", "--agent", "@INPUT@/agent.jar"],
            "inventory": q.tree_inventory(stage)}
        if keep:
            descriptor["argv"].append("--keep-prefix")
        if marker:
            descriptor["argv"].extend(["--result-marker", "FINAL_PASS"])
        if result_file:
            descriptor["argv"].extend(["--result-file", result_file])
        digest = q.digest_json(descriptor)
        q.atomic_json(stage / "prepared.json", {**descriptor, "digest": digest})
        dest = self.root / "prepared" / digest
        stage.rename(dest)
        info = dest.stat()
        with self.store.transaction() as db:
            db.execute("INSERT INTO retention_objects(kind,object_id,created_at,metadata) VALUES('prepared',?,?,?)",
                       (digest, self.now - 20 * gc.DAY, q.canonical_json({"device": info.st_dev, "inode": info.st_ino})))
        return digest

    def finish(self, state="failed", age=20, prepared=None):
        prepared = prepared or self.prepared
        submitted = self.store.submit(prepared, prepared, "job-" + str(len(self.store.jobs())))
        job = self.store.claim()
        self.assertEqual(submitted["job_id"], job["job_id"])
        task = self.host / "test" / "5302-one" / job["run_id"]
        (task / "prefix/pfx").mkdir(parents=True)
        (task / "prefix/pfx/data").write_bytes(b"clone")
        (task / "turboism-home/logs").mkdir(parents=True)
        (task / "turboism-home/logs/runtime.log").write_bytes(b"diagnostic")
        (task / "turboism-home/state").mkdir()
        (task / "turboism-home/state/result.json").write_bytes(b'{"assertion":"preserve"}')
        (task / "saved-model.cmo3").write_bytes(b"saved-result")
        (task / "evidence").mkdir()
        (task / "evidence/assertions.json").write_bytes(b'{"expected":1,"actual":1}')
        identity = {"jobId": job["job_id"], "attemptId": job["attempt_id"], "runId": job["run_id"], "preparedDigest": prepared}
        entry = {"pid": 999999999, "startTicks": "1", "bootId": "synthetic-boot", "uid": os.getuid(), "gid": os.getgid()}
        kernel = {"originalCgroupBound": True, "errors": [], "bootId": "synthetic-boot",
            "cgroupPath": "/synthetic/scope", "cgroupDevice": 1, "cgroupInode": 2, "finalReading": {"kind": "destroyed"}}
        proof = {"schemaVersion": 1, **identity, "cleanup": "safe", "bootId": "synthetic-boot", "scopeUnit": "synthetic.scope",
            "cgroupPath": "/synthetic/scope", "cgroupDevice": 1, "cgroupInode": 2, "entryIdentity": entry, "kernelProof": kernel}
        final = {"schemaVersion": 1, **identity, "cleanup": "safe", "terminalState": state,
            "validationStatus": "PASS" if state == "succeeded" else "FAIL", "identityVerified": True,
            "fixtureUnchanged": True, "normalExit": True, "finalizedBy": "contained-supervisor", "containment": proof,
            "details": {"taskDir": str(task), "taskOwnedCleanup": True,
                "postContainmentChecks": {"terminalResult": {"passed": state == "succeeded"}}}}
        directory = self.root / "jobs" / job["job_id"]
        (directory / "evidence").mkdir(parents=True)
        q.atomic_json(directory / "containment.json", {**proof, "state": "FINISHED"})
        q.atomic_json(directory / "runner-identity.json", q.scope_identity(entry))
        q.atomic_json(directory / "outcome.json", final)
        q.atomic_json(directory / "evidence/lifecycle-result.json", final)
        (directory / "runner.log").write_text("verbose log")
        self.store.complete(job["job_id"], state, final)
        with self.store.transaction() as db:
            db.execute("UPDATE jobs SET updated_at=? WHERE job_id=?", (self.now - age * gc.DAY, job["job_id"]))
        return self.store.jobs(job["job_id"])[0], task, directory

    def plan(self):
        return gc.plan(self.root, now=self.now)

    def apply(self, report=None):
        report = report or self.plan()
        return gc.apply(report, report["planDigest"], self.root, now=self.now, busy=lambda: [])

    def test_default_report_does_not_delete_or_change_queue(self):
        job, task, _ = self.finish()
        before = q.tree_inventory(self.root)
        report = self.plan()
        self.assertEqual(before, q.tree_inventory(self.root))
        self.assertEqual({"prefix", "environment", "prepared"}, {i["kind"] for i in report["candidates"]})
        self.assertTrue((task / "prefix").exists())
        self.assertFalse(report["policy"]["enabled"])

    def test_success_and_failure_ttl_boundaries(self):
        for state, days in (("failed", 14), ("succeeded", 3), ("timed_out", 14), ("cancelled", 14)):
            job, task, _ = self.finish(state, age=days - 0.001)
            self.assertNotIn(job["job_id"], {i["id"] for i in self.plan()["candidates"]})
            with self.store.transaction() as db:
                db.execute("UPDATE jobs SET updated_at=? WHERE job_id=?", (self.now - days * gc.DAY, job["job_id"]))
            self.assertIn(job["job_id"], {i["id"] for i in self.plan()["candidates"]})

    def test_archive_then_collect_preserves_core_and_idempotency(self):
        job, task, directory = self.finish()
        report = self.plan()
        result = self.apply(report)
        self.assertEqual([], result["errors"])
        self.assertFalse((task / "prefix").exists())
        self.assertFalse((task / "saved-model.cmo3").exists())
        self.assertTrue((task / "evidence/assertions.json").exists())
        self.assertTrue((directory / "outcome.json").exists())
        with tarfile.open(directory / "retention-archive/core.tar.gz") as tar:
            self.assertEqual(b"saved-result", tar.extractfile("saved-model.cmo3").read())
            self.assertNotIn("turboism-home/logs/runtime.log", tar.getnames())
        with tarfile.open(directory / "retention-archive/logs.tar.gz") as tar:
            self.assertEqual(b"diagnostic", tar.extractfile("turboism-home/logs/runtime.log").read())
        self.assertFalse((self.root / "prepared" / self.prepared).exists())
        self.assertEqual(job["job_id"], self.store.submit(self.prepared, self.prepared, job["request_key"])["job_id"])
        with self.assertRaisesRegex(q.QueueError, "retired"):
            self.store.submit(self.prepared, self.prepared, "new-request")
        self.assertEqual([], self.apply(report)["removed"])

    def test_prepared_shared_with_queued_job_is_protected(self):
        self.finish()
        self.store.submit(self.prepared, self.prepared, "queued-reference")
        report = self.plan()
        self.assertNotIn("prepared", {i["kind"] for i in report["candidates"]})
        self.assertTrue(any("references" in r["reason"] for r in report["retained"]))
        with self.assertRaisesRegex(q.QueueBusy, "queued"):
            self.apply(report)
        self.assertTrue((self.root / "prepared" / self.prepared).exists())

    def test_plan_changed_by_new_reference_cannot_delete_input(self):
        self.finish()
        report = self.plan()
        self.finish(age=0)  # A new safe reference whose diagnostic period has not expired.
        result = self.apply(report)
        self.assertTrue(any(r["kind"] == "prepared" for r in result["skipped"]))
        self.assertTrue((self.root / "prepared" / self.prepared).exists())

    def test_pin_and_keep_prefix_protect_all_job_artifacts_and_inputs(self):
        job, task, _ = self.finish()
        report = self.plan()
        gc.mutate_hold(self.root, "pin", job["job_id"], "acceptance baseline")
        self.assertEqual([], self.apply(report)["removed"])
        gc.mutate_hold(self.root, "unpin", job["job_id"], "baseline superseded")
        self.assertTrue(self.plan()["candidates"])
        kept = self.make_prepared(keep=True)
        kept_job, _, _ = self.finish(prepared=kept)
        self.assertNotIn(kept_job["job_id"], {i["id"] for i in self.plan()["candidates"]})

    def test_missing_terminal_prepared_is_reported_not_blocking(self):
        # Approved spec 041 change (2026-09-17): recycled prepared inputs from
        # terminal-only jobs are reported explicitly and never block collection;
        # non-terminal references still fail closed.
        job, task, directory = self.finish(state="failed", age=20)
        shutil.rmtree(self.root / "prepared" / self.prepared)
        report = self.plan()
        self.assertEqual([], report["blocked"])
        reported = {item["preparedId"] for item in report["missingPreparedInputs"]}
        self.assertIn(self.prepared, reported)
        retained = {item["id"]: item.get("reason", "") for item in report["retained"] if item["kind"] == "job"}
        self.assertIn(job["job_id"], retained)
        self.assertEqual([], [c for c in report["candidates"] if c["id"] == job["job_id"]])
        # Non-terminal references keep failing closed.
        kept = self.make_prepared(marker=True)
        submitted = self.store.submit(kept, kept, "queued-missing-prepared")
        shutil.rmtree(self.root / "prepared" / kept)
        report = self.plan()
        self.assertTrue(any("cannot establish all protected input paths" in reason for reason in report["blocked"]))

    def test_legacy_requires_individual_adoption(self):
        job, task, _ = self.finish()
        with self.store.transaction() as db:
            db.execute("DELETE FROM retention_objects WHERE kind='job'")
        self.assertFalse(self.plan()["candidates"])
        gc.mutate_hold(self.root, "pin", job["job_id"], "hold legacy")
        gc.mutate_hold(self.root, "unpin", job["job_id"], "release hold only")
        self.assertFalse(self.plan()["candidates"])
        gc.mutate_hold(self.root, "adopt", job["job_id"], "reviewed exact ownership")
        self.assertTrue(self.plan()["candidates"])

    def test_active_quarantine_and_external_session_prevent_apply(self):
        self.finish()
        report = self.plan()
        for state in ("owned", "quarantined", "external-busy"):
            with self.store.transaction() as db:
                db.execute("UPDATE host SET state=?", (state,))
            with self.assertRaises(q.QueueError):
                self.apply(report)
        with self.store.transaction() as db:
            db.execute("UPDATE host SET state='idle'")
        with self.assertRaisesRegex(q.QueueError, "external"):
            gc.apply(report, report["planDigest"], self.root, now=self.now, busy=lambda: [123])

    def test_malformed_or_conflicting_cleanup_is_retained(self):
        job, task, directory = self.finish()
        value = gc.read_json(directory / "evidence/lifecycle-result.json")
        value["cleanup"] = "unknown"
        q.atomic_json(directory / "evidence/lifecycle-result.json", value)
        self.assertEqual([], self.plan()["candidates"])
        self.assertTrue((task / "prefix").exists())

    def test_task_replacement_and_symlink_escape_are_rejected(self):
        job, task, _ = self.finish()
        report = self.plan()
        task.rename(task.with_name(task.name + "-saved"))
        task.mkdir()
        (task / "do-not-delete").write_text("other owner")
        self.assertEqual([], self.apply(report)["removed"])
        self.assertTrue((task / "do-not-delete").exists())

    def test_prefix_links_are_unlinked_without_touching_target(self):
        _, task, _ = self.finish()
        outside = self.base / "outside"
        outside.mkdir()
        (outside / "keep").write_text("keep")
        (task / "prefix/pfx/z").symlink_to(outside, target_is_directory=True)
        self.assertFalse(self.apply()["errors"])
        self.assertTrue((outside / "keep").exists())

    def test_ancestor_symlink_blocks_collection(self):
        _, task, _ = self.finish()
        report = self.plan()
        self.host.rename(self.base / "moved-host")
        self.host.symlink_to(self.base / "moved-host", target_is_directory=True)
        self.assertEqual([], self.apply(report)["removed"])
        self.assertTrue((task / "prefix").exists())

    def test_approval_cannot_add_arbitrary_path_even_with_rehashed_plan(self):
        _, task, _ = self.finish()
        report = self.plan()
        report["candidates"][0]["paths"] = [str(self.sources)]
        report["candidates"][0]["fingerprint"] = "tampered"
        report.pop("planDigest")
        report["planDigest"] = q.digest_json(report)
        result = self.apply(report)
        self.assertTrue(result["skipped"])
        self.assertTrue((self.sources / "model.cmo3").exists())

    def test_archive_failure_or_receipt_failure_never_deletes_source(self):
        _, task, _ = self.finish()
        with mock.patch.object(gc, "_tar_files", side_effect=OSError("disk full")):
            self.assertTrue(self.apply()["errors"])
        self.assertTrue((task / "prefix").exists())
        original = q.atomic_json
        def fail_receipt(path, value):
            if path.parent.name == "retention-receipts":
                raise OSError("audit unavailable")
            return original(path, value)
        with mock.patch.object(q, "atomic_json", side_effect=fail_receipt):
            self.assertTrue(self.apply()["errors"])
        self.assertTrue((task / "prefix").exists())

    def test_changed_content_invalidates_approval(self):
        _, task, _ = self.finish()
        report = self.plan()
        (task / "prefix/pfx/data").write_bytes(b"changed")
        result = self.apply(report)
        self.assertTrue(any(i["kind"] == "prefix" for i in result["skipped"]))
        self.assertTrue((task / "prefix").exists())

    def test_interrupted_prepared_retirement_remains_recoverable_and_unsubmittable(self):
        report = self.plan()  # orphan prepared, over seven days
        original = gc.remove_manifest
        def interrupted(path, manifest):
            (path / "fixture.cmo3").unlink()
            raise OSError("injected crash")
        with mock.patch.object(gc, "remove_manifest", side_effect=interrupted):
            self.assertTrue(self.apply(report)["errors"])
        with self.assertRaisesRegex(q.QueueError, "retired"):
            self.store.submit(self.prepared, self.prepared, "after-crash")
        new_report = self.plan()
        self.assertTrue(new_report["candidates"], new_report)
        self.assertEqual([], self.apply(new_report)["errors"])
        self.assertFalse((self.root / "prepared" / self.prepared).exists())

    def test_staging_only_registered_dead_creator_after_24h(self):
        stage = self.root / "staging" / "dead-preparation"
        stage.mkdir()
        (stage / "partial").write_bytes(b"partial")
        info = stage.stat()
        owner = {"bootId": "old-boot", "pid": 999999999, "startTicks": 1, "uid": os.getuid()}
        with self.store.transaction() as db:
            db.execute("INSERT INTO retention_objects(kind,object_id,created_at,metadata) VALUES('staging',?,?,?)",
                       (stage.name, self.now - 25 * 3600, q.canonical_json({"owner": owner, "device": info.st_dev, "inode": info.st_ino})))
        unknown = self.root / "staging" / "unknown"
        unknown.mkdir()
        self.assertFalse(self.apply()["errors"])
        self.assertFalse(stage.exists())
        self.assertTrue(unknown.exists())

    def test_policy_disabled_run_only_reports(self):
        self.finish()
        args = cli.build_parser(Path("unused")).parse_args(["gc", "run"])
        with contextlib.redirect_stdout(io.StringIO()) as output:
            self.assertEqual(0, gc.main(args, root=self.root))
        report = json.loads(output.getvalue())
        self.assertIn("candidates", report)
        self.assertFalse((self.root / "retention-receipts").exists())
        q.atomic_json(self.root / "retention-policy.json", {"enabled": True})
        with self.assertRaisesRegex(q.QueueError, "writersMigrated"):
            self.apply()

    def test_space_guard_uses_actual_free_space_and_rejects_invalid_policy(self):
        usage = shutil.disk_usage(self.root)
        with self.assertRaisesRegex(q.QueueError, "insufficient"):
            gc.check_space(self.root, probe=lambda _: usage._replace(free=1))
        gc.check_space(self.root, probe=lambda _: usage._replace(free=100 * 1024**3))
        for value in (0, -1, float("nan"), True):
            q.atomic_json(self.root / "retention-policy.json", {"minFreeGiB": value})
            with self.assertRaises(q.QueueError):
                gc.policy(self.root)

    def test_storage_lock_blocks_submit_until_collection_finishes(self):
        context = multiprocessing.get_context("spawn")
        ready, done = context.Event(), context.Event()
        process = context.Process(target=locked_submit, args=(str(self.root), self.prepared, ready, done))
        with q.storage_lock(self.root, exclusive=True):
            process.start()
            self.assertTrue(ready.wait(5))
            self.assertFalse(done.wait(0.1))
        process.join(5)
        self.assertEqual(0, process.exitcode)
        self.assertTrue(done.is_set())

    def test_collection_during_prepare_is_rejected_without_deletion(self):
        self.finish()
        report = self.plan()
        with q.storage_lock(self.root):
            with self.assertRaisesRegex(q.QueueError, "storage is busy"):
                self.apply(report)

    def test_logs_expire_after_environment_is_archived(self):
        _, _, directory = self.finish(age=31)
        self.assertFalse(self.apply()["errors"])
        next_report = self.plan()
        self.assertTrue(any(i["kind"] == "logs" for i in next_report["candidates"]))
        self.assertFalse(self.apply(next_report)["errors"])
        self.assertFalse((directory / "retention-archive/logs.tar.gz").exists())
        self.assertTrue((directory / "retention-archive/core.tar.gz").exists())
        self.assertTrue((directory / "evidence/lifecycle-result.json").exists())

    def test_inventory_cannot_adopt_legacy_by_directory_date(self):
        prefix = self.host / "ancient" / "prefix"
        prefix.mkdir(parents=True)
        (prefix / "data").write_text("keep")
        report = gc.inventory(self.host)
        self.assertEqual(1, report["count"])
        self.assertFalse(report["legacyPrefixes"][0]["eligible"])
        self.assertTrue((prefix / "data").exists())

    def test_readonly_snapshot_includes_committed_wal_without_touching_live_files(self):
        writer = sqlite3.connect(self.store.database)
        self.addCleanup(writer.close)
        writer.execute("PRAGMA journal_mode=WAL")
        writer.execute("INSERT INTO events(job_id,kind,payload,created_at) VALUES(NULL,'wal-test','{}',?)", (self.now,))
        writer.commit()
        before = {p.name: (p.stat().st_size, p.stat().st_mtime_ns) for p in self.root.iterdir()}
        snap = gc.Snapshot(self.root)
        self.assertEqual(self.store.jobs(), snap.job_rows)
        self.assertEqual(before, {p.name: (p.stat().st_size, p.stat().st_mtime_ns) for p in self.root.iterdir()})
        with gc.copied_database(self.store.database) as copied:
            db = sqlite3.connect(copied)
            self.assertEqual(1, db.execute("SELECT count(*) FROM events WHERE kind='wal-test'").fetchone()[0])
            db.close()

    def test_new_payload_after_archive_is_not_deleted_by_later_batch(self):
        _, task, _ = self.finish()
        q.atomic_json(self.root / "retention-policy.json", {"writersMigrated": True, "maxItems": 1})
        self.assertFalse(self.apply()["errors"])
        (task / "saved-model.cmo3").write_bytes(b"new unique evidence")
        result = self.apply()
        self.assertTrue(result["errors"])
        self.assertEqual(b"new unique evidence", (task / "saved-model.cmo3").read_bytes())

    def test_same_filesystem_mount_boundary_is_rejected(self):
        _, task, _ = self.finish()
        original = Path.read_text
        def mountinfo(path, *args, **kwargs):
            if str(path) == "/proc/self/mountinfo":
                return f"1 2 0:1 / {task}/prefix rw - btrfs dev rw\n"
            return original(path, *args, **kwargs)
        with mock.patch.object(Path, "read_text", mountinfo):
            report = self.plan()
        self.assertFalse(any(i["kind"] == "prefix" for i in report["candidates"]))
        self.assertTrue((task / "prefix").exists())

    def test_worker_waits_for_collector_admission_lock_without_exiting(self):
        from test_host_validation_queue import worker_process
        job = self.store.submit(self.prepared, self.prepared, "wait-for-collector")
        context = multiprocessing.get_context("spawn")
        stop = context.Event()
        worker = context.Process(target=worker_process, args=(str(self.root), stop, 1))
        with q.FileLock(self.root / "admission.lock"):
            worker.start()
            time.sleep(0.25)
            self.assertTrue(worker.is_alive())
            self.assertEqual("queued", self.store.jobs(job["job_id"])[0]["state"])
        worker.join(5)
        if worker.is_alive():
            stop.set()
            q.wake(self.store)
            worker.join(5)
        self.assertEqual(0, worker.exitcode)
        self.assertEqual("failed", self.store.jobs(job["job_id"])[0]["state"])

    def test_low_space_prevents_worker_claim_and_host_launch(self):
        import threading
        from test_host_validation_queue import IsolatedBackend
        job = self.store.submit(self.prepared, self.prepared, "space-blocked")
        self.store.production = True  # Only this explicitly injected temporary store.
        stop = threading.Event()
        def no_space(*_):
            stop.set()
            raise q.QueueError("insufficient free space: test")
        with mock.patch.object(gc, "check_space", side_effect=no_space), mock.patch.object(IsolatedBackend, "run") as launch:
            q.Worker(self.store, IsolatedBackend()).serve(stop=stop)
            launch.assert_not_called()
        self.assertEqual("queued", self.store.jobs(job["job_id"])[0]["state"])

    def test_enabled_scheduled_collection_requires_migration_and_records_receipt(self):
        self.finish()
        q.atomic_json(self.root / "retention-policy.json", {"enabled": True, "writersMigrated": True})
        args = cli.build_parser(Path("unused")).parse_args(["gc", "run"])
        with mock.patch.object(q.RunnerBackend, "busy", return_value=[]), contextlib.redirect_stdout(io.StringIO()) as output:
            self.assertEqual(0, gc.main(args, root=self.root))
        self.assertTrue(json.loads(output.getvalue())["removed"])
        self.assertTrue(list((self.root / "retention-receipts").glob("*.json")))

    def test_service_examples_do_not_enable_or_launch_host(self):
        preview = Path(__file__).resolve().parents[1] / "preview"
        service = (preview / "turboism-host-validation-gc.service.example").read_text()
        timer = (preview / "turboism-host-validation-gc.timer.example").read_text()
        self.assertIn("host_validation.py gc run", service)
        self.assertNotIn("gc apply", service)
        self.assertIn("Persistent=true", timer)
        rules = json.loads((preview / "host-validation-retention-policy.example.json").read_text())
        self.assertFalse(rules["enabled"])
        self.assertFalse(rules["writersMigrated"])

    def test_result_logs_and_marker_only_evidence_are_retained_as_core(self):
        for options in ({"marker": True}, {"result_file": "logs/runtime.log"}):
            prepared = self.make_prepared(**options)
            _, task, directory = self.finish(prepared=prepared)
            (task / "turboism-home/state/result.log").write_text("structured assertion, not an ordinary log")
            self.assertFalse(self.apply()["errors"])
            with tarfile.open(directory / "retention-archive/core.tar.gz") as tar:
                self.assertIn("turboism-home/logs/runtime.log", tar.getnames())
                self.assertIn("turboism-home/state/result.log", tar.getnames())

    def test_missing_or_corrupt_core_archive_prevents_log_expiration(self):
        _, _, directory = self.finish(age=31)
        self.assertFalse(self.apply()["errors"])
        report = self.plan()
        core = directory / "retention-archive/core.tar.gz"
        core.write_bytes(b"corrupt archive")
        self.assertFalse(any(i["kind"] == "logs" for i in self.plan()["candidates"]))
        self.assertEqual([], self.apply(report)["removed"])
        self.assertTrue((directory / "retention-archive/logs.tar.gz").exists())
        self.assertTrue((directory / "runner.log").exists())

    def test_unchanged_fixture_and_deployment_are_not_repacked_indefinitely(self):
        job, task, directory = self.finish()
        fixture = task / (job["run_id"] + ".cmo3")
        fixture.write_bytes(b"unchanged source")
        final = gc.read_json(directory / "outcome.json")
        final["details"]["postContainmentChecks"]["fixtureCopySha256"] = q.file_digest(fixture)
        q.atomic_json(directory / "outcome.json", final)
        q.atomic_json(directory / "evidence/lifecycle-result.json", final)
        with self.store.transaction() as db:
            db.execute("UPDATE jobs SET evidence_json=? WHERE job_id=?", (q.canonical_json(final), job["job_id"]))
        (task / "turboism-agent.jar").write_bytes(b"disposable binary")
        (task / "turboism-home/config.json").write_bytes(b"private config")
        self.assertFalse(self.apply()["errors"])
        with tarfile.open(directory / "retention-archive/core.tar.gz") as tar:
            self.assertNotIn(fixture.name, tar.getnames())
            self.assertNotIn("turboism-agent.jar", tar.getnames())
            self.assertNotIn("turboism-home/config.json", tar.getnames())
            self.assertIn("saved-model.cmo3", tar.getnames())
        manifest = gc.read_json(directory / "retention-archive/manifest.json")
        self.assertIn(fixture.name, manifest["omitted"])


if __name__ == "__main__":
    unittest.main()
