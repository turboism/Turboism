#!/usr/bin/env python3
"""Regression tests for fail-closed Editor-model alias admission."""
from __future__ import annotations

import importlib.util
import json
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "scripts/test/check_editor_model_aliases.py"
SPEC = importlib.util.spec_from_file_location("check_editor_model_aliases", SCRIPT)
CHECKER = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(CHECKER)


class EditorModelAliasesTest(unittest.TestCase):
    def test_public_5303_record_carries_exact_additive_subset(self):
        aliases = CHECKER.aliases_in_record(ROOT, CHECKER.ADDITIVE_RECORD)
        self.assertTrue(CHECKER.ADDITIVE_ALIASES <= aliases)
        union, per_record = CHECKER.record_aliases(ROOT)
        self.assertTrue(CHECKER.ADDITIVE_ALIASES <= union)
        self.assertEqual(
            set(CHECKER.ADDITIVE_ALIASES),
            per_record[Path(CHECKER.ADDITIVE_RECORD).name + " (admitted subset)"],
        )

    def test_broader_5303_inventory_does_not_widen_runtime_admission(self):
        aliases = CHECKER.aliases_in_record(ROOT, CHECKER.ADDITIVE_RECORD)
        broader = aliases - CHECKER.ADDITIVE_ALIASES
        self.assertTrue(broader)
        union, _ = CHECKER.record_aliases(ROOT)
        self.assertTrue(broader - union)

    def test_missing_additive_selector_fails_closed(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for relative in (*CHECKER.BASE_RECORDS, CHECKER.ADDITIVE_RECORD):
                source = ROOT / relative
                target = root / relative
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(source.read_bytes())
            missing = next(iter(CHECKER.ADDITIVE_ALIASES))
            path = root / CHECKER.ADDITIVE_RECORD
            document = json.loads(path.read_text(encoding="utf-8"))
            document["selectors"] = [
                selector for selector in document["selectors"]
                if selector.get("alias") != missing
            ]
            path.write_text(json.dumps(document), encoding="utf-8")

            with self.assertRaisesRegex(ValueError, "lacks required additive aliases"):
                CHECKER.record_aliases(root)


if __name__ == "__main__":
    unittest.main(verbosity=2)
