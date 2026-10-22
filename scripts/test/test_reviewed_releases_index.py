#!/usr/bin/env python3
"""Fail-closed fixtures for the packaged verification-record index."""
from __future__ import annotations

import json
import tempfile
import unittest
from unittest.mock import patch
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
from scripts import check_reviewed_releases as gate

MANIFEST = """
final class FixtureVerificationManifest {
    static final String PIN = "cubism-9.9.09.fixture.static";
}
"""

RECORD_NAMES = (
    "cubism-9.9.09-fixture.json",
    "cubism-9.9.09-extra.json",
)


def record(version_id: str) -> dict:
    return {
        "format": "turboism.static-verification-record",
        "verificationId": version_id,
        "selectors": [],
        "capabilityIds": [],
    }


def write_tree(root: Path, records: dict[str, dict], listed: list[str]) -> None:
    verification = root / gate.index_VERIFICATION_DIR
    verification.mkdir(parents=True)
    for name, document in records.items():
        (verification / name).write_text(json.dumps(document) + "\n")
    # Expected expansion is independent from physical files in the fixture.
    (root / "expected.txt").write_text("\n".join(listed))
    manifest = root / gate.index_MANIFEST_ROOT
    manifest.mkdir(parents=True)
    (manifest / "FixtureVerificationManifest.java").write_text(MANIFEST)


def base_records() -> dict[str, dict]:
    return {
        "cubism-9.9.09-fixture.json": record("cubism-9.9.09.fixture.static"),
        "cubism-9.9.09-extra.json": record("cubism-9.9.09.extra.static"),
    }


class VerificationRecordIndexTest(unittest.TestCase):
    def setUp(self):
        original = gate.render_records
        def render(root):
            if root == ROOT:
                return original(root)
            names = (root / "expected.txt").read_text().splitlines()
            return {name: (root / gate.index_VERIFICATION_DIR / name).read_bytes()
                    if (root / gate.index_VERIFICATION_DIR / name).exists() else b"{}" for name in names}
        self.patch = patch.object(gate, "render_records", side_effect=render)
        self.patch.start()
        self.addCleanup(self.patch.stop)

    def test_live_tree_is_consistent(self):
        self.assertEqual([], gate.index_check(ROOT))

    def test_consistent_fixture_passes(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            write_tree(root, base_records(), list(RECORD_NAMES))
            self.assertEqual([], gate.index_check(root))

    def test_extra_materialized_record_fails(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            write_tree(root, base_records(), [RECORD_NAMES[0]])
            found = gate.index_check(root)
            self.assertTrue(any("file set differs" in v for v in found), found)

    def test_missing_materialized_record_fails(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            write_tree(root, base_records(),
                       [*RECORD_NAMES, "cubism-9.9.09-ghost.json"])
            found = gate.index_check(root)
            self.assertTrue(any("file set differs" in v for v in found), found)

    def test_modified_materialized_bytes_fail(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            write_tree(root, base_records(), list(RECORD_NAMES))
            with patch.object(gate, "render_records", return_value={name: b"{}" for name in RECORD_NAMES}):
                self.assertTrue(any("bytes differ" in v for v in gate.index_check(root)))

    def test_manifest_pin_without_record_fails(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            records = base_records()
            records["cubism-9.9.09-fixture.json"] = record("cubism-9.9.09.renamed.static")
            write_tree(root, records, list(RECORD_NAMES))
            found = gate.index_check(root)
            self.assertTrue(any("no\nmatching record" in v or "no matching record" in v
                                for v in found), found)

    def test_duplicate_verification_id_fails(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            records = base_records()
            records["cubism-9.9.09-extra.json"] = record("cubism-9.9.09.fixture.static")
            write_tree(root, records, list(RECORD_NAMES))
            with self.assertRaises(gate.index_IndexError_):
                gate.index_check(root)

    def test_filename_version_mismatch_fails(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            records = base_records()
            records["cubism-9.9.09-extra.json"] = record("cubism-9.8.07.extra.static")
            write_tree(root, records, list(RECORD_NAMES))
            with self.assertRaises(gate.index_IndexError_):
                gate.index_check(root)

    def test_render_lists_directory(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            write_tree(root, base_records(), list(RECORD_NAMES))
            self.assertEqual(sorted(RECORD_NAMES), gate.index_on_disk_records(root))

    def test_live_manifest_pins_are_non_vacuous(self):
        ids = gate.index_manifest_verification_ids(ROOT)
        self.assertGreaterEqual(len(ids), 30)
        self.assertIn("cubism-5.3.03.editor-model.static", ids)


if __name__ == "__main__":
    unittest.main(verbosity=2)
