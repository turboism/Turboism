#!/usr/bin/env python3
"""Immutable fixture inventory gate: hashes, safe paths, and full coverage."""
from __future__ import annotations

import hashlib
import json
import re
import sys
from pathlib import Path, PurePosixPath

ROOT = Path(__file__).resolve().parents[1] / "tests/fixtures/snapshots"
ALLOWED_FAMILIES = {"runtime-config", "typed-config", "update-state"}
ALLOWED_ORIGINS = {"synthetic-format-v1", "verified-release-artifact"}
VERIFIED_PROVENANCE_FIELDS = (
    "releaseTag",
    "releaseCommit",
    "writer",
    "producerInvocation",
    "deidentification",
)
RELEASE_TAG = re.compile(r"v[0-9]+[.][0-9]+[.][0-9]+(?:[-+][0-9A-Za-z.-]+)?")
RELEASE_COMMIT = re.compile(r"[0-9a-f]{40}")
SHA256 = re.compile(r"[0-9a-f]{64}")


def require_verified_provenance(case: dict, case_id: str) -> None:
    """A release-derived fixture must carry checkable, non-empty provenance."""
    missing = [
        field
        for field in VERIFIED_PROVENANCE_FIELDS
        if not isinstance(case.get(field), str) or not case[field].strip()
    ]
    if missing:
        raise ValueError(f"missing verified provenance for {case_id}: {', '.join(missing)}")
    if not RELEASE_TAG.fullmatch(case["releaseTag"]):
        raise ValueError(f"invalid releaseTag for {case_id}: {case['releaseTag']}")
    if not RELEASE_COMMIT.fullmatch(case["releaseCommit"]):
        raise ValueError(f"invalid releaseCommit for {case_id}: {case['releaseCommit']}")
    if not SHA256.fullmatch(case.get("sha256", "")):
        raise ValueError(f"invalid sha256 for {case_id}")
    for field in ("writer", "producerInvocation", "deidentification"):
        if len(case[field]) < 8:
            raise ValueError(f"unsubstantiated {field} for {case_id}")


def validate() -> None:
    manifest = json.loads((ROOT / "manifest.json").read_text(encoding="utf-8"))
    cases = manifest["snapshots"]
    if not isinstance(cases, list) or not cases:
        raise ValueError("snapshot manifest must contain at least one case")

    covered: set[str] = set()
    ids: set[str] = set()
    for case in cases:
        case_id = case["id"]
        path = case["path"]
        path_parts = PurePosixPath(path).parts
        if (case_id in ids or not re.fullmatch(r"[a-z0-9][a-z0-9-]+", case_id)
                or not path_parts or path.startswith("/")
                or any(part in {".", ".."} for part in path_parts)
                or "\\" in path):
            raise ValueError(f"invalid snapshot id/path: {case_id}: {path}")
        if (case["family"] not in ALLOWED_FAMILIES
                or case["origin"] not in ALLOWED_ORIGINS
                or not path.startswith(case["family"] + "/")):
            raise ValueError(f"invalid snapshot provenance: {case_id}")
        if case["origin"] == "verified-release-artifact":
            require_verified_provenance(case, case_id)
        document_path = case.get("documentPath")
        if document_path is not None:
            if not isinstance(document_path, str):
                raise ValueError(f"invalid documentPath for {case_id}: {document_path}")
            document_parts = PurePosixPath(document_path).parts
            if (not document_parts
                    or document_path.startswith("/")
                    or any(part in {".", ".."} for part in document_parts)
                    or "\\" in document_path):
                raise ValueError(f"invalid documentPath for {case_id}: {document_path}")
        if path in covered:
            raise ValueError(f"duplicate snapshot path: {path}")
        full_path = ROOT.joinpath(*path_parts)
        if (not full_path.is_file() or full_path.is_symlink()
                or full_path.resolve().parent != ROOT.joinpath(*path_parts[:-1]).resolve()):
            raise ValueError(f"missing or unsafe fixture: {path}")
        observed = hashlib.sha256(full_path.read_bytes()).hexdigest()
        if observed != case["sha256"]:
            raise ValueError(f"snapshot hash changed: {path}")
        if not isinstance(case.get("expectation"), str) or not case["expectation"]:
            raise ValueError(f"missing compatibility oracle: {case_id}")
        ids.add(case_id)
        covered.add(path)

    actual = {
        path.relative_to(ROOT).as_posix()
        for path in ROOT.rglob("*")
        if path.is_file() and path.name not in {"README.md", "manifest.json"}
    }
    if actual != covered:
        raise ValueError(f"fixture inventory mismatch: unlisted={actual - covered}, missing={covered - actual}")
    print(f"checkGoldenSnapshotManifest: {len(covered)} immutable fixtures verified")


def main() -> int:
    try:
        validate()
        return 0
    except (OSError, ValueError, KeyError, TypeError) as exc:
        print(f"checkGoldenSnapshotManifest: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
