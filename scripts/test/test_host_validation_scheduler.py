#!/usr/bin/env python3
"""Offline contract tests for the exact-host resource scheduler."""

from __future__ import annotations

import contextlib
import io
import json
import os
from pathlib import Path
import sys
import tempfile
import time
import unittest
from unittest import mock

ROOT = Path(__file__).resolve().parents[2]
PREVIEW = ROOT / "scripts" / "preview"
sys.path.insert(0, str(PREVIEW))

import host_validation as scheduler  # noqa: E402


class HostValidationSchedulerTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.manifest_path = PREVIEW / "host-validation-tasks.json"
        cls.manifest = scheduler.load_manifest(cls.manifest_path)

    def request(self, spec: str) -> scheduler.Request:
        return scheduler.parse_request(spec, "test-run", self.manifest)

    def test_manifest_covers_supported_wrappers_and_resource_boundaries(self) -> None:
        expected = {
            "backup", "backup-interactive", "clipmask-viewer", "core-acquisition",
            "dialog-automation", "fps", "host-locale", "parameter",
            "parameter-batch-transfer", "psd-clip-mask",
            "recent-preview", "selection-lag", "separate-save-path",
            "startup-suppression", "status-bar", "theme", "workspace",
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


if __name__ == "__main__":
    unittest.main()
