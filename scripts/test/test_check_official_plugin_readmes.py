"""README version contract: documentation may lag the descriptor but never lead it."""
import importlib.util
from pathlib import Path
import sys
import unittest

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts"))
spec = importlib.util.spec_from_file_location("readme_check", ROOT / "scripts/test/check_official_plugin_readmes.py")
CHECK = importlib.util.module_from_spec(spec)
spec.loader.exec_module(CHECK)


class DocumentedVersionTest(unittest.TestCase):
    def test_equal_or_older_documented_version_is_accepted(self):
        self.assertEqual(CHECK.documented_version_errors("0.68.0", "0.68.0"), [])
        self.assertEqual(CHECK.documented_version_errors("0.1.0", "0.68.2"), [])

    def test_newer_or_malformed_documented_version_is_rejected(self):
        self.assertTrue(CHECK.documented_version_errors("0.68.1", "0.68.0"))
        self.assertTrue(CHECK.documented_version_errors("0.9.0", "0.10.0") == [])
        self.assertTrue(CHECK.documented_version_errors("abc", "0.68.0"))
        self.assertTrue(CHECK.documented_version_errors("0.68", "0.68.0"))


if __name__ == "__main__":
    unittest.main()
