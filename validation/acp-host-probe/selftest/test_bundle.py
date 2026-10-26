import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
import zipfile

spec = importlib.util.spec_from_file_location("bundle", Path(__file__).parents[1] / "check_bundle.py")
bundle = importlib.util.module_from_spec(spec)
spec.loader.exec_module(bundle)


class BundleTest(unittest.TestCase):
    def check(self, dependency="[0.1.0,0.68.0)", plugin_id="dev.turboism.plugin.mcp"):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for name, data in {
                "mcp.jar": {"id": plugin_id, "version": "0.67.2"},
                "acp.jar": {"id": "dev.turboism.plugin.acp", "version": "0.67.2"},
                "acp-host-validation-probe.jar": {"id": "dev.turboism.validation.acp", "version": "0.0.0",
                    "dependencies": [{"id": "dev.turboism.plugin.mcp", "version": dependency, "type": "required"}]},
            }.items():
                with zipfile.ZipFile(root / name, "w") as jar:
                    jar.writestr("META-INF/turboism/plugin.json", json.dumps(data))
            return bundle.check(root)

    def test_current_version(self):
        self.assertEqual(3, self.check())

    def test_stale_dependency(self):
        with self.assertRaisesRegex(ValueError, "incompatible"):
            self.check("[0.1.0,0.2.0)")

    def test_wrong_identity(self):
        with self.assertRaisesRegex(ValueError, "identities"):
            self.check(plugin_id="foreign")

    def test_unknown_range_fails_closed(self):
        with self.assertRaisesRegex(ValueError, "Unsupported"):
            self.check("*")


if __name__ == "__main__":
    unittest.main()
