#!/usr/bin/env python3
"""Fail-closed unit tests for check_host_validation_versions."""
from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path
from scripts.test.reviewed_releases_fixture import table

from scripts import check_host_validation_versions as check_gate


class CheckHostValidationVersionsTest(unittest.TestCase):
    def test_live_repository_passes(self) -> None:
        self.assertEqual([], check_gate.check(check_gate.ROOT))

    def test_manifest_version_drift_is_rejected_until_registered(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self._write_fixture(root)
            self.assertEqual([], check_gate.check(root))

            manifest_file = root / check_gate.MANIFEST_PATH
            manifest = json.loads(manifest_file.read_text(encoding="utf-8"))
            manifest["tasks"]["workspace"]["versions"] = ["5302"]
            manifest_file.write_text(json.dumps(manifest), encoding="utf-8")

            failures = check_gate.check(root)
            self.assertTrue(
                any("task 'workspace' versions" in f for f in failures),
                failures,
            )

            self.assertEqual(0, check_gate.update(root))
            self.assertEqual([], check_gate.check(root))

    def test_unregistered_wrapper_version_literal_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self._write_fixture(root)
            wrapper = root / "scripts/preview/run-workspace-host-validation.sh"
            wrapper.write_text(
                '#!/usr/bin/env bash\ncase "$version" in 5203|5302) ;; esac\n',
                encoding="utf-8",
            )
            failures = check_gate.check(root)
            self.assertTrue(
                any("unregistered wrapper version enumeration" in f for f in failures),
                failures,
            )

            reg_file = root / "scripts/version-set-registry.json"
            reg = json.loads(reg_file.read_text(encoding="utf-8"))
            reg["sites"].append(
                {
                    "kind": "file",
                    "path": "scripts/preview/run-workspace-host-validation.sh",
                    "versions": ["5.2.03", "5.3.02"],
                    "basis": "evidence",
                    "reason": "documented bytecode boundary",
                }
            )
            reg_file.write_text(json.dumps(reg), encoding="utf-8")
            self.assertEqual([], check_gate.check(root))

    @staticmethod
    def _write_fixture(root: Path) -> None:
        artifacts = root / "compatibility/cubism/reviewed-releases.json"
        artifacts.parent.mkdir(parents=True, exist_ok=True)
        artifacts.write_text(table(['5.2.03', '5.3.02']), encoding='utf-8')
        matrix = root / check_gate.MATRIX_PATH
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
        manifest = root / check_gate.MANIFEST_PATH
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
        reg = root / "scripts/version-set-registry.json"
        reg.parent.mkdir(parents=True, exist_ok=True)
        reg.write_text(
            json.dumps(
                {
                    "format": "turboism.version-set-registry/v1",
                    "sites": [],
                }
            ),
            encoding="utf-8",
        )


if __name__ == "__main__":
    unittest.main()
