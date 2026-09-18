#!/usr/bin/env python3
"""Sync DRAFT mapping-pack metadata projections from hash-pinned verification records.

A draft pack whose ``metadata.inventoryRef`` resolves under
``compatibility/cubism/verification/`` restates four fields derived from the
referenced record bytes:

- ``selectorCount``            == len(record.selectors)
- ``capabilityCount``          == len(record.capabilityIds)
- ``capabilityIds``            == record.capabilityIds (exact ordered list)
- ``verificationRecordSha256`` == SHA-256 of the record file bytes

Record bytes are the SHA-256-pinned audit chain and are never modified by this
tool; only the pack's ``metadata`` object is patched, surgically, so untouched
regions keep their bytes. Default mode rewrites out-of-sync packs; ``--check``
reports mismatches and exits non-zero.

Usage: sync_draft_pack_metadata.py [repo-root] [--check]
"""
from __future__ import annotations

import argparse
import hashlib
import json
import sys
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parents[1]
DRAFT_PACKS = Path("compatibility/cubism/mapping-packs/draft")
VERIFICATION_RECORDS = Path("compatibility/cubism/verification")
MANAGED_FIELDS = (
    "selectorCount",
    "capabilityCount",
    "capabilityIds",
    "verificationRecordSha256",
)


class SyncError(Exception):
    """Raised for malformed inputs that prevent any decision."""


def load_json(path: Path) -> Any:
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise SyncError(f"unable to read {path}: {exc}") from exc


def _decode_value(text: str, start: int) -> int:
    """Returns the end offset of the JSON value starting at ``start``."""
    cursor = start
    while cursor < len(text) and text[cursor] in " \t\n\r":
        cursor += 1
    try:
        _, end = json.JSONDecoder().raw_decode(text, cursor)
    except json.JSONDecodeError as exc:
        raise SyncError(f"malformed JSON value at offset {cursor}: {exc}") from exc
    return end


def _object_members(text: str, start: int, end: int) -> dict[str, tuple[int, int, int]]:
    """Maps each depth-1 member key of the object at ``[start, end)`` to
    ``(key_start, value_start, value_end)`` offsets in ``text``."""
    members: dict[str, tuple[int, int, int]] = {}
    cursor = start + 1
    while cursor < end - 1:
        while cursor < end and text[cursor] in " \t\n\r,":
            cursor += 1
        if cursor >= end - 1:
            break
        if text[cursor] != '"':
            raise SyncError(f"expected object key at offset {cursor}")
        key_end = _decode_value(text, cursor)
        key = json.loads(text[cursor:key_end])
        colon = text.index(":", key_end)
        value_start = colon + 1
        while text[value_start] in " \t\n\r":
            value_start += 1
        value_end = _decode_value(text, value_start)
        members[key] = (cursor, value_start, value_end)
        cursor = value_end
    return members


def _top_level_object_span(text: str) -> tuple[int, int]:
    start = text.index("{")
    return start, _decode_value(text, start)


def metadata_span(text: str) -> tuple[int, int, int]:
    """Returns ``(value_start, value_end, key_indent)`` for the top-level
    ``metadata`` member, or ``(-1, -1, 0)`` when absent."""
    start, end = _top_level_object_span(text)
    for key, (key_start, value_start, value_end) in _object_members(
        text, start, end
    ).items():
        if key == "metadata":
            line_start = text.rfind("\n", 0, key_start) + 1
            key_indent = key_start - line_start
            return value_start, value_end, key_indent
    return -1, -1, 0


def _indented(document: str, indent: int) -> str:
    """Re-indents a canonical ``json.dumps(indent=2)`` document so its members
    sit ``indent`` columns deeper than the surrounding key."""
    lines = document.split("\n")
    pad = " " * indent
    return lines[0] + "".join("\n" + pad + line for line in lines[1:])


def expected_metadata(record_path: Path) -> dict[str, Any]:
    raw = record_path.read_bytes()
    record = json.loads(raw)
    selectors = record.get("selectors")
    capabilities = record.get("capabilityIds")
    if not isinstance(selectors, list) or not isinstance(capabilities, list):
        raise SyncError(f"{record_path}: record lacks selectors/capabilityIds")
    return {
        "selectorCount": len(selectors),
        "capabilityCount": len(capabilities),
        "capabilityIds": capabilities,
        "verificationRecordSha256": hashlib.sha256(raw).hexdigest(),
    }


def patch_pack(text: str, expected: dict[str, Any]) -> str:
    """Returns ``text`` with the managed ``metadata`` fields set to
    ``expected``, preserving all other bytes and the pack's key order."""
    document = json.loads(text)
    metadata = document.get("metadata")
    if not isinstance(metadata, dict):
        raise SyncError("pack has no metadata object to patch")
    value_start, value_end, key_indent = metadata_span(text)
    if value_start < 0:
        raise SyncError("unable to locate metadata object bytes")
    updated = dict(metadata)
    for field in MANAGED_FIELDS:
        updated[field] = expected[field]
    replacement = _indented(json.dumps(updated, indent=2), key_indent)
    patched = text[:value_start] + replacement + text[value_end:]
    reparsed = json.loads(patched)
    expected_document = dict(document)
    expected_document["metadata"] = updated
    if reparsed != expected_document:
        raise SyncError("patched metadata does not reparse to the expected document")
    return patched


