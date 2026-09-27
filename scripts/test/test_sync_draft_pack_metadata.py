#!/usr/bin/env python3
"""Fail-closed fixtures for DRAFT pack metadata synchronization."""
from __future__ import annotations

import copy
import hashlib
import importlib.util
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "scripts/sync_draft_pack_metadata.py"
SPEC = importlib.util.spec_from_file_location("sync_draft_pack_metadata", SCRIPT)
SYNC = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(SYNC)

PACK_DIR = Path("compatibility/cubism/mapping-packs/draft")
RECORD_DIR = Path("compatibility/cubism/verification")
RECORD_REL = RECORD_DIR / "fixture-record.json"


def fixture_record() -> dict:
    return {
        "format": "turboism.static-verification-record",
        "schemaVersion": 1,
        "verificationId": "fixture.record.static",
        "capabilityIds": ["cap.alpha", "cap.beta", "cap.gamma"],
        "selectors": [
            {
                "mappingId": "fixture.one",
                "alias": "fixture.alias.one",
                "kind": "method",
                "ownerInternalName": "a/B",
                "memberName": "one",
                "descriptor": "()V",
                "requiredAccessFlags": 1,
                "forbiddenAccessFlags": 0,
                "status": "VERIFIED_STATIC",
            },
            {
                "mappingId": "fixture.two",
                "alias": "fixture.alias.two",
                "kind": "method",
                "ownerInternalName": "a/B",
                "memberName": "two",
                "descriptor": "()V",
                "requiredAccessFlags": 1,
                "forbiddenAccessFlags": 0,
                "status": "VERIFIED_STATIC",
            },
            {
                "mappingId": "fixture.three",
                "alias": "fixture.alias.three",
                "kind": "class",
                "ownerInternalName": "a/C",
                "memberName": None,
                "descriptor": None,
                "requiredAccessFlags": 1,
                "forbiddenAccessFlags": 0,
                "status": "VERIFIED_STATIC",
            },
        ],
    }


def fixture_pack(record_bytes: bytes, record: dict) -> dict:
    return {
        "format": "turboism.mapping.pack",
        "schemaVersion": 1,
        "status": "DRAFT",
        "cubismVersion": "9.9.09",
        "entries": [],
        "metadata": {
            "description": "keeps its position and text",
            "inventoryRef": RECORD_REL.as_posix(),
            "selectorCount": len(record["selectors"]),
            "capabilityCount": len(record["capabilityIds"]),
            "capabilityIds": list(record["capabilityIds"]),
            "verificationRecordSha256": hashlib.sha256(record_bytes).hexdigest(),
        },
    }


def write_tree(root: Path, pack: dict, record: dict, record_bytes: bytes | None = None) -> bytes:
    raw = record_bytes if record_bytes is not None else (
        json.dumps(record, indent=2).encode() + b"\n"
    )
    record_path = root / RECORD_REL
    record_path.parent.mkdir(parents=True, exist_ok=True)
    record_path.write_bytes(raw)
    pack_path = root / PACK_DIR / "fixture-pack.json"
    pack_path.parent.mkdir(parents=True, exist_ok=True)
    pack_path.write_text(json.dumps(pack, indent=2) + "\n", encoding="utf-8")
    return raw


def checked(root: Path) -> int:
    return SYNC.run(root, check=True)


