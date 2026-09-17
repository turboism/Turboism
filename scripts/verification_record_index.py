#!/usr/bin/env python3
"""Derive and check the packaged verification-record index.

The bootstrap fat JAR ships ``META-INF/turboism/verification/*.json`` from an
explicit filename list in ``bootstrap/build.gradle.kts``. A record missing from
that list only fails closed when the agent extracts it at host start, so this
gate derives the required set at build time:

- the packaging list must equal the ``compatibility/cubism/verification/``
  directory listing exactly (both directions);
- every ``*.static`` verification id pinned in
  ``runtime/src/main/java/dev/turboism/mapping/verification/`` must resolve to a
  record on disk whose ``verificationId`` field matches;
- every record filename must carry the dotted Cubism version contained in its
  ``verificationId``, and record ids must be unique.

``--check`` (the default) verifies; ``render`` prints the derived packaging
list for inspection. Product structure is unchanged: nothing extra is shipped.

Usage: verification_record_index.py [repo-root] [--check | --render]
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
VERIFICATION_DIR = Path("compatibility/cubism/verification")
BOOTSTRAP_BUILD = Path("bootstrap/build.gradle.kts")
MANIFEST_ROOT = Path("runtime/src/main/java/dev/turboism/mapping/verification")
TEMPLATE_ROOT = Path("scripts/verification-sources/templates")

RECORD_LITERAL = re.compile(r'"(cubism-\d+\.\d+\.\d+-[^"]+\.json)"')
VERIFICATION_ID_LITERAL = re.compile(r'"([a-z0-9][a-z0-9._-]*\.static)"')
TEMPLATE_RECORD_PIN = re.compile(
    r"\$\{record:(cubism-\d+\.\d+\.\d+-[^}:]+\.json):verificationId\}"
)
RECORD_FILENAME = re.compile(r"^cubism-(\d+\.\d+\.\d+)-.+\.json$")


class IndexError_(Exception):
    """Raised for unreadable or unparseable inputs."""


def _read(path: Path) -> str:
    try:
        return path.read_text(encoding="utf-8")
    except OSError as exc:
        raise IndexError_(f"unable to read {path}: {exc}") from exc


def packaged_records(root: Path) -> list[str]:
    return RECORD_LITERAL.findall(_read(root / BOOTSTRAP_BUILD))


def on_disk_records(root: Path) -> list[str]:
    base = root / VERIFICATION_DIR
    return sorted(path.name for path in base.glob("*.json"))


def record_ids(root: Path) -> dict[str, str]:
    """Maps verificationId -> record filename for every record on disk."""
    ids: dict[str, str] = {}
    problems = []
    for name in on_disk_records(root):
        path = root / VERIFICATION_DIR / name
        try:
            document = json.loads(_read(path))
        except json.JSONDecodeError as exc:
            raise IndexError_(f"{name}: invalid JSON: {exc}") from exc
        verification_id = document.get("verificationId")
        if not isinstance(verification_id, str) or not verification_id:
            problems.append(f"{name}: missing verificationId")
            continue
        if verification_id in ids:
            problems.append(
                f"duplicate verificationId {verification_id!r}: "
                f"{ids[verification_id]} and {name}"
            )
            continue
        ids[verification_id] = name
        filename_match = RECORD_FILENAME.match(name)
        if filename_match and filename_match.group(1) not in verification_id:
            problems.append(
                f"{name}: verificationId {verification_id!r} does not carry "
                f"the filename version {filename_match.group(1)}"
            )
    if problems:
        raise IndexError_("; ".join(problems))
    return ids


def manifest_verification_ids(root: Path) -> dict[str, str]:
    """Maps each ``*.static`` id pinned in manifest sources or generated
    templates to its declaring file.

    Checked-in sources pin ``verificationId`` string literals directly; the
    generated targets' templates pin them through ``${record:<file>:
    verificationId}`` placeholders, which resolve through the record filename.
    """
    ids: dict[str, str] = {}
    base = root / MANIFEST_ROOT
    if base.is_dir():
        for source in sorted(base.rglob("*.java")):
            for literal in VERIFICATION_ID_LITERAL.findall(_read(source)):
                ids.setdefault(literal, source.name)
    template_base = root / TEMPLATE_ROOT
    if template_base.is_dir():
        name_to_id = {name: vid for vid, name in record_ids(root).items()}
        for source in sorted(template_base.rglob("*.java")):
            for record_name in TEMPLATE_RECORD_PIN.findall(_read(source)):
                if record_name in name_to_id:
                    ids.setdefault(
                        name_to_id[record_name],
                        f"{source.name} (via ${{record:{record_name}}})",
                    )
    return ids


def template_record_pins(root: Path) -> dict[str, str]:
    """Maps record filenames referenced by template placeholders to the file."""
    pins: dict[str, str] = {}
    template_base = root / TEMPLATE_ROOT
    if template_base.is_dir():
        for source in sorted(template_base.rglob("*.java")):
            for record_name in TEMPLATE_RECORD_PIN.findall(_read(source)):
                pins.setdefault(record_name, source.name)
    return pins


def check(root: Path) -> list[str]:
    violations = []
    packaged = packaged_records(root)
    on_disk = on_disk_records(root)
    packaged_set = set(packaged)
    if len(packaged) != len(packaged_set):
        seen = set()
        for name in packaged:
            if name in seen:
                violations.append(f"{BOOTSTRAP_BUILD}: duplicate record entry {name}")
            seen.add(name)
    for name in sorted(set(on_disk) - packaged_set):
        violations.append(
            f"{BOOTSTRAP_BUILD}: verification record {name} is not packaged; "
            "the agent would fail closed only at host start"
        )
    for name in sorted(packaged_set - set(on_disk)):
        violations.append(
            f"{BOOTSTRAP_BUILD}: packages {name} but no such record exists"
        )

    ids = record_ids(root)
    manifest_ids = manifest_verification_ids(root)
    if not manifest_ids:
        violations.append(
            f"{MANIFEST_ROOT}: no verification id literals found; "
            "the manifest-pin cross-check would be vacuous"
        )
    for verification_id, source in sorted(manifest_ids.items()):
        if verification_id not in ids:
            violations.append(
                f"{source}: pins verificationId {verification_id!r} with no "
                "matching record under compatibility/cubism/verification/"
            )
    for record_name, source in sorted(template_record_pins(root).items()):
        if record_name not in ids.values():
            violations.append(
                f"{source}: placeholder pins missing record {record_name}"
            )
    return violations


def run(root: Path, render: bool) -> int:
    if render:
        for name in on_disk_records(root):
            print(name)
        return 0
    try:
        violations = check(root)
    except IndexError_ as exc:
        print(f"FAIL: {exc}", file=sys.stderr)
        return 2
    for violation in violations:
        print(f"FAIL: {violation}")
    if violations:
        print(f"\n{len(violations)} verification index finding(s)")
        return 1
    print("PASS: packaged verification records match the repository and "
          "manifest pins")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("root", nargs="?", default=str(ROOT))
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--check", action="store_true", help="verify (default)")
    mode.add_argument(
        "--render", action="store_true",
        help="print the derived packaging list instead of checking",
    )
    args = parser.parse_args()
    return run(Path(args.root).resolve(), args.render)


if __name__ == "__main__":
    raise SystemExit(main())
