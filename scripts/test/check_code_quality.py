#!/usr/bin/env python3
"""Reject undocumented public API, duplicated host digests, and retired naming.

Four rules, all fail-closed:

1. Every public type and every non-``@Override`` public method in the production roots carries
   Javadoc. ``@Override`` implementations inherit their supertype documentation and are exempt.
2. The reviewed Cubism digests appear only in their single production declaration and its guard
   test. A second copy can drift from the reviewed record and silently widen admission.
3. No production type name encodes a Cubism version. Versions are declared as data so that no
   type can quietly mean "the other version".
4. No retired governance token (``m14``/``m15``) survives in ``cubism-ref/`` asset filenames.

Usage: check_code_quality.py [repo-root] [--rules RULE[,RULE...]] [--report]
"""
from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

PRODUCTION_ROOTS = (
    "sdk/src/main/java",
    "runtime/src/main/java",
    "bootstrap/src/main/java",
)
PLUGIN_ROOT = "plugins"

REVIEWED_DIGESTS = (
    "bcc6e34f448be33d8964f2e17f4eb7fd3780e4a9b7f60525da377c9f35d2b3dd",
    "988ef6a8b5fede84bd43c6dc3a9a045d9a6a974986c3f49fb6f567ccf8c84f21",
)
DIGEST_DECLARATION_SITES = (
    "runtime/src/main/java/dev/turboism/mapping/verification/ReviewedHostArtifacts.java",
    "runtime/src/test/java/dev/turboism/mapping/verification/ReviewedHostArtifactsTest.java",
)
DIGEST_SCAN_ROOTS = PRODUCTION_ROOTS + (
    PLUGIN_ROOT,
    "runtime/src/test/java",
    "bootstrap/src/test/java",
    "tests/src/test/java",
)

RETIRED_ASSET_TOKENS = ("m14", "m15")
ASSET_ROOT = "cubism-ref"

# Grandfathered: these two names are frozen inside hash-anchored reviewed records. The retired
# token also appears in each pack's `semanticName` values, which are bound bidirectionally to the
# `mappingId` values in the reviewed verification records, whose bytes are pinned by SHA-256 in
# the runtime trust roots. Renaming them would require re-issuing those reviewed records with new
# digests -- a governance action that breaks the audit chain for a cosmetic gain. The rule's
# purpose is to stop NEW retired-governance names from appearing.
GRANDFATHERED_ASSETS = (
    "cubism-ref/mapping-packs/draft/cubism-5.3.02-m14-project-workspace.json",
    "cubism-ref/mapping-packs/draft/cubism-5.3.02-m15-clipmask.json",
)

TYPE_DECLARATION = re.compile(
    r"^public\s+(?:final\s+|abstract\s+|sealed\s+|non-sealed\s+|static\s+)*"
    r"(?:class|interface|record|enum)\s+(\w+)"
)
METHOD_DECLARATION = re.compile(r"^public\s+[\w<>\[\],\s.?]+\s+(\w+)\s*\(")
VERSION_SUFFIXED_TYPE = re.compile(r"^\w+(?:52|53|5203|5302)$")

ALL_RULES = ("javadoc", "digests", "naming", "assets")

# Ratchet for the Javadoc backlog. The other three rules are already at zero and are enforced
# absolutely. Javadoc is enforced as a maximum instead: new undocumented public API is blocked
# immediately, while the existing backlog burns down. Lower this number as batches land; the
# checker refuses to let it drift upward, and tells you to lower it when you go below.
JAVADOC_BACKLOG_MAXIMUM = 1132


def java_sources(root: Path, relative: str) -> list[Path]:
    base = root / relative
    if not base.exists():
        return []
    if relative == PLUGIN_ROOT:
        return sorted(p for p in base.rglob("*.java") if "/src/main/" in p.as_posix())
    return sorted(base.rglob("*.java"))


def documented(lines: list[str], index: int) -> tuple[bool, bool]:
    """Returns (has_javadoc, is_override) for a declaration at ``index``."""
    cursor = index - 1
    is_override = False
    while cursor >= 0:
        stripped = lines[cursor].strip()
        if stripped.startswith("@"):
            is_override = is_override or stripped.startswith("@Override")
            cursor -= 1
            continue
        if not stripped:
            cursor -= 1
            continue
        break
    return (cursor >= 0 and lines[cursor].strip().endswith("*/")), is_override


def check_javadoc(root: Path) -> list[str]:
    failures = []
    for relative in PRODUCTION_ROOTS + (PLUGIN_ROOT,):
        for source in java_sources(root, relative):
            if source.name == "package-info.java":
                continue
            display = source.relative_to(root).as_posix()
            lines = source.read_text(encoding="utf-8").split("\n")
            for index, line in enumerate(lines):
                stripped = line.strip()
                indent = len(line) - len(line.lstrip())
                type_match = TYPE_DECLARATION.match(stripped)
                if type_match and indent == 0:
                    has_doc, _ = documented(lines, index)
                    if not has_doc:
                        failures.append(
                            f"undocumented public type {type_match.group(1)}: {display}:{index + 1}"
                        )
                    continue
                method_match = METHOD_DECLARATION.match(stripped)
                if method_match and " class " not in stripped and " interface " not in stripped:
                    has_doc, is_override = documented(lines, index)
                    if not has_doc and not is_override:
                        failures.append(
                            f"undocumented public method {method_match.group(1)}: "
                            f"{display}:{index + 1}"
                        )
    return failures


