"""API floors belong to plugins, not to the current framework build."""
import contextlib
import importlib.util
import io
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts"))
spec = importlib.util.spec_from_file_location("sync_plugin_api", ROOT / "scripts/sync_plugin_api.py")
SYNC = importlib.util.module_from_spec(spec)
spec.loader.exec_module(SYNC)


class PluginApiSyncTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        (self.root / "gradle").mkdir()
        (self.root / "gradle/common-java.gradle.kts").write_text(
            'rootProject.extra["turboismFrameworkVersion"] = "0.67.1"\n')
        self.module = self.root / "plugins/toy"
        self.descriptor = self.module / "src/main/resources/META-INF/turboism/plugin.json"
        self.descriptor.parent.mkdir(parents=True)
        self.descriptor.write_text(json.dumps({"turboismApi": "[0.67.0,1.0.0)"}))
        self.readme = self.module / "README.md"
        self.readme.write_text('turboismApi: "[0.67.0,1.0.0)"\n- SDK API: `[0.67.0,1.0.0)`.\n')

    def run_sync(self, write=False):
        with patch.object(SYNC, "ROOT", self.root), patch.object(sys, "argv", ["sync"] + (["--write"] if write else [])), contextlib.redirect_stdout(io.StringIO()):
            return SYNC.main()

    def test_older_compatible_floor_is_not_stale(self):
        self.assertEqual(0, self.run_sync())

    def test_write_syncs_readme_to_descriptor_without_bumping_floor_or_dependencies(self):
        self.readme.write_text('turboismApi: "[0.66.0,1.0.0)"\n- SDK API: `[0.66.0,1.0.0)`.\n- dependency: `[0.1.0,0.68.0)`.\n')
        self.assertEqual(1, self.run_sync())
        self.assertEqual(0, self.run_sync(True))
        self.assertEqual("[0.67.0,1.0.0)", json.loads(self.descriptor.read_text())["turboismApi"])
        self.assertIn('SDK API: `[0.67.0,1.0.0)`', self.readme.read_text())
        self.assertIn('dependency: `[0.1.0,0.68.0)`', self.readme.read_text())
        self.assertEqual(0, self.run_sync())

    def test_future_floor_and_expired_ceiling_are_rejected(self):
        for value in ["[0.68.0,1.0.0)", "[0.66.0,0.67.1)", "[1.0.0,0.67.0)"]:
            with self.subTest(value=value):
                self.descriptor.write_text(json.dumps({"turboismApi": value}))
                with self.assertRaises(ValueError):
                    self.run_sync(True)


if __name__ == "__main__":
    unittest.main()
