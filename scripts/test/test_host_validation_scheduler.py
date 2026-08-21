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
            "parameter-batch-transfer", "psd-clip-mask", "psd-pass-through",
            "recent-preview", "selection-lag", "separate-save-path",
            "startup-suppression", "status-bar", "theme", "workspace",
        }
        self.assertEqual(expected, set(self.manifest.tasks))
        self.assertEqual(4, self.manifest.resources["host-slot"].capacity)
        self.assertEqual(
            {"host-slot": 4, "display-input": 1, "performance-host": 1},
            self.manifest.tasks["fps"].resources,
        )
        self.assertEqual(
            {"host-slot": 1, "display-input": 1, "interactive-desktop": 1},
            self.manifest.tasks["backup-interactive"].resources,
        )
        self.assertFalse(self.manifest.tasks["dialog-automation"].runnable)

    def test_parser_selects_default_and_explicit_variants(self) -> None:
        default = self.request("parameter:5302")
        explicit = self.request("host-locale:5203@ja")
        self.assertEqual("matrix", default.variant)
        self.assertEqual("ja", explicit.variant)
        with self.assertRaisesRegex(scheduler.SchedulerError, "does not support version"):
            scheduler.parse_request("workspace:9999", "test-run", self.manifest)
        with self.assertRaisesRegex(scheduler.SchedulerError, "does not define variants"):
            scheduler.parse_request("workspace:5302@matrix", "test-run", self.manifest)

    def test_planner_parallelizes_isolated_projects_and_serializes_performance(self) -> None:
        requests = [
            self.request("workspace:5302"),
            self.request("recent-preview:5203"),
            self.request("status-bar:5302"),
            self.request("fps:5302"),
        ]
        waves = scheduler.plan_waves(requests, self.manifest.resources)
        self.assertEqual(
            [["workspace", "recent-preview", "status-bar"], ["fps"]],
            [[request.task.name for request in wave] for wave in waves],
        )

    def test_rendered_command_reuses_wrapper_and_common_placement_options(self) -> None:
        request = self.request("psd-clip-mask:5203@read")
        command = scheduler.render_command(
            request, self.manifest, "operator@example", Path("/tmp/scheduler-key")
        )
        self.assertEqual("bash", command[0])
        self.assertTrue(command[1].endswith("run-psd-clip-mask-host-validation.sh"))
        self.assertEqual(["5203", "read", "test-run"], command[2:5])
        self.assertEqual(
            ["--ssh-host", "operator@example", "--ssh-key", "/tmp/scheduler-key"],
            command[-4:],
        )

    def test_cli_list_and_plan_do_not_require_host_access(self) -> None:
        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            self.assertEqual(0, scheduler.main(["list"]))
        self.assertIn("workspace\t5203,5302", output.getvalue())

        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            self.assertEqual(0, scheduler.main([
                "plan", "workspace:5302", "fps:5203", "--run-label", "offline",
            ]))
        rendered = output.getvalue()
        self.assertIn("wave 1:", rendered)
        self.assertIn("wave 2:", rendered)
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

    def test_remote_lease_acquisition_is_atomic_and_owner_scoped(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            temporary = Path(directory)
            key = temporary / "key"
            key.write_text("test", encoding="utf-8")
            bin_dir = temporary / "bin"
            bin_dir.mkdir()
            ssh = bin_dir / "ssh"
            ssh.write_text(
                "#!/usr/bin/env bash\nset -euo pipefail\nremote=${!#}\nexec bash -c \"$remote\"\n",
                encoding="utf-8",
            )
            ssh.chmod(0o755)
            old_path = os.environ.get("PATH", "")
            os.environ["PATH"] = f"{bin_dir}:{old_path}"
            try:
                resources = {"exclusive": scheduler.Resource("exclusive", 1, "test")}
                leases = scheduler.RemoteLeases(
                    "local-test", key, str(temporary / "scheduler"), resources
                )
                task = scheduler.Task(
                    name="lease-test", description="test", command="scripts/preview/run-theme-host-validation.sh",
                    versions=("5302",), resources={"exclusive": 1}, arguments=("{version}", "{runLabel}"),
                    variants={}, default_variant=None, runnable=True, blocked_reason=None,
                )
                request = scheduler.Request(task, "5302", None, "test")
                first = leases.acquire(request, "owner-one", wait_seconds=1, poll_seconds=1)
                self.assertEqual(1, len(first.slots))
                with self.assertRaisesRegex(scheduler.SchedulerError, "timed out"):
                    leases.acquire(request, "owner-two", wait_seconds=1, poll_seconds=1)
                rows = leases.status()
                self.assertEqual("owner-one", rows[0]["owner"])
                leases.release_owner("owner-two")
                self.assertEqual(1, len(leases.status()))
                leases.release_owner("owner-one")
                self.assertEqual([], leases.status())

                stale = leases.acquire(request, "stale-owner", wait_seconds=1, poll_seconds=1)
                heartbeat = Path(stale.slots[0]) / "heartbeatEpoch"
                heartbeat.write_text(str(int(time.time()) - 1000) + "\n", encoding="utf-8")
                released = leases.release_stale(older_than=300)
                self.assertEqual(1, len(released))
                self.assertEqual([], leases.status())

                live = leases.acquire(request, "live-owner", wait_seconds=1, poll_seconds=1)
                live_heartbeat = Path(live.slots[0]) / "heartbeatEpoch"
                live_heartbeat.write_text(str(int(time.time()) - 1000) + "\n", encoding="utf-8")
                operation_lock = Path(live.slots[0]) / ".scheduler-operation"
                operation_lock.mkdir()
                self.assertEqual([], leases.release_stale(older_than=300))
                self.assertEqual("live-owner", leases.status()[0]["owner"])
                operation_lock.rmdir()
                leases.heartbeat("live-owner")
                self.assertEqual([], leases.release_stale(older_than=300))
                leases.release_owner("live-owner")

                missing_root = scheduler.RemoteLeases(
                    "local-test", key, str(temporary / "missing-scheduler"), resources
                )
                self.assertEqual([], missing_root.status())
                self.assertEqual([], missing_root.release_stale(older_than=300))
            finally:
                os.environ["PATH"] = old_path

    def test_owner_ids_include_random_dispatcher_entropy(self) -> None:
        request = self.request("workspace:5302")
        first = scheduler.owner_id(request, 1)
        second = scheduler.owner_id(request, 1)
        self.assertNotEqual(first, second)
        self.assertRegex(first, scheduler.SAFE_NAME)
        self.assertRegex(second, scheduler.SAFE_NAME)


if __name__ == "__main__":
    unittest.main()
