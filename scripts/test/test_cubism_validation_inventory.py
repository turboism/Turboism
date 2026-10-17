"""Prevent incomplete version evidence from becoming a support claim."""

import hashlib
import importlib.util
import json
from pathlib import Path
import re
import tempfile
import unittest
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location(
    "inventory", Path(__file__).resolve().parents[1] / "dev/cubism_validation_inventory.py"
)
INVENTORY = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(INVENTORY)


class ValidationInventoryTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.install = self.root / "installed"
        artifact = self.install / "Editor/app/lib/Live2D_Cubism.jar"
        artifact.parent.mkdir(parents=True)
        artifact.write_bytes(b"editor")
        artifact.with_name("Live2DCubismCore.jar").write_bytes(b"core")
        self.hosts = {"5304": ("Editor", hashlib.sha256(b"editor").hexdigest())}
        self.write("compatibility/cubism/core-api/observed/cubism-core-5.3.04.json", json.dumps({
            "artifact": {"sha256": hashlib.sha256(b"core").hexdigest(), "sizeBytes": 4},
        }))
        self.write("compatibility/cubism/verification/core.json", json.dumps({
            "cubismVersion": "5.3.04", "artifact": {}, "capabilityIds": ["cubism.geometry.read"],
        }))
        self.write("sdk/src/main/java/dev/turboism/sdk/plugin/PluginService.java",
                   "enum PluginService {\n    WORKSPACE(dev.example.Workspace.class)\n}")
        self.write("scripts/preview/host-validation-tasks.json", json.dumps({"tasks": {
            "workspace": {"versions": ["5304"], "command": "scripts/preview/run-workspace-host-validation.sh"},
        }}))

    def write(self, relative, content):
        path = self.root / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content)

    def build(self):
        with patch.object(INVENTORY, "HOSTS", self.hosts):
            return INVENTORY.build_inventory(self.install, self.root)

    def test_static_record_and_declared_task_never_prove_runtime_support(self):
        result = self.build()
        self.assertFalse(result["complete"])
        self.assertEqual(["cubism.geometry.read"], result["versions"][0]["exactRecordCapabilities"])
        self.assertEqual([], result["versions"][0]["verifiedSupported"])
        self.assertEqual([], result["versions"][0]["verifiedUnsupported"])
        self.assertEqual("PENDING_AUDIT", result["scenarios"][0]["coverageMapping"])

    def test_unexpected_installation_cannot_be_silently_omitted(self):
        self.write("installed/New Editor/app/lib/Live2D_Cubism.jar", "different")
        with self.assertRaisesRegex(ValueError, "Uncatalogued"):
            self.build()

    def test_changed_installed_bytes_invalidate_the_inventory(self):
        self.write("installed/Editor/app/lib/Live2D_Cubism.jar", "replaced")
        with self.assertRaisesRegex(ValueError, "fingerprint changed"):
            self.build()

    def test_core_wrapper_pins_every_catalogued_editor_and_core(self):
        root = INVENTORY.ROOT
        wrapper = (root / "scripts/preview/run-core-acquisition-host-validation.sh").read_text()
        self.assertIn('turboism_require_task_version core-acquisition "$version"', wrapper)
        self.assertIn('turboism_core_acquisition_metadata "$version"', wrapper)

        tasks = json.loads((root / "scripts/preview/host-validation-tasks.json").read_text())
        task_versions = set(tasks["tasks"]["core-acquisition"]["versions"])
        self.assertEqual(set(INVENTORY.HOSTS), task_versions)

        for code in task_versions:
            version_semver = INVENTORY.semver(code)
            edit_rec = json.loads((root / f"compatibility/cubism/verification/cubism-{version_semver}-editor-model.json").read_text())
            core_rec = json.loads((root / f"compatibility/cubism/verification/cubism-{version_semver}-core-model-read.json").read_text())
            editor_sha = edit_rec["artifact"]["sha256"]
            core_sha = core_rec["artifact"]["sha256"]
            self.assertEqual(INVENTORY.HOSTS[code][1], editor_sha)
            observed = json.loads((root / (
                f"compatibility/cubism/core-api/observed/cubism-core-{version_semver}.json"
            )).read_text())
            self.assertEqual(observed["artifact"]["sha256"], core_sha)

    def test_changed_core_cannot_hide_behind_a_matching_editor(self):
        self.write("installed/Editor/app/lib/Live2DCubismCore.jar", "changed")
        with self.assertRaisesRegex(ValueError, "Core fingerprint changed"):
            self.build()


if __name__ == "__main__":
    unittest.main()