def inventory_ref_target(root: Path, pack_path: Path, metadata: dict[str, Any]) -> Path | None:
    """Resolves ``metadata.inventoryRef`` inside ``root``; ``None`` when the pack
    does not reference an inventory."""
    ref = metadata.get("inventoryRef")
    if ref is None:
        return None
    if not isinstance(ref, str) or not ref:
        raise SyncError(f"{pack_path}: metadata.inventoryRef must be a non-empty string")
    target = (root / ref).resolve()
    if not target.is_relative_to(root.resolve()):
        raise SyncError(f"{pack_path}: inventoryRef escapes the repository: {ref}")
    return target


def is_verification_record(root: Path, target: Path) -> bool:
    records_dir = (root / VERIFICATION_RECORDS).resolve()
    return target.is_relative_to(records_dir)


def metadata_violations(
    root: Path, pack_path: Path, metadata: dict[str, Any], target: Path
) -> list[str]:
    """Returns the managed-field mismatches between a verification-bound pack
    and its referenced record."""
    if not target.is_file():
        return [f"{pack_path.name}: inventoryRef target missing: "
                f"{metadata['inventoryRef']}"]
    expected = expected_metadata(target)
    violations = []
    for field in ("selectorCount", "capabilityCount"):
        actual = metadata.get(field)
        if actual != expected[field]:
            violations.append(
                f"{pack_path.name}: metadata.{field} {actual!r} "
                f"!= record value {expected[field]!r}"
            )
    actual_ids = metadata.get("capabilityIds")
    if not isinstance(actual_ids, list):
        violations.append(f"{pack_path.name}: metadata.capabilityIds missing or not an array")
    else:
        if len(set(actual_ids)) != len(actual_ids):
            violations.append(f"{pack_path.name}: metadata.capabilityIds contains duplicates")
        if actual_ids != expected["capabilityIds"]:
            missing = [c for c in expected["capabilityIds"] if c not in actual_ids]
            extra = [c for c in actual_ids if c not in expected["capabilityIds"]]
            detail = ""
            if missing:
                detail += f" missing={missing}"
            if extra:
                detail += f" extra={extra}"
            violations.append(
                f"{pack_path.name}: metadata.capabilityIds does not match record"
                f" capabilityIds{detail or ' (order drift)'}"
            )
    actual_sha = metadata.get("verificationRecordSha256")
    if actual_sha != expected["verificationRecordSha256"]:
        violations.append(
            f"{pack_path.name}: metadata.verificationRecordSha256 {actual_sha!r} "
            f"!= sha256(record bytes) {expected['verificationRecordSha256']!r}"
        )
    return violations


def iter_draft_packs(root: Path) -> list[Path]:
    packs_dir = root / DRAFT_PACKS
    return sorted(packs_dir.glob("*.json"))


def run(root: Path, check: bool) -> int:
    violations: list[str] = []
    synced: list[str] = []
    skipped = 0
    for pack_path in iter_draft_packs(root):
        document = load_json(pack_path)
        metadata = document.get("metadata")
        if not isinstance(metadata, dict):
            skipped += 1
            continue
        try:
            target = inventory_ref_target(root, pack_path, metadata)
        except SyncError as exc:
            violations.append(str(exc))
            continue
        if target is None or not is_verification_record(root, target):
            skipped += 1
            continue
        found = metadata_violations(root, pack_path, metadata, target)
        if not found:
            continue
        if check:
            violations.extend(found)
            continue
        text = pack_path.read_text(encoding="utf-8")
        patched = patch_pack(text, expected_metadata(target))
        if patched != text:
            pack_path.write_text(patched, encoding="utf-8")
            synced.append(pack_path.name)

    if check:
        for violation in violations:
            print(f"FAIL: {violation}")
        if violations:
            print(f"\n{len(violations)} draft pack metadata finding(s)")
            return 1
        print("PASS: draft pack metadata matches referenced verification records")
        return 0

    for violation in violations:
        print(f"FAIL: {violation}")
    for name in synced:
        print(f"synced {name}")
    if violations:
        print(f"\n{len(violations)} unrecoverable finding(s)")
        return 1
    print(f"PASS: {len(synced)} pack(s) synced, {skipped} pack(s) without "
          "verification inventoryRef untouched")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("root", nargs="?", default=str(ROOT))
    parser.add_argument(
        "--check",
        action="store_true",
        help="verify instead of rewriting; exit 1 on any mismatch",
    )
    args = parser.parse_args()
    root = Path(args.root).resolve()
    try:
        return run(root, args.check)
    except SyncError as exc:
        print(f"FAIL: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
