#!/usr/bin/env python3
"""Fail-closed unit tests for check_host_validation_versions."""
from __future__ import annotations

import json
import tempfile
import unittest
from unittest.mock import patch
from pathlib import Path
from scripts.test.reviewed_releases_fixture import table

from scripts import check_reviewed_releases as gate


class CheckHostValidationVersionsTest(unittest.TestCase):
    def setUp(self):
        original = gate.render_records
        def records(root):
            if root == gate.r4_ROOT:
                return original(root)
            return {f"cubism-{version}-workspace.json": json.dumps({
                "status": "VERIFIED_STATIC", "cubismVersion": version, "capabilityIds": ["cubism.workspace.control"]}).encode()
                for version in ("5.2.03", "5.3.02")}
        self.patch = patch.object(gate, "render_records", side_effect=records)
        self.patch.start()
        self.addCleanup(self.patch.stop)

    def test_live_repository_passes(self) -> None:
        self.assertEqual([], gate.r4_check(gate.r4_ROOT))

    def test_manifest_version_drift_is_rejected_until_registered(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self._write_fixture(root)
            self.assertEqual([], gate.r4_check(root))

            manifest_file = root / gate.r4_MANIFEST_PATH
            manifest = json.loads(manifest_file.read_text(encoding="utf-8"))
            manifest["tasks"]["workspace"]["versions"] = ["5302"]
            manifest_file.write_text(json.dumps(manifest), encoding="utf-8")

            failures = gate.r4_check(root)
            self.assertTrue(
                any("task 'workspace' versions" in f for f in failures),
                failures,
            )

            self.assertEqual(0, gate.r4_update(root))
            self.assertEqual([], gate.r4_check(root))

    def test_unregistered_wrapper_version_literal_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self._write_fixture(root)
            wrapper = root / "scripts/preview/run-workspace-host-validation.sh"
            wrapper.write_text(
                '#!/usr/bin/env bash\ncase "$version" in 5203|5302) ;; esac\n',
                encoding="utf-8",
            )
            failures = gate.r4_check(root)
            self.assertTrue(
                any("unregistered wrapper version enumeration" in f for f in failures),
                failures,
            )

            reg_file = root / "scripts/reviewed-release-literal-exceptions.json"
            reg = json.loads(reg_file.read_text(encoding="utf-8"))
            reg["sites"].append(
                {
                    "kind": "file",
                    "path": "scripts/preview/run-workspace-host-validation.sh",
                    "versions": ["5.2.03", "5.3.02"],
                    "basis": "evidence",
                    "evidence": "documented bytecode boundary",
                }
            )
            reg_file.write_text(json.dumps(reg), encoding="utf-8")
            self.assertEqual([], gate.r4_check(root))

    @staticmethod
    def _write_fixture(root: Path) -> None:
        artifacts = root / "compatibility/cubism/reviewed-releases.json"
        artifacts.parent.mkdir(parents=True, exist_ok=True)
        artifacts.write_text(table(['5.2.03', '5.3.02']), encoding='utf-8')
        matrix = root / gate.r4_MATRIX_PATH
        matrix.parent.mkdir(parents=True, exist_ok=True)
        matrix.write_text(
            json.dumps(
                {
                    "reviewedVersions": ["5.2.03", "5.3.02"],
                    "capabilities": [
                        {
                            "id": "cubism.workspace.control",
                            "admitted": ["5.2.03", "5.3.02"],
                        }
                    ],
                    "capabilityGroups": [
                        {
                            "id": "workspace",
                            "capabilities": ["cubism.workspace.control"],
                        }
                    ],
                    "plugins": [],
                    "classPins": [],
                    "taskPluginAliases": {},
                    "taskGroupAliases": {"workspace": "workspace"},
                }
            ),
            encoding="utf-8",
        )
        manifest = root / gate.r4_MANIFEST_PATH
        manifest.parent.mkdir(parents=True, exist_ok=True)
        manifest.write_text(
            json.dumps(
                {
                    "format": "turboism.host-validation.tasks",
                    "schemaVersion": 1,
                    "tasks": {
                        "workspace": {
                            "command": "scripts/preview/run-workspace-host-validation.sh",
                            "versions": ["5203", "5302"],
                        }
                    },
                }
            ),
            encoding="utf-8",
        )
        wrapper = root / "scripts/preview/run-workspace-host-validation.sh"
        wrapper.write_text(
            '#!/usr/bin/env bash\nturboism_require_task_version workspace "$1"\n',
            encoding="utf-8",
        )
        reg = root / "scripts/reviewed-release-literal-exceptions.json"
        reg.parent.mkdir(parents=True, exist_ok=True)
        reg.write_text(
            json.dumps(
                {
                    "format": "turboism.reviewed-release-literal-exceptions/v1",
                    "sites": [],
                }
            ),
            encoding="utf-8",
        )


if __name__ == "__main__":
    unittest.main()
