#!/usr/bin/env python3
"""Offline contract tests for the exact-host resource scheduler."""

from __future__ import annotations

import contextlib
import io
import json
from pathlib import Path
import sqlite3
import sys
import tempfile
import unittest
from unittest import mock

ROOT = Path(__file__).resolve().parents[2]
PREVIEW = ROOT / "scripts" / "preview"
sys.path.insert(0, str(PREVIEW))

import host_validation as scheduler  # noqa: E402
queue = scheduler.queue


class HostValidationSchedulerTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.manifest_path = PREVIEW / "host-validation-tasks.json"
        cls.manifest = scheduler.load_manifest(cls.manifest_path)

    def request(self, spec: str) -> scheduler.Request:
        return scheduler.parse_request(spec, "test-run", self.manifest)

    def test_manifest_covers_supported_wrappers_and_resource_boundaries(self) -> None:
        expected = {
            "animation-timeline", "atlas", "backup", "backup-interactive", "backup-webdav", "boundingbox-warp-mirror", "clipmask-viewer", "core-acquisition",
            "dialog-automation", "edit", "edit-protocol", "fps", "host-locale", "incremental-update", "mcp", "model-update-skip", "parameter",
            "parameter-batch-transfer", "protected-export", "psd-clip-mask",
            "recent-preview", "selection-brush", "selection-lag", "separate-save-path",
            "startup-suppression", "status-bar", "theme", "update-check", "warp-deformer-alt-symmetry", "workspace",
        }
        self.assertEqual(expected, set(self.manifest.tasks))
        self.assertEqual(1, self.manifest.resources["host-slot"].capacity)
        self.assertEqual(
            {"host-slot": 1, "display-input": 1, "performance-host": 1},
            self.manifest.tasks["fps"].resources,
        )
        self.assertEqual(
            {"host-slot": 1, "display-input": 1, "interactive-desktop": 1},
            self.manifest.tasks["backup-interactive"].resources,
        )
        self.assertFalse(self.manifest.tasks["dialog-automation"].runnable)
        self.assertFalse(self.manifest.tasks["backup-interactive"].runnable)

    def test_mcp_reserves_exact_host_and_display_for_native_close(self) -> None:
        task = self.manifest.tasks["mcp"]
        self.assertEqual(("5203", "5302", "5303"), task.versions)
        self.assertEqual({"host-slot": 1, "display-input": 1}, task.resources)
        with mock.patch("subprocess.run", side_effect=AssertionError("Plan must not start the host")):
            for version in task.versions:
                command = scheduler.render_command(self.request("mcp:" + version), self.manifest)
                self.assertTrue(command[1].endswith("run-mcp-host-validation.sh"))
                self.assertEqual([version, "test-run"], command[2:])
        with self.assertRaises(scheduler.SchedulerError):
            self.request("mcp:5400")

    def test_atlas_has_fixed_preliminary_cases_and_exact_version(self) -> None:
        task = self.manifest.tasks["atlas"]
        self.assertEqual({"host-slot": 1, "display-input": 1, "performance-host": 1}, task.resources)
        self.assertEqual({f"geometry-{count}-{implementation}" for count in (100, 500, 1000, 2500)
                          for implementation in ("native", "new")} |
                         {f"ui-{dataset}-{count}-{implementation}" for dataset in ("circle", "geometry")
                          for count in (100, 500, 1000, 2500)
                          for implementation in ("native", "new")} |
                         {f"ui-{dataset}-{count}-polygon" for dataset in ("circle", "geometry")
                          for count in (100, 500, 1000)} |
                         {f"ui-{dataset}-{count}-new-parallel" for dataset in ("circle", "geometry")
                          for count in (100, 500)}, set(task.variants))
        self.assertEqual("geometry-100-new", self.request("atlas:5303").variant)
        for spec in ("atlas:9999", "atlas:5303@geometry-2500-both",
                     "atlas:5303@geometry-2499-new", "atlas:5303@geometry-100-both",
                     "atlas:5303@ui-geometry-2499-native", "atlas:5303@ui-circle-101-new",
                     "atlas:5303@ui-circle-1000-new-parallel", "atlas:5303@ui-geometry-500-native-parallel",
                     "atlas:5303@ui-circle-2500-polygon", "atlas:5303@geometry-100-polygon"):
            with self.subTest(spec=spec), self.assertRaises(scheduler.SchedulerError):
                self.request(spec)
        for spec in ("atlas:5203@ui-circle-100-polygon", "atlas:5302@ui-geometry-1000-polygon"):
            with self.subTest(spec=spec):
                self.assertEqual(spec.split("@", 1)[1], self.request(spec).variant)
        with mock.patch("subprocess.run", side_effect=AssertionError("Plan must not execute wrappers")):
            command = scheduler.render_command(self.request("atlas:5303"), self.manifest)
            self.assertTrue(command[1].endswith("run-atlas-host-validation.sh"))
            self.assertEqual(["5303", "test-run", "geometry-100-new"], command[2:])

    def test_local_env_loads_data_without_overriding_exported_values(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            env_file = Path(directory) / ".env"
            env_file.write_text(
                "# local-only\n"
                "TURBOISM_HOST_VALIDATION_SSH_HOST=env@example.invalid\n"
                "TURBOISM_HOST_VALIDATION_FIXTURE_5302='/remote/fixture with spaces.cmo3'\n",
                encoding="utf-8",
            )
            host_key = "TURBOISM_HOST_VALIDATION_SSH_HOST"
            fixture_key = "TURBOISM_HOST_VALIDATION_FIXTURE_5302"
            values = scheduler.parse_local_env(env_file)
            environment = {host_key: "exported@example.invalid"}
            for name, value in values.items():
                environment.setdefault(name, value)
            self.assertEqual("exported@example.invalid", environment[host_key])
            self.assertEqual("/remote/fixture with spaces.cmo3", environment[fixture_key])

    def test_parser_selects_default_and_explicit_variants(self) -> None:
        default = self.request("parameter:5302")
        explicit = self.request("host-locale:5203@ja")
        self.assertEqual("matrix", default.variant)
        self.assertEqual("ja", explicit.variant)
        with self.assertRaisesRegex(scheduler.SchedulerError, "does not support version"):
            scheduler.parse_request("workspace:9999", "test-run", self.manifest)
        with self.assertRaisesRegex(scheduler.SchedulerError, "does not define variants"):
            scheduler.parse_request("workspace:5302@matrix", "test-run", self.manifest)

    def test_planner_serializes_all_versions_in_fifo_order(self) -> None:
        requests = [
            self.request("workspace:5302"),
            self.request("recent-preview:5203"),
            self.request("status-bar:5302"),
            self.request("fps:5302"),
        ]
        waves = scheduler.plan_waves(requests, self.manifest.resources)
        self.assertEqual(
            [["workspace"], ["recent-preview"], ["status-bar"], ["fps"]],
            [[request.task.name for request in wave] for wave in waves],
        )

    def test_rendered_command_reuses_wrapper_and_common_placement_options(self) -> None:
        request = self.request("psd-clip-mask:5203@read")
        command = scheduler.render_command(request, self.manifest)
        self.assertEqual("bash", command[0])
        self.assertTrue(command[1].endswith("run-psd-clip-mask-host-validation.sh"))
        self.assertEqual(["5203", "read", "test-run"], command[2:5])
        self.assertNotIn("--ssh-host", command)
        self.assertNotIn("--ssh-key", command)

    def test_cli_list_and_plan_do_not_require_host_access(self) -> None:
        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            self.assertEqual(0, scheduler.main(["list"]))
        self.assertIn("workspace\t5203,5302", output.getvalue())

        output = io.StringIO()
        with contextlib.redirect_stdout(output), mock.patch.object(scheduler.queue, "Store", side_effect=AssertionError("plan touched queue")), mock.patch.object(scheduler.subprocess, "run", side_effect=AssertionError("plan executed a process")):
            self.assertEqual(0, scheduler.main([
                "plan", "workspace:5302", "fps:5203", "--run-label", "offline",
            ]))
        rendered = output.getvalue()
        self.assertIn("local-only FIFO", rendered)
        self.assertIn("1. workspace:5302", rendered)
        self.assertIn("2. fps:5203", rendered)
        self.assertIn("workspace:5302", rendered)
        self.assertIn("fps:5203", rendered)

        errors = io.StringIO()
        with contextlib.redirect_stderr(errors):
            self.assertEqual(2, scheduler.main(["run", "dialog-automation:5302"]))
        self.assertIn("cannot run blocked tasks", errors.getvalue())

    def test_manifest_rejects_unknown_resources_and_unsafe_commands(self) -> None:
        source = json.loads(self.manifest_path.read_text(encoding="utf-8"))
        with tempfile.TemporaryDirectory(dir=PREVIEW) as directory:
            invalid = Path(directory) / "manifest.json"
            source["tasks"]["workspace"]["resources"]["missing"] = 1
            invalid.write_text(json.dumps(source), encoding="utf-8")
            with self.assertRaisesRegex(scheduler.SchedulerError, "unknown resource"):
                scheduler.load_manifest(invalid)

    def test_local_admission_is_atomic_and_owner_scoped(self) -> None:
        # Preserve the old lease test's exclusivity/owner guarantees, without SSH stubs.
        with tempfile.TemporaryDirectory() as directory:
            store = scheduler.queue.Store(Path(directory) / "queue")
            path = store.root / "admission.lock"
            owner = scheduler.queue.FileLock(path).acquire()
            other = scheduler.queue.FileLock(path)
            with self.assertRaises(scheduler.queue.QueueError):
                other.acquire()
            other.close()  # A non-owner cannot release the owner's lock.
            with self.assertRaises(scheduler.queue.QueueError):
                scheduler.queue.FileLock(path).acquire()
            owner.close()
            with scheduler.queue.FileLock(path):
                pass
            store.submit("prepared", "digest", "one")
            job = store.claim()
            store.quarantine(job["job_id"], "cleanup unknown")
            with contextlib.redirect_stderr(io.StringIO()):
                self.assertEqual(2, scheduler.main(["release-stale", "--older-than", "3600", "--force"]))
            self.assertIsNone(store.claim())

    def test_attempt_ids_include_entropy(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            store = scheduler.queue.Store(Path(directory) / "queue")
            first = store.submit("prepared", "digest", "first")
            second = store.submit("prepared", "digest", "second")
            self.assertNotEqual(first["job_id"], second["job_id"])
            self.assertRegex(first["job_id"], scheduler.SAFE_NAME)
            self.assertRegex(second["job_id"], scheduler.SAFE_NAME)

    def test_legacy_remote_configuration_cannot_trigger_connections(self) -> None:
        with mock.patch.object(scheduler.subprocess, "run", side_effect=AssertionError("network execution")):
            for arguments in (["plan", "workspace:5302", "--ssh-host", "remote"],
                              ["status", "--ssh-key=/secret"], ["run", "workspace:5302", "--keep-going"]):
                with contextlib.redirect_stderr(io.StringIO()):
                    self.assertEqual(2, scheduler.main(arguments))

    def test_recovery_confirmation_requires_reason(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            store = scheduler.queue.Store(Path(directory) / "queue")
            with mock.patch.object(scheduler.queue, "Store", return_value=store), \
                 mock.patch.object(scheduler.queue, "recover", side_effect=AssertionError("invalid recovery invoked")), \
                 contextlib.redirect_stderr(io.StringIO()):
                for arguments in (["recover", "--confirm", "job"],
                                  ["recover", "--confirm", "job", "--reason", " "],
                                  ["recover", "--inspect", "job", "--reason", "reviewed"]):
                    self.assertEqual(2, scheduler.main(arguments))


class _WaitStore:
    def __init__(self, database: Path) -> None:
        self.database = database
        self.submit_calls = 0

    def submit(self, *arguments, **kwargs):
        self.submit_calls += 1
        raise AssertionError("waiter must never submit a job")


class HostValidationWaitTest(unittest.TestCase):
    @staticmethod
    def coded_error(message: str, code: int) -> sqlite3.OperationalError:
        failure = sqlite3.OperationalError(message)
        failure.sqlite_errorcode = code
        return failure

    @staticmethod
    def terminal_database(directory: str, state: str) -> tuple[queue.Store, dict]:
        store = queue.Store(Path(directory) / "queue")
        job = store.submit("prepared", "digest", "wait-test")
        with store.transaction() as db:
            db.execute("UPDATE jobs SET state=? WHERE job_id=?", (state, job["job_id"]))
        return store, job

    def wait_after_one_lock(self, state: str) -> tuple[int, dict, int, str]:
        with tempfile.TemporaryDirectory() as directory:
            store, job = self.terminal_database(directory, state)
            waiter = _WaitStore(store.database)
            connection_calls = []
            real_read_job = scheduler.queue.read_only_job

            def connect(database, **kwargs):
                connection_calls.append((database, kwargs))
                if len(connection_calls) == 1:
                    raise self.coded_error("locking protocol", sqlite3.SQLITE_PROTOCOL)
                return sqlite3.connect(database, **kwargs)

            def read_job(database, job_id):
                return real_read_job(database, job_id, connect=connect)

            output = io.StringIO()
            with contextlib.redirect_stdout(output), \
                 mock.patch.object(scheduler.queue, "read_only_job", side_effect=read_job), \
                 mock.patch.object(scheduler.time, "sleep") as sleep:
                result = scheduler.wait_job(waiter, job["job_id"], timeout=10)
            record = json.loads(output.getvalue())
            return result, record["job"], len(connection_calls), sleep.call_count

    def test_lock_retry_returns_same_job_success(self) -> None:
        result, job, connections, sleeps = self.wait_after_one_lock("succeeded")
        self.assertEqual(0, result)
        self.assertEqual("succeeded", job["state"])
        self.assertEqual(2, connections)
        self.assertEqual(1, sleeps)

    def test_lock_retry_returns_same_job_failed(self) -> None:
        result, job, connections, sleeps = self.wait_after_one_lock("failed")
        self.assertEqual(1, result)
        self.assertEqual("failed", job["state"])
        self.assertEqual(2, connections)
        self.assertEqual(1, sleeps)

    def test_lock_retry_is_bounded_and_never_submits(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            database = Path(directory) / "queue.sqlite3"
            database.write_bytes(b"test database placeholder")
            waiter = _WaitStore(database)
            connection_calls = []
            real_read_job = scheduler.queue.read_only_job

            def connect(database_uri, **kwargs):
                connection_calls.append(database_uri)
                raise self.coded_error("database is locked", sqlite3.SQLITE_BUSY)

            def read_job(database_path, job_id):
                return real_read_job(database_path, job_id, connect=connect)

            with mock.patch.object(scheduler.queue, "read_only_job", side_effect=read_job), \
                 mock.patch.object(scheduler.time, "sleep") as sleep:
                with self.assertRaisesRegex(
                    scheduler.SchedulerError,
                    r"infrastructure wait failure.*retry limit",
                ):
                    scheduler.wait_job(waiter, "job", timeout=10)
            self.assertEqual(scheduler.WAIT_JOB_READ_RETRY_LIMIT + 1, len(connection_calls))
            self.assertEqual(scheduler.WAIT_JOB_READ_RETRY_LIMIT, sleep.call_count)
            self.assertEqual(0, waiter.submit_calls)

    def test_lock_retry_stops_at_wait_deadline(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            database = Path(directory) / "queue.sqlite3"
            database.write_bytes(b"test database placeholder")
            waiter = _WaitStore(database)
            now = [100.0]
            connection_calls = []
            real_read_job = scheduler.queue.read_only_job

            def connect(database_uri, **kwargs):
                connection_calls.append(database_uri)
                raise self.coded_error("locking protocol", sqlite3.SQLITE_PROTOCOL)

            def read_job(database_path, job_id):
                return real_read_job(database_path, job_id, connect=connect)

            def clock():
                return now[0]

            def advance(seconds):
                now[0] += seconds

            with mock.patch.object(scheduler.queue, "read_only_job", side_effect=read_job), \
                 mock.patch.object(scheduler.time, "monotonic", side_effect=clock), \
                 mock.patch.object(scheduler.time, "sleep", side_effect=advance) as sleep:
                with self.assertRaisesRegex(
                    scheduler.SchedulerError,
                    r"infrastructure wait failure.*deadline",
                ):
                    scheduler.wait_job(waiter, "job", timeout=0.06)
            self.assertEqual(2, len(connection_calls))
            self.assertEqual(2, sleep.call_count)
            self.assertEqual(0, waiter.submit_calls)

    def test_lock_retry_budget_spans_successful_polls(self) -> None:
        waiter = _WaitStore(Path("/unused/queue.sqlite3"))
        lock = self.coded_error("database is locked", sqlite3.SQLITE_BUSY)
        running = {"state": "running"}
        responses = [lock, running, lock, running, lock, running, lock]
        calls = []

        def read_job(database, job_id):
            calls.append((database, job_id))
            response = responses.pop(0)
            if isinstance(response, BaseException):
                raise response
            return response

        with mock.patch.object(scheduler.queue, "read_only_job", side_effect=read_job), \
             mock.patch.object(scheduler.time, "sleep") as sleep:
            with self.assertRaisesRegex(
                scheduler.SchedulerError,
                r"infrastructure wait failure.*retry limit",
            ):
                scheduler.wait_job(waiter, "job", timeout=10)
        self.assertEqual(7, len(calls))
        self.assertEqual(6, sleep.call_count)
        self.assertEqual([], responses)
        self.assertEqual(0, waiter.submit_calls)

    def test_nonterminal_poll_does_not_sleep_past_wait_deadline(self) -> None:
        waiter = _WaitStore(Path("/unused/queue.sqlite3"))
        now = [100.0]

        def clock():
            return now[0]

        def advance(seconds):
            now[0] += seconds

        with mock.patch.object(scheduler.queue, "read_only_job", return_value={"state": "running"}), \
             mock.patch.object(scheduler.time, "monotonic", side_effect=clock), \
             mock.patch.object(scheduler.time, "sleep", side_effect=advance) as sleep:
            output = io.StringIO()
            with contextlib.redirect_stdout(output):
                result = scheduler.wait_job(waiter, "job", timeout=0.06)
        self.assertEqual(3, result)
        self.assertEqual(1, sleep.call_count)
        self.assertAlmostEqual(0.06, sleep.call_args.args[0])
        self.assertLessEqual(sleep.call_args.args[0], 0.06 + 1e-12)
        self.assertTrue(json.loads(output.getvalue())["waitTimedOut"])
        self.assertEqual(0, waiter.submit_calls)

    def test_non_lock_sqlite_error_fails_immediately(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            database = Path(directory) / "queue.sqlite3"
            database.write_bytes(b"test database placeholder")
            waiter = _WaitStore(database)
            calls = []

            def read_job(database_path, job_id):
                calls.append((database_path, job_id))
                raise self.coded_error("locking protocol", sqlite3.SQLITE_ERROR)

            with mock.patch.object(scheduler.queue, "read_only_job", side_effect=read_job), \
                 mock.patch.object(scheduler.time, "sleep") as sleep:
                with self.assertRaisesRegex(scheduler.SchedulerError, r"queue wait failure"):
                    scheduler.wait_job(waiter, "job", timeout=10)
            self.assertEqual(1, len(calls))
            sleep.assert_not_called()
            self.assertEqual(0, waiter.submit_calls)

    def test_lock_classifier_uses_exact_message_only_without_error_code(self) -> None:
        self.assertTrue(queue.is_retryable_read_error(sqlite3.OperationalError("locking protocol")))
        self.assertTrue(queue.is_retryable_read_error(sqlite3.OperationalError("DATABASE IS LOCKED")))
        self.assertFalse(queue.is_retryable_read_error(sqlite3.OperationalError("locking protocol: extra")))
        self.assertFalse(
            queue.is_retryable_read_error(
                self.coded_error("database is locked", sqlite3.SQLITE_ERROR)
            )
        )

    def test_unknown_schema_fails_without_a_wait_retry(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            store, job = self.terminal_database(directory, "failed")
            with store.transaction() as db:
                db.execute("UPDATE metadata SET version=99")
            waiter = _WaitStore(store.database)

            with mock.patch.object(scheduler.time, "sleep") as sleep:
                with self.assertRaisesRegex(
                    scheduler.queue.QueueError,
                    r"unsupported queue schema",
                ):
                    scheduler.wait_job(waiter, job["job_id"], timeout=10)
            sleep.assert_not_called()
            self.assertEqual(0, waiter.submit_calls)

    def test_wait_command_does_not_initialize_a_missing_database(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            database = root / "queue.sqlite3"
            with mock.patch.object(scheduler.queue, "account_root", return_value=root), \
                 mock.patch.object(scheduler.queue, "Store", side_effect=AssertionError("wait initialized Store")), \
                 contextlib.redirect_stderr(io.StringIO()):
                self.assertEqual(2, scheduler.main(["wait", "missing-job"]))
            self.assertFalse(database.exists())

    def test_read_only_job_uses_ro_query_only_and_rejects_write(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            store, job = self.terminal_database(directory, "failed")
            calls = []
            real_connect = sqlite3.connect

            def connect(database_uri, **kwargs):
                calls.append((database_uri, kwargs))
                return real_connect(database_uri, **kwargs)

            self.assertEqual(
                job["job_id"],
                scheduler.queue.read_only_job(store.database, job["job_id"], connect=connect)["job_id"],
            )
            self.assertEqual(1, len(calls))
            self.assertIn("?mode=ro", calls[0][0])
            self.assertTrue(calls[0][1]["uri"])
            with scheduler.queue.read_only_connection(store.database) as database:
                self.assertEqual(1, database.execute("PRAGMA query_only").fetchone()[0])
                with self.assertRaisesRegex(sqlite3.OperationalError, r"readonly"):
                    database.execute("CREATE TABLE forbidden_write(value TEXT)")

            waiter = _WaitStore(store.database)
            with mock.patch.object(scheduler.time, "sleep") as sleep:
                with self.assertRaisesRegex(queue.QueueError, r"unknown job id"):
                    scheduler.wait_job(waiter, "missing-job", timeout=10)
            sleep.assert_not_called()
            self.assertEqual(0, waiter.submit_calls)

    def test_read_only_connection_closes_when_pragma_fails(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "queue.sqlite3"
            path.write_bytes(b"placeholder")

            class FailingConnection:
                def __init__(self):
                    self.closed = False

                def execute(self, statement):
                    raise sqlite3.OperationalError("query_only setup failed")

                def close(self):
                    self.closed = True

            connection = FailingConnection()

            with self.assertRaisesRegex(sqlite3.OperationalError, r"query_only setup failed"):
                with scheduler.queue.read_only_connection(path, connect=lambda *a, **k: connection):
                    self.fail("failing PRAGMA unexpectedly yielded a connection")
            self.assertTrue(connection.closed)

    def test_corrupt_read_only_database_fails_without_retry(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            database = Path(directory) / "queue.sqlite3"
            database.write_bytes(b"not a sqlite database")
            waiter = _WaitStore(database)

            with mock.patch.object(scheduler.time, "sleep") as sleep:
                with self.assertRaisesRegex(
                    scheduler.SchedulerError,
                    r"queue wait failure",
                ):
                    scheduler.wait_job(waiter, "job", timeout=10)
            sleep.assert_not_called()
            self.assertEqual(0, waiter.submit_calls)

    def test_missing_read_only_database_is_not_created(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "missing" / "queue.sqlite3"
            calls = []

            def connect(*arguments, **kwargs):
                calls.append((arguments, kwargs))
                raise AssertionError("missing database must be rejected before connect")

            with self.assertRaisesRegex(scheduler.queue.QueueError, r"does not exist"):
                scheduler.queue.read_only_job(path, "job", connect=connect)
            self.assertFalse(path.exists())
            self.assertEqual([], calls)


if __name__ == "__main__":
    unittest.main()