class DraftPackMetadataTest(unittest.TestCase):
    def make_root(self, directory: str) -> tuple[Path, dict, dict]:
        root = Path(directory)
        record = fixture_record()
        raw = json.dumps(record, indent=2).encode() + b"\n"
        pack = fixture_pack(raw, record)
        write_tree(root, pack, record, raw)
        return root, pack, record

    def rewrite_pack(self, root: Path, pack: dict) -> None:
        (root / PACK_DIR / "fixture-pack.json").write_text(
            json.dumps(pack, indent=2) + "\n", encoding="utf-8"
        )

    def assert_fails(self, root: Path, field: str) -> None:
        self.assertEqual(1, checked(root), f"tampered {field} must fail --check")

    def test_live_tree_is_in_sync(self):
        self.assertEqual(0, checked(ROOT))

    def test_in_sync_fixture_passes(self):
        with tempfile.TemporaryDirectory() as directory:
            root, _, _ = self.make_root(directory)
            self.assertEqual(0, checked(root))

    def test_sync_repairs_and_preserves_non_metadata_bytes(self):
        with tempfile.TemporaryDirectory() as directory:
            root, pack, _ = self.make_root(directory)
            pack["metadata"]["selectorCount"] = 999
            pack["metadata"].pop("capabilityIds")
            self.rewrite_pack(root, pack)
            before = (root / PACK_DIR / "fixture-pack.json").read_text()
            self.assertEqual(1, checked(root))
            self.assertEqual(0, SYNC.run(root, check=False))
            self.assertEqual(0, checked(root))
            after = (root / PACK_DIR / "fixture-pack.json").read_text()
            prefix = before.index('  "metadata"')
            self.assertEqual(before[:prefix], after[:prefix])
            document = json.loads(after)
            self.assertEqual("keeps its position and text",
                             document["metadata"]["description"])
            self.assertEqual(
                ["cap.alpha", "cap.beta", "cap.gamma"],
                document["metadata"]["capabilityIds"],
            )

    def test_cli_check_exit_codes(self):
        with tempfile.TemporaryDirectory() as directory:
            root, pack, _ = self.make_root(directory)
            ok = subprocess.run(
                [sys.executable, str(SCRIPT), str(root), "--check"],
                capture_output=True, text=True,
            )
            self.assertEqual(0, ok.returncode, ok.stderr + ok.stdout)
            pack["metadata"]["selectorCount"] = 0
            self.rewrite_pack(root, pack)
            bad = subprocess.run(
                [sys.executable, str(SCRIPT), str(root), "--check"],
                capture_output=True, text=True,
            )
            self.assertEqual(1, bad.returncode)
            self.assertIn("selectorCount", bad.stdout)

    def test_tampered_selector_count_fails(self):
        with tempfile.TemporaryDirectory() as directory:
            root, pack, _ = self.make_root(directory)
            pack["metadata"]["selectorCount"] = 4
            self.rewrite_pack(root, pack)
            self.assert_fails(root, "selectorCount")

    def test_tampered_capability_count_fails(self):
        with tempfile.TemporaryDirectory() as directory:
            root, pack, _ = self.make_root(directory)
            pack["metadata"]["capabilityCount"] = 2
            self.rewrite_pack(root, pack)
            self.assert_fails(root, "capabilityCount")

    def test_missing_capability_id_fails(self):
        with tempfile.TemporaryDirectory() as directory:
            root, pack, _ = self.make_root(directory)
            pack["metadata"]["capabilityIds"] = ["cap.alpha", "cap.beta"]
            self.rewrite_pack(root, pack)
            self.assert_fails(root, "capabilityIds")

    def test_extra_capability_id_fails(self):
        with tempfile.TemporaryDirectory() as directory:
            root, pack, _ = self.make_root(directory)
            pack["metadata"]["capabilityIds"] = [
                "cap.alpha", "cap.beta", "cap.gamma", "cap.delta"
            ]
            self.rewrite_pack(root, pack)
            self.assert_fails(root, "capabilityIds")

    def test_duplicate_capability_id_fails(self):
        with tempfile.TemporaryDirectory() as directory:
            root, pack, _ = self.make_root(directory)
            pack["metadata"]["capabilityIds"] = [
                "cap.alpha", "cap.beta", "cap.beta"
            ]
            self.rewrite_pack(root, pack)
            self.assert_fails(root, "capabilityIds")

    def test_capability_id_order_drift_fails(self):
        with tempfile.TemporaryDirectory() as directory:
            root, pack, _ = self.make_root(directory)
            pack["metadata"]["capabilityIds"] = [
                "cap.beta", "cap.alpha", "cap.gamma"
            ]
            self.rewrite_pack(root, pack)
            self.assert_fails(root, "capabilityIds")

    def test_tampered_record_digest_fails(self):
        with tempfile.TemporaryDirectory() as directory:
            root, pack, _ = self.make_root(directory)
            pack["metadata"]["verificationRecordSha256"] = "0" * 64
            self.rewrite_pack(root, pack)
            self.assert_fails(root, "verificationRecordSha256")

    def test_absent_managed_fields_fail(self):
        with tempfile.TemporaryDirectory() as directory:
            root, pack, _ = self.make_root(directory)
            for field in SYNC.MANAGED_FIELDS:
                pack["metadata"].pop(field)
            self.rewrite_pack(root, pack)
            self.assert_fails(root, "all managed fields")

    def test_missing_inventory_target_fails(self):
        with tempfile.TemporaryDirectory() as directory:
            root, pack, _ = self.make_root(directory)
            (root / RECORD_REL).unlink()
            self.assert_fails(root, "inventoryRef target")

    def test_non_verification_inventory_ref_is_out_of_scope(self):
        with tempfile.TemporaryDirectory() as directory:
            root, pack, _ = self.make_root(directory)
            observed = root / "compatibility/cubism/core-api/observed"
            observed.mkdir(parents=True)
            (observed / "fixture.json").write_text("{}")
            pack["metadata"] = {
                "inventoryRef": "compatibility/cubism/core-api/observed/fixture.json"
            }
            self.rewrite_pack(root, pack)
            self.assertEqual(0, checked(root))

    def test_pack_without_inventory_ref_is_out_of_scope(self):
        with tempfile.TemporaryDirectory() as directory:
            root, pack, _ = self.make_root(directory)
            pack["metadata"] = {"validationNotes": "no binding"}
            self.rewrite_pack(root, pack)
            self.assertEqual(0, checked(root))

    def test_metadata_patch_is_surgical_on_unmanaged_fields(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            record = fixture_record()
            raw = json.dumps(record, indent=2).encode() + b"\n"
            pack = fixture_pack(raw, record)
            pack["metadata"] = {
                "mergedFrom": ["a.json", "b.json"],
                "inventoryRef": RECORD_REL.as_posix(),
                "selectorCount": 1,
                "capabilityCount": 1,
                "capabilityIds": [],
                "verificationRecordSha256": "0" * 64,
                "validationNotes": "tail stays last",
            }
            write_tree(root, pack, record, raw)
            self.assertEqual(0, SYNC.run(root, check=False))
            document = json.loads(
                (root / PACK_DIR / "fixture-pack.json").read_text()
            )
            self.assertEqual(
                ["mergedFrom", "inventoryRef", "selectorCount", "capabilityCount",
                 "capabilityIds", "verificationRecordSha256", "validationNotes"],
                list(document["metadata"].keys()),
            )
            self.assertEqual(3, document["metadata"]["selectorCount"])


if __name__ == "__main__":
    unittest.main(verbosity=2)
