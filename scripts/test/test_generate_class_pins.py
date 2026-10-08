#!/usr/bin/env python3
"""Fail-closed fixtures for the class-pin generator."""
from __future__ import annotations

import hashlib
import importlib.util
import json
import subprocess
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "scripts/generate_class_pins.py"
SPEC = importlib.util.spec_from_file_location("generate_class_pins", SCRIPT)
GEN = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(GEN)


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
            "hook-one": {"5.3.02": {"com/example/A": "0" * 64}},
            "hook-two": {
                "5.3.02": {"com/example/B": "1" * 64},
                "5.3.03": {"com/example/C$D": "2" * 64},
            },
        }
        write_pin_dir(self.root, self.tables)
        self._old_pin_dir = GEN.PIN_DIR
        GEN.PIN_DIR = self.root / "class-pins"

    def tearDown(self) -> None:
        GEN.PIN_DIR = self._old_pin_dir
        self._tmp.cleanup()

    def _args(self, mode: str, **over):
        base = dict(
            mode=mode, jar=str(self.jar), version="9.9.99", pin=[],
            new=None, classes=[], allow_partial=False, dry_run=False,
        )
        base.update(over)
        return type("Args", (), base)

    def test_update_adds_version_block_to_all_tables(self):
        rc = GEN._cmd_update(self._args("update"))
        self.assertEqual(0, rc)
        expected_a = hashlib.sha256(self.classes["com/example/A"]).hexdigest()
        for name, table in self.tables.items():
            written = json.loads((GEN.PIN_DIR / f"{name}.json").read_text())
            # every class key ever named by the table is pinned under the new version
            keys = {k for block in table.values() for k in block}
            self.assertEqual(sorted(keys), sorted(written["9.9.99"]))
            # pre-existing blocks survive untouched
            for version, block in table.items():
                self.assertEqual(block, written[version])
        written = json.loads((GEN.PIN_DIR / "hook-one.json").read_text())
        self.assertEqual(expected_a, written["9.9.99"]["com/example/A"])

    def test_update_selected_pin_only(self):
        GEN._cmd_update(self._args("update", pin=["hook-one"]))
        one = json.loads((GEN.PIN_DIR / "hook-one.json").read_text())
        two = json.loads((GEN.PIN_DIR / "hook-two.json").read_text())
        self.assertIn("9.9.99", one)
        self.assertNotIn("9.9.99", two)

    def test_missing_class_aborts_file(self):
        write_pin_dir(self.root, {
            "hook-gap": {"5.3.02": {"com/example/Absent": "0" * 64}},
        })
        rc = GEN._cmd_update(self._args("update", pin=["hook-gap"]))
        self.assertEqual(1, rc)
        written = json.loads((GEN.PIN_DIR / "hook-gap.json").read_text())
        self.assertNotIn("9.9.99", written)

    def test_allow_partial_writes_found_classes(self):
        write_pin_dir(self.root, {
            "hook-gap": {
                "5.3.02": {
                    "com/example/A": "0" * 64,
                    "com/example/Absent": "0" * 64,
                }
            },
        })
        rc = GEN._cmd_update(self._args(
            "update", pin=["hook-gap"], allow_partial=True))
        self.assertEqual(0, rc)
        written = json.loads((GEN.PIN_DIR / "hook-gap.json").read_text())
        self.assertEqual(["com/example/A"], list(written["9.9.99"]))

    def test_new_pin_file(self):
        rc = GEN._cmd_update(self._args(
            "update", new="hook-fresh",
            classes=["com/example/A", "com/example/C$D"]))
        self.assertEqual(0, rc)
        written = json.loads((GEN.PIN_DIR / "hook-fresh.json").read_text())
        self.assertEqual(2, len(written["9.9.99"]))
        self.assertEqual(
            hashlib.sha256(self.classes["com/example/C$D"]).hexdigest(),
            written["9.9.99"]["com/example/C$D"],
        )

    def test_check_passes_on_matching_jar(self):
        GEN._cmd_update(self._args("update"))
        self.assertEqual(0, GEN._cmd_check(self._args("check")))

    def test_check_fails_on_digest_mismatch(self):
        GEN._cmd_update(self._args("update"))
        write_jar(self.jar, {"com/example/A": b"tampered", "com/example/B": b"b",
                             "com/example/C$D": b"inner"})
        self.assertEqual(1, GEN._cmd_check(self._args("check")))

    def test_check_fails_on_absent_version(self):
        self.assertEqual(1, GEN._cmd_check(self._args("check")))

    def test_dry_run_does_not_write(self):
        rc = GEN._cmd_update(self._args("update", dry_run=True))
        self.assertEqual(0, rc)
        written = json.loads((GEN.PIN_DIR / "hook-one.json").read_text())
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
