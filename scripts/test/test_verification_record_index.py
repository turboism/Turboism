#!/usr/bin/env python3
"""Fail-closed fixtures for the packaged verification-record index."""
from __future__ import annotations

import importlib.util
import json
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "scripts/verification_record_index.py"
SPEC = importlib.util.spec_from_file_location("verification_record_index", SCRIPT)
INDEX = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(INDEX)

BOOTSTRAP = """
tasks.processResources {
    listOf(
%s
    ).forEach { record ->
        from(rootProject.file("compatibility/cubism/verification/$record")) {
            into("META-INF/turboism/verification")
        }
    }
}
"""

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
    verification = root / INDEX.VERIFICATION_DIR
    verification.mkdir(parents=True)
    for name, document in records.items():
        (verification / name).write_text(json.dumps(document) + "\n")
    bootstrap = root / INDEX.BOOTSTRAP_BUILD
    bootstrap.parent.mkdir(parents=True, exist_ok=True)
    bootstrap.write_text(
        BOOTSTRAP % "\n".join(f'        "{name}",' for name in listed)
    )
    manifest = root / INDEX.MANIFEST_ROOT
    manifest.mkdir(parents=True)
    (manifest / "FixtureVerificationManifest.java").write_text(MANIFEST)


def base_records() -> dict[str, dict]:
    return {
        "cubism-9.9.09-fixture.json": record("cubism-9.9.09.fixture.static"),
        "cubism-9.9.09-extra.json": record("cubism-9.9.09.extra.static"),
    }


class VerificationRecordIndexTest(unittest.TestCase):
    def test_live_tree_is_consistent(self):
        self.assertEqual([], INDEX.check(ROOT))

    def test_consistent_fixture_passes(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            write_tree(root, base_records(), list(RECORD_NAMES))
            self.assertEqual([], INDEX.check(root))

    def test_unlisted_record_fails(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            write_tree(root, base_records(), [RECORD_NAMES[0]])
            found = INDEX.check(root)
            self.assertTrue(any("not packaged" in v for v in found), found)

    def test_packaged_phantom_fails(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            write_tree(root, base_records(),
                       [*RECORD_NAMES, "cubism-9.9.09-ghost.json"])
            found = INDEX.check(root)
            self.assertTrue(any("no such record" in v for v in found), found)

    def test_duplicate_packaging_entry_fails(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            write_tree(root, base_records(),
                       [RECORD_NAMES[0], RECORD_NAMES[0], RECORD_NAMES[1]])
            found = INDEX.check(root)
            self.assertTrue(any("duplicate record entry" in v for v in found), found)

    def test_manifest_pin_without_record_fails(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            records = base_records()
            records["cubism-9.9.09-fixture.json"] = record("cubism-9.9.09.renamed.static")
            write_tree(root, records, list(RECORD_NAMES))
            found = INDEX.check(root)
            self.assertTrue(any("no\nmatching record" in v or "no matching record" in v
                                for v in found), found)

    def test_duplicate_verification_id_fails(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            records = base_records()
            records["cubism-9.9.09-extra.json"] = record("cubism-9.9.09.fixture.static")
            write_tree(root, records, list(RECORD_NAMES))
            with self.assertRaises(INDEX.IndexError_):
                INDEX.check(root)

    def test_filename_version_mismatch_fails(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            records = base_records()
            records["cubism-9.9.09-extra.json"] = record("cubism-9.8.07.extra.static")
            write_tree(root, records, list(RECORD_NAMES))
            with self.assertRaises(INDEX.IndexError_):
                INDEX.check(root)

    def test_render_lists_directory(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            write_tree(root, base_records(), list(RECORD_NAMES))
            self.assertEqual(sorted(RECORD_NAMES), INDEX.on_disk_records(root))

    def test_live_manifest_pins_are_non_vacuous(self):
        ids = INDEX.manifest_verification_ids(ROOT)
        self.assertGreaterEqual(len(ids), 30)
        self.assertIn("cubism-5.3.03.editor-model.static", ids)


if __name__ == "__main__":
    unittest.main(verbosity=2)
