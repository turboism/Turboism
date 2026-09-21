#!/usr/bin/env python3
"""Check resolver-consumed Editor-model aliases against reviewed records.

The checker scans every runtime production Java source for aliases passed to VerifiedMemberResolver
operations. It follows simple string constants and expands the finite preset-color alias builder.
Implementation aliases not declared by the older exact-use records or the fixed additive subset of
the public 5.3.03 reviewed record fail hard. Admitted method aliases not consumed by production also
fail: reviewed admission must be no wider than actual use.

Usage: check_editor_model_aliases.py [repo-root] [--report]
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

IMPLEMENTATION_ROOT = "runtime/src/main/java"
BASE_RECORDS = (
    "compatibility/cubism/verification/cubism-5.2.03-editor-model.json",
    "compatibility/cubism/verification/cubism-5.3.02-editor-model.json",
)
ADDITIVE_RECORD = "compatibility/cubism/verification/cubism-5.3.03-editor-model.json"
ADDITIVE_ALIASES = frozenset({
    "cubism.editor-model.keyform-grid.keyforms-on-grid",
    "cubism.editor-model.keyform-on-grid.form-guid",
    "cubism.editor-model.undo.local-simple-factory-create",
    "cubism.editor-model.undo.local-simple-factory-instance",
})
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


def aliases_in_record(root: Path, relative: str) -> set[str]:
    """Return non-class Editor-model aliases in one public reviewed record."""
    path = root / relative
    if not path.exists():
        return set()
    data = json.loads(path.read_text(encoding="utf-8"))
    return {
        selector["alias"]
        for selector in data["selectors"]
        if selector["alias"].startswith(ALIAS_PREFIX) and selector["kind"] != "class"
    }


def record_aliases(root: Path) -> tuple[set[str], dict[str, set[str]]]:
    """Return exact consumed admission from the public reviewed records.

    The 5.3.03 record is a full static host inventory and intentionally contains selectors for
    capabilities this runtime does not consume. Only its four additive, invoked aliases extend the
    exact-use admission held by the 5.2.03/5.3.02 records. The subset is fixed here so adding an
    arbitrary selector to any record cannot silently widen runtime admission.
    """
    union: set[str] = set()
    per_record: dict[str, set[str]] = {}
    for relative in BASE_RECORDS:
        aliases = aliases_in_record(root, relative)
        if aliases:
            per_record[Path(relative).name] = aliases
            union |= aliases

    additive_record = aliases_in_record(root, ADDITIVE_RECORD)
    missing = ADDITIVE_ALIASES - additive_record
    if missing:
        raise ValueError(
            "5.3.03 reviewed record lacks required additive aliases: "
            + ", ".join(sorted(missing))
        )
    unexpected_base = ADDITIVE_ALIASES & union
    if unexpected_base:
        raise ValueError(
            "5.3.03-only aliases unexpectedly appear in older records: "
            + ", ".join(sorted(unexpected_base))
        )
    per_record[Path(ADDITIVE_RECORD).name + " (admitted subset)"] = set(ADDITIVE_ALIASES)
    union |= ADDITIVE_ALIASES
    return union, per_record


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("root", nargs="?", default=".")
    parser.add_argument("--report", action="store_true", help="print both comparison directions")
    args = parser.parse_args()

    root = Path(args.root).resolve()
    implementation = implementation_aliases(root)
    try:
        union, per_record = record_aliases(root)
    except (KeyError, TypeError, ValueError, json.JSONDecodeError) as failure:
        print(f"FAIL: invalid reviewed Editor-model record: {failure}", file=sys.stderr)
        return 2
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
