#!/usr/bin/env python3
"""Unit tests for the turboism_dev shared package."""
from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

# Add scripts directory to sys.path so turboism_dev can be imported
SCRIPTS_DIR = Path(__file__).resolve().parents[1]
import sys

if str(SCRIPTS_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPTS_DIR))

from turboism_dev.json_io import dump_json, read_json, write_json
from turboism_dev.paths import REPO_ROOT, repo_root, scripts_dir
from turboism_dev.versions import (
    compact_to_dotted,
    compare_cubism_versions,
    cubism_version_key,
    dotted_to_compact,
    framework_version,
    missing_higher,
    parse_cubism_version,
    version_tag,
)


class TestTurboismDevPaths(unittest.TestCase):
    def test_repo_root_default(self) -> None:
        root = repo_root()
        self.assertTrue((root / "gradle/common-java.gradle.kts").is_file())
        self.assertEqual(root, REPO_ROOT)

    def test_repo_root_with_anchor(self) -> None:
        scripts = scripts_dir()
        self.assertEqual(repo_root(scripts), REPO_ROOT)
        self.assertEqual(repo_root(scripts / "test"), REPO_ROOT)

    def test_scripts_dir(self) -> None:
        self.assertEqual(scripts_dir(), REPO_ROOT / "scripts")


class TestTurboismDevJsonIo(unittest.TestCase):
    def test_read_write_dump_json(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            file_path = Path(tmp) / "sub" / "data.json"
            data = {"name": "Turboism", "tags": [1, 2, 3], "unicode": "中文"}
            write_json(file_path, data)
            self.assertTrue(file_path.is_file())

            loaded = read_json(file_path)
            self.assertEqual(loaded, data)

            rendered = dump_json(data, indent=2)
            self.assertTrue(rendered.endswith("\n"))
            self.assertIn("中文", rendered)


class TestTurboismDevVersions(unittest.TestCase):
    def test_cubism_version_key(self) -> None:
        self.assertEqual(cubism_version_key("5.2.03"), (5, 2, 3))
        self.assertEqual(cubism_version_key("5.3.00"), (5, 3, 0))
        self.assertEqual(cubism_version_key("5203"), (5, 2, 3))
        # Sorting check
        versions = ["5.3.01", "5.2.03", "5.3.00", "5.1.00"]
        sorted_versions = sorted(versions, key=cubism_version_key)
        self.assertEqual(sorted_versions, ["5.1.00", "5.2.03", "5.3.00", "5.3.01"])

    def test_parse_cubism_version(self) -> None:
        self.assertEqual(parse_cubism_version("5.2.03"), (2, 3))
        self.assertEqual(parse_cubism_version("5.3.00"), (3, 0))
        with self.assertRaises(ValueError):
            parse_cubism_version("invalid")

    def test_compare_cubism_versions(self) -> None:
        self.assertEqual(compare_cubism_versions("5.2.03", "5.3.00"), -1)
        self.assertEqual(compare_cubism_versions("5.3.00", "5.3.00"), 0)
        self.assertEqual(compare_cubism_versions("5.3.01", "5.3.00"), 1)

    def test_conversions(self) -> None:
        self.assertEqual(compact_to_dotted("5203"), "5.2.03")
        self.assertEqual(compact_to_dotted("5300"), "5.3.00")
        self.assertEqual(dotted_to_compact("5.2.03"), "5203")
        self.assertEqual(dotted_to_compact("5.3.00"), "5300")
        self.assertEqual(version_tag("5.2.03"), "5_2_03")

    def test_missing_higher(self) -> None:
        declared = {"5.2.01", "5.2.02"}
        admitted = {"5.2.01", "5.2.02", "5.2.03", "5.3.00"}
        missing = missing_higher(declared, admitted)
        self.assertEqual(missing, {"5.2.03"})

    def test_framework_version(self) -> None:
        ver = framework_version()
        self.assertTrue(bool(ver))
        self.assertRegex(ver, r"^\d+\.\d+\.\d+$")


class TestImportMechanism(unittest.TestCase):
    """Entry points put scripts/ on sys.path themselves; no shared _bootstrap module."""

    def test_no_bootstrap_modules(self) -> None:
        self.assertEqual([], sorted(SCRIPTS_DIR.rglob("_bootstrap.py")))

    def test_scripts_package_exposes_scripts_dir(self) -> None:
        import subprocess

        code = (
            "import sys, scripts; "
            "from turboism_dev.paths import REPO_ROOT; "
            "print(sys.path[0])"
        )
        result = subprocess.run(
            [sys.executable, "-c", code],
            cwd=SCRIPTS_DIR.parent,
            capture_output=True,
            text=True,
            check=True,
        )
        self.assertEqual(str(SCRIPTS_DIR), result.stdout.strip())


if __name__ == "__main__":
    unittest.main()
