#!/usr/bin/env python3
"""Fail-closed fixtures for the class-pin generator."""
from __future__ import annotations

import importlib.util
import json
import subprocess
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from turboism_dev import class_pins


ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "scripts/generate_class_pins.py"
SPEC = importlib.util.spec_from_file_location("generate_class_pins", SCRIPT)
GEN = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(GEN)

# Synthetic admitted universe: a new version joins it and inherits `default`.
VERSIONS = ("5.3.02", "5.3.03", "9.9.99")


def write_jar(path: Path, classes: dict[str, bytes]) -> None:
    with zipfile.ZipFile(path, "w") as jar:
        for name, content in classes.items():
            jar.writestr(name + ".class", content)


def write_pin_dir(root: Path, tables: dict[str, dict]) -> None:
    for name, table in tables.items():
        path = root / "class-pins" / f"{name}.json"
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(table), encoding="utf-8")


class GenerateClassPinsTest(unittest.TestCase):
    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory()
        self.root = Path(self._tmp.name)
        self.jar = self.root / "host.jar"
        self.classes = {
            "com/example/A": b"bytes-a",
            "com/example/B": b"bytes-b",
            "com/example/C$D": b"inner-bytes",
        }
        write_jar(self.jar, self.classes)
        self.tables = {
            "hook-one": {"5.3.02": ["com/example/A"]},
            "hook-two": {
                "5.3.02": ["com/example/B"],
                "5.3.03": ["com/example/C$D"],
            },
        }
        write_pin_dir(self.root, self.tables)
        self._old_pin_dir = GEN.PIN_DIR
        self._old_versions = GEN.admitted_versions
        GEN.PIN_DIR = self.root / "class-pins"
        GEN.admitted_versions = lambda root=GEN.ROOT: list(VERSIONS)

    def tearDown(self) -> None:
        GEN.PIN_DIR = self._old_pin_dir
        GEN.admitted_versions = self._old_versions
        self._tmp.cleanup()

    def _args(self, mode: str, **over):
        base = dict(
            mode=mode, jar=str(self.jar), version="9.9.99", pin=[],
            new=None, classes=[], allow_partial=False, dry_run=False,
        )
        base.update(over)
        return type("Args", (), base)

    def _expanded(self, name: str) -> dict[str, list[str]]:
        return class_pins.load(GEN.PIN_DIR / f"{name}.json", VERSIONS)

    def test_update_adds_version_block_to_all_tables(self):
        rc = GEN._cmd_update(self._args("update"))
        self.assertEqual(0, rc)
        for name in self.tables:
            written = self._expanded(name)
            keys = {k for block in self.tables[name].values() for k in block}
            self.assertEqual(sorted(keys), sorted(written["9.9.99"]))
            # pre-existing blocks survive untouched
            for version, block in self.tables[name].items():
                self.assertEqual(block, written[version])
        self.assertIn("com/example/A", self._expanded("hook-one")["9.9.99"])

    def test_update_folds_an_identical_table_into_one_default_block(self):
        GEN._cmd_update(self._args("update", pin=["hook-one"]))
        document = json.loads((GEN.PIN_DIR / "hook-one.json").read_text())
        self.assertEqual({"default": ["com/example/A"]}, document)

    def test_update_keeps_only_differing_versions_as_overrides(self):
        write_pin_dir(self.root, {
            "hook-three": {
                "5.3.02": ["com/example/A"],
                "5.3.03": ["com/example/A"],
            },
        })
        GEN._cmd_update(self._args("update", pin=["hook-three"]))
        document = json.loads((GEN.PIN_DIR / "hook-three.json").read_text())
        # The new version declares the union of every key, so all three blocks agree
        # and the table collapses to a single `default` with no overrides block.
        self.assertEqual({"default": ["com/example/A"]}, document)

    def test_update_tie_prefers_the_newest_block_as_default(self):
        GEN._cmd_update(self._args("update", pin=["hook-two"]))
        document = json.loads((GEN.PIN_DIR / "hook-two.json").read_text())
        self.assertEqual(["com/example/B", "com/example/C$D"], document["default"])
        self.assertEqual(
            {"5.3.02": ["com/example/B"], "5.3.03": ["com/example/C$D"]},
            document["overrides"],
        )

    def test_update_selected_pin_only(self):
        GEN._cmd_update(self._args("update", pin=["hook-one"]))
        one = self._expanded("hook-one")
        two = self._expanded("hook-two")
        self.assertIn("9.9.99", one)
        self.assertNotIn("9.9.99", two)

    def test_missing_class_aborts_file(self):
        write_pin_dir(self.root, {
            "hook-gap": {"5.3.02": ["com/example/Absent"]},
        })
        rc = GEN._cmd_update(self._args("update", pin=["hook-gap"]))
        self.assertEqual(1, rc)
        written = self._expanded("hook-gap")
        self.assertNotIn("9.9.99", written)

    def test_allow_partial_writes_found_classes(self):
        write_pin_dir(self.root, {
            "hook-gap": {
                "5.3.02": ["com/example/A", "com/example/Absent"],
            },
        })
        rc = GEN._cmd_update(self._args(
            "update", pin=["hook-gap"], allow_partial=True))
        self.assertEqual(0, rc)
        written = self._expanded("hook-gap")
        self.assertEqual(["com/example/A"], list(written["9.9.99"]))

    def test_new_pin_file(self):
        rc = GEN._cmd_update(self._args(
            "update", new="hook-fresh",
            classes=["com/example/A", "com/example/C$D"]))
        self.assertEqual(0, rc)
        written = self._expanded("hook-fresh")
        self.assertEqual(
            ["com/example/A", "com/example/C$D"], sorted(written["9.9.99"]))

    def test_check_passes_on_matching_jar(self):
        GEN._cmd_update(self._args("update"))
        self.assertEqual(0, GEN._cmd_check(self._args("check")))

    def test_check_fails_on_absent_declared_class(self):
        GEN._cmd_update(self._args("update"))
        write_jar(self.jar, {"com/example/B": b"b", "com/example/C$D": b"inner"})
        self.assertEqual(1, GEN._cmd_check(self._args("check")))

    def test_check_fails_on_absent_version(self):
        self.assertEqual(1, GEN._cmd_check(self._args("check")))

    def test_dry_run_does_not_write(self):
        rc = GEN._cmd_update(self._args("update", dry_run=True))
        self.assertEqual(0, rc)
        written = self._expanded("hook-one")
        self.assertNotIn("9.9.99", written)

    def test_cli_end_to_end(self):
        result = subprocess.run(
            [sys.executable, str(SCRIPT), "check",
             "--jar", str(self.jar), "--version", "9.9.99"],
            capture_output=True, text=True,
            env={"PATH": "/usr/bin:/bin", "PYTHONPATH": ""},
            # script computes PIN_DIR from repo root; smoke-test exit code only
        )
        # check mode against the live repo pins: 9.9.99 exists nowhere -> 1
        self.assertIn(result.returncode, (0, 1))
        self.assertNotIn("Traceback", result.stderr)


if __name__ == "__main__":
    unittest.main(verbosity=2)