def check_digests(root: Path) -> list[str]:
    allowed = {(root / site).resolve() for site in DIGEST_DECLARATION_SITES}
    failures = []
    for relative in DIGEST_SCAN_ROOTS:
        for source in java_sources(root, relative):
            if source.resolve() in allowed:
                continue
            text = source.read_text(encoding="utf-8")
            for digest in REVIEWED_DIGESTS:
                if digest in text:
                    failures.append(
                        "reviewed host digest restated outside ReviewedHostArtifacts: "
                        f"{source.relative_to(root).as_posix()}"
                    )
                    break
    return failures


def check_naming(root: Path) -> list[str]:
    failures = []
    for relative in PRODUCTION_ROOTS + (PLUGIN_ROOT,):
        for source in java_sources(root, relative):
            if VERSION_SUFFIXED_TYPE.match(source.stem):
                failures.append(
                    "production type name encodes a Cubism version: "
                    f"{source.relative_to(root).as_posix()}"
                )
    return failures


def check_assets(root: Path) -> list[str]:
    base = root / ASSET_ROOT
    if not base.exists():
        return []
    failures = []
    for asset in sorted(base.rglob("*.json")):
        relative = asset.relative_to(root).as_posix()
        if relative in GRANDFATHERED_ASSETS:
            continue
        parts = asset.stem.split("-")
        if any(token in parts for token in RETIRED_ASSET_TOKENS):
            failures.append(f"retired governance token in asset name: {relative}")
    return failures


CHECKS = {
    "javadoc": check_javadoc,
    "digests": check_digests,
    "naming": check_naming,
    "assets": check_assets,
}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("root", nargs="?", default=".")
    parser.add_argument(
        "--rules",
        default=",".join(ALL_RULES),
        help=f"comma-separated subset of: {', '.join(ALL_RULES)}",
    )
    parser.add_argument(
        "--report",
        action="store_true",
        help="print per-rule counts and exit 0 (use while a rule is still being closed)",
    )
    parser.add_argument(
        "--ratchet",
        action="store_true",
        help=(
            "enforce javadoc as a maximum instead of zero, so new undocumented public API fails "
            "while the existing backlog burns down"
        ),
    )
    args = parser.parse_args()

    root = Path(args.root).resolve()
    selected = [rule.strip() for rule in args.rules.split(",") if rule.strip()]
    unknown = [rule for rule in selected if rule not in CHECKS]
    if unknown:
        print(f"FAIL: unknown rule(s): {', '.join(unknown)}", file=sys.stderr)
        return 2

    results = {rule: CHECKS[rule](root) for rule in selected}

    if args.report:
        for rule, failures in results.items():
            print(f"{rule}: {len(failures)} finding(s)")
        return 0

    if args.ratchet and "javadoc" in results:
        return _ratchet(results, selected)

    failures = [failure for rule in selected for failure in results[rule]]
    if failures:
        for failure in failures:
            print(f"FAIL: {failure}")
        print(f"\n{len(failures)} code-quality finding(s)")
        return 1

    print(f"PASS: code quality clean for rules: {', '.join(selected)}")
    return 0


def _ratchet(results: dict[str, list[str]], selected: list[str]) -> int:
    """Enforces javadoc as a non-increasing maximum and the other rules absolutely."""
    javadoc = results["javadoc"]
    strict = [failure for rule in selected if rule != "javadoc" for failure in results[rule]]

    for failure in strict:
        print(f"FAIL: {failure}")

    if len(javadoc) > JAVADOC_BACKLOG_MAXIMUM:
        added = len(javadoc) - JAVADOC_BACKLOG_MAXIMUM
        print(
            f"FAIL: {added} new undocumented public API item(s): "
            f"{len(javadoc)} findings exceeds the recorded backlog of {JAVADOC_BACKLOG_MAXIMUM}"
        )
        for failure in javadoc[:20]:
            print(f"  {failure}")
        return 1

    if strict:
        print(f"\n{len(strict)} code-quality finding(s)")
        return 1

    if len(javadoc) < JAVADOC_BACKLOG_MAXIMUM:
        print(
            f"FAIL: javadoc backlog is down to {len(javadoc)}; lower "
            f"JAVADOC_BACKLOG_MAXIMUM to {len(javadoc)} so the ratchet keeps holding"
        )
        return 1

    print(
        f"PASS: code quality clean; javadoc backlog holding at {JAVADOC_BACKLOG_MAXIMUM} "
        "with no new undocumented public API"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
