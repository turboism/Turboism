#!/usr/bin/env python3
"""Check the Editor-model implementation's selector aliases against the reviewed records.

Every other capability family declares the aliases it uses as constants and exposes them through
``Verified*HostOperations.methodAliasesUsed()``, so a test can compare implementation against
record directly. The Editor-model family cannot: its ~464 aliases are inline string literals
spread across 23 ``Editor*Access`` classes, with no constant to reference. The repository test
worked around that with a hand-maintained list, which drifted until it matched neither side --
293 entries against 464 in the implementation and 494 in the record -- and therefore asserted
nothing. This derives both sides instead.

Two directions, deliberately not symmetric:

*Unrecorded* -- the implementation invokes an alias that no reviewed record declares. This fails
at runtime with a resolution error, so it is a hard failure and the count must stay zero.

*Unused* -- a reviewed record declares an alias the implementation never invokes. Admission is
wider than it needs to be. That is hygiene, not a safety hole, so it is enforced as a
non-increasing maximum: it can never grow, and shrinking it requires lowering the recorded
number, exactly like the Javadoc ratchet.

Usage: check_editor_model_aliases.py [repo-root] [--report]
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

IMPLEMENTATION_ROOT = "runtime/src/main/java/dev/turboism/adapter/cubism"
RECORDS = (
    "cubism-ref/verification/cubism-5.2.03-editor-model.json",
    "cubism-ref/verification/cubism-5.3.02-editor-model.json",
)
ALIAS_PREFIX = "cubism.editor-model"
ALIAS = re.compile(r'"(' + re.escape(ALIAS_PREFIX) + r'[^"]*)"')

# Admission wider than the implementation needs. Lower this as selectors are either used or
# removed from the reviewed records; the checker refuses to let it grow.
UNUSED_ALIAS_MAXIMUM = 19


def implementation_aliases(root: Path) -> set[str]:
    """Alias literals the implementation invokes, excluding class-kind type checks.

    Class aliases are matched by suffix rather than by consulting the record, so that a class
    alias the records do not declare is still excluded here and reported by the record side
    instead of being mistaken for an invoke.
    """
    aliases: set[str] = set()
    base = root / IMPLEMENTATION_ROOT
    if not base.exists():
        return aliases
    for source in sorted(base.rglob("*.java")):
        for alias in ALIAS.findall(source.read_text(encoding="utf-8")):
            # A literal ending in '.' is a concatenation prefix, not a whole alias.
            if alias.endswith(".") or alias.endswith(".class"):
                continue
            aliases.add(alias)
    return aliases


def record_aliases(root: Path) -> tuple[set[str], dict[str, set[str]]]:
    """Method-kind aliases only.

    Class-kind selectors are consumed through ``isInstance`` type checks rather than ``invoke``,
    and several are reached only via a resolver helper, so counting them here would report
    permanently-unused entries that are in fact used. The invoke-vs-declare question this checker
    answers is about method selectors.
    """
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
    parser.add_argument(
        "--report",
        action="store_true",
        help="print both directions and exit 0, including the full unused list",
    )
    args = parser.parse_args()

    root = Path(args.root).resolve()
    implementation = implementation_aliases(root)
    union, per_record = record_aliases(root)

    if not implementation or not union:
        print("FAIL: could not read the implementation or the reviewed records", file=sys.stderr)
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
        print(
            f"FAIL: {len(unrecorded)} Editor-model alias(es) are invoked but declared by no "
            "reviewed record; these fail at runtime:"
        )
        for alias in unrecorded:
            print(f"  {alias}")

    if len(unused) > UNUSED_ALIAS_MAXIMUM:
        failed = True
        print(
            f"FAIL: {len(unused)} reviewed Editor-model alias(es) are unused, above the recorded "
            f"maximum of {UNUSED_ALIAS_MAXIMUM}; admission must not widen further:"
        )
        for alias in unused[:20]:
            print(f"  {alias}")
    elif len(unused) < UNUSED_ALIAS_MAXIMUM:
        failed = True
        print(
            f"FAIL: unused Editor-model aliases are down to {len(unused)}; lower "
            f"UNUSED_ALIAS_MAXIMUM to {len(unused)} so the ratchet keeps holding"
        )

    if failed:
        return 1

    print(
        f"PASS: {len(implementation)} Editor-model aliases all declared by a reviewed record; "
        f"unused holding at {UNUSED_ALIAS_MAXIMUM}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
