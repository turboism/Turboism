#!/usr/bin/env python3
"""Check resolver-consumed Editor-model aliases against reviewed records.

The checker scans every runtime production Java source for aliases passed to VerifiedMemberResolver
operations. It follows simple string constants and expands the finite preset-color alias builder.
Implementation aliases not declared by either reviewed record fail hard. Reviewed method aliases
not consumed by production also fail: reviewed admission must be no wider than actual use.

Usage: check_editor_model_aliases.py [repo-root] [--report]
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

IMPLEMENTATION_ROOT = "runtime/src/main/java"
RECORDS = (
    "cubism-ref/verification/cubism-5.2.03-editor-model.json",
    "cubism-ref/verification/cubism-5.3.02-editor-model.json",
)
ALIAS_PREFIX = "cubism.editor-model"
ALIAS_LITERAL = re.compile(r'"(' + re.escape(ALIAS_PREFIX) + r'[^\"]*)"')
VERIFICATION_PATH = "/mapping/verification/"
PRESET_ALIAS_PREFIX = "cubism.editor-model.label-color-type."
PRESET_COLORS = ("red", "orange", "yellow", "green", "blue", "purple", "gray")
UNUSED_ALIAS_MAXIMUM = 0


def implementation_aliases(root: Path) -> set[str]:
    """Return production aliases, excluding contracts/manifests that merely declare evidence."""
    aliases: set[str] = set()
    base = root / IMPLEMENTATION_ROOT
    if not base.exists():
        return aliases
    for source in sorted(base.rglob("*.java")):
        if VERIFICATION_PATH in source.as_posix():
            continue
        text = source.read_text(encoding="utf-8")
        for alias in ALIAS_LITERAL.findall(text):
            if alias.endswith(".") or alias.endswith(".class"):
                continue
            aliases.add(alias)
        if "presetAlias(" in text and PRESET_ALIAS_PREFIX in text:
            aliases.update(PRESET_ALIAS_PREFIX + color for color in PRESET_COLORS)
    return aliases


def record_aliases(root: Path) -> tuple[set[str], dict[str, set[str]]]:
    """Return all non-class aliases declared by the reviewed Editor-model records."""
    union: set[str] = set()
    per_record: dict[str, set[str]] = {}
    for relative in RECORDS:
        path = root / relative
        if not path.exists():
            continue
        data = json.loads(path.read_text(encoding="utf-8"))
        aliases = {
            selector["alias"]
            for selector in data["selectors"]
            if selector["alias"].startswith(ALIAS_PREFIX) and selector["kind"] != "class"
        }
        per_record[Path(relative).name] = aliases
        union |= aliases
    return union, per_record


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("root", nargs="?", default=".")
    parser.add_argument("--report", action="store_true", help="print both comparison directions")
    args = parser.parse_args()

    root = Path(args.root).resolve()
    implementation = implementation_aliases(root)
    union, per_record = record_aliases(root)
    if not implementation or not union:
        print("FAIL: could not read the implementation or reviewed records", file=sys.stderr)
        return 2

    unrecorded = sorted(implementation - union)
    unused = sorted(union - implementation)
    if args.report:
        print(f"implementation aliases : {len(implementation)}")
        for name, aliases in per_record.items():
            print(f"  {name}: {len(aliases)}")
        print(f"unrecorded (must be 0) : {len(unrecorded)}")
        for alias in unrecorded:
            print(f"    {alias}")
        print(f"unused (maximum {UNUSED_ALIAS_MAXIMUM}) : {len(unused)}")
        for alias in unused:
            print(f"    {alias}")
        return 0

    failed = False
    if unrecorded:
        failed = True
        print(f"FAIL: {len(unrecorded)} invoked Editor-model aliases lack reviewed evidence:")
        for alias in unrecorded:
            print(f"  {alias}")
    if len(unused) != UNUSED_ALIAS_MAXIMUM:
        failed = True
        print(f"FAIL: {len(unused)} reviewed Editor-model aliases are unused; expected 0:")
        for alias in unused:
            print(f"  {alias}")
    if failed:
        return 1
    print(f"PASS: {len(implementation)} consumed Editor-model aliases exactly match reviewed admission")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
