#!/usr/bin/env python3
"""Immutable fixture inventory gate: hashes, safe paths, and full coverage."""
from __future__ import annotations

import hashlib
import json
import re
import sys
from pathlib import Path, PurePosixPath

ROOT = Path(__file__).resolve().parents[1] / "tests/fixtures/snapshots"
ALLOWED_FAMILIES = {"runtime-config", "typed-config"}
ALLOWED_ORIGINS = {"synthetic-format-v1", "verified-release-artifact"}


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
