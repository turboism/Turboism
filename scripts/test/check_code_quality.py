#!/usr/bin/env python3
"""Reject undocumented public API, duplicated host digests, and retired naming.

Four rules, all fail-closed:

1. Every publicly reachable type and every non-``@Override`` public method in the production
   roots carries Javadoc, decided by the JDK compiler tree API (``JavacTask.parse`` +
   ``DocTrees``) rather than line patterns: interface-implicit ``public``/``default``/``static``
   methods and nested public types count, ordinary block comments and string literals do not,
   and ``@Override`` implementations inherit their supertype documentation and are exempt.
   The scan needs a full JDK 17 toolchain and fails closed when one is unavailable or a
   source file cannot be parsed.
2. The reviewed Cubism digests appear only in their single production declaration and its guard
   test. A second copy can drift from the reviewed record and silently widen admission.
3. No production type name encodes a Cubism version. Versions are declared as data so that no
   type can quietly mean "the other version".
4. No retired governance token (``m14``/``m15``) survives in ``compatibility/cubism/`` asset filenames.

Usage: check_code_quality.py [repo-root] [--rules RULE[,RULE...]] [--report]
"""
from __future__ import annotations

import argparse
import os
import re
import shutil
import subprocess
import sys
import tempfile
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
    "bd0a23b9f21a56271d31e6f7f5aed0202661c4fe12444469d093bcdeb4cbf166",
)
DIGEST_DECLARATION_SITES = (
    "runtime/src/main/java/dev/turboism/mapping/verification/ReviewedHostArtifacts.java",
    "runtime/src/test/java/dev/turboism/mapping/verification/ReviewedHostArtifactsTest.java",
    # P1 published the 5.3.03 public records before the production trust root existed; these
    # agreement tests pin those already-reviewed public bytes and are the repository's allowed
    # test locations for the same exact identity.
    "testing/integration-tests/src/test/java/dev/turboism/tests/mapping/MappingPackDraftImportTest.java",
    "testing/integration-tests/src/test/java/dev/turboism/tests/mapping/StaticVerificationRecordRepositoryTest.java",
)
DIGEST_SCAN_ROOTS = PRODUCTION_ROOTS + (
    PLUGIN_ROOT,
    "runtime/src/test/java",
    "bootstrap/src/test/java",
    "testing/integration-tests/src/test/java",
)

RETIRED_ASSET_TOKENS = ("m14", "m15")
ASSET_ROOT = "compatibility/cubism"

# Grandfathered: these two names are frozen inside hash-anchored reviewed records. The retired
# token also appears in each pack's `semanticName` values, which are bound bidirectionally to the
# `mappingId` values in the reviewed verification records, whose bytes are pinned by SHA-256 in
# the runtime trust roots. Renaming them would require re-issuing those reviewed records with new
# digests -- a governance action that breaks the audit chain for a cosmetic gain. The rule's
# purpose is to stop NEW retired-governance names from appearing.
GRANDFATHERED_ASSETS = (
    "compatibility/cubism/mapping-packs/draft/cubism-5.3.02-m14-project-workspace.json",
    "compatibility/cubism/mapping-packs/draft/cubism-5.3.02-m15-clipmask.json",
)

# A Cubism version fused into a type name appears as a digit run that starts with the major
# version 5: "52"/"53" encode major.minor, "520" a single-digit patch, "5203"/"5302"/"5303"
# a two-digit patch. Any maximal digit run of shape 5X, 5XY or 5XYY is a version token no
# matter where it sits in the name; unrelated digit runs (Point2, M12ReadSnapshotSource,
# Utf8PluginCatalog) do not start with 5 and stay legal.
CUBISM_VERSION_TOKEN = re.compile(r"(?<!\d)5\d{1,3}(?!\d)")

ALL_RULES = ("javadoc", "digests", "naming", "assets")

# The Javadoc backlog is closed. The ratchet that carried it down from 1253 is kept because it is
# the mechanism that got here and is cheap to re-arm, but the maximum is now zero, so every rule
# is enforced absolutely: any undocumented public type or non-@Override public method fails.
JAVADOC_BACKLOG_MAXIMUM = 0


def java_sources(root: Path, relative: str) -> list[Path]:
    base = root / relative
    if not base.exists():
        return []
    if relative == PLUGIN_ROOT:
        return sorted(p for p in base.rglob("*.java") if "/src/main/" in p.as_posix())
    return sorted(base.rglob("*.java"))


class QualityToolError(Exception):
    """A required verification tool could not produce a trustworthy result."""


# The Javadoc rule is enforced by scripts/test/java/JavadocPublicApiScan.java, a JDK-only
# helper run through the JDK 17 toolchain resolved below. It is build tooling, never a
# product dependency.
JAVADOC_HELPER = Path(__file__).resolve().parent / "java" / "JavadocPublicApiScan.java"
JAVADOC_HELPER_MAIN = "JavadocPublicApiScan"
JAVADOC_HELPER_TIMEOUT_SECONDS = 600
JAVADOC_COMPILE_TIMEOUT_SECONDS = 180


def _jdk_home() -> Path:
    """Resolves the JDK 17+ home used for the Javadoc scan; prefers the toolchain Gradle wires in."""
    for variable in ("TURBOISM_QUALITY_JAVA_HOME", "JAVA17_HOME", "JAVA_HOME"):
        candidate = os.environ.get(variable)
        if candidate and (Path(candidate) / "bin" / "javac").is_file():
            return Path(candidate)
    javac = shutil.which("javac")
    if javac:
        return Path(javac).resolve().parent.parent
    raise QualityToolError(
        "javadoc rule requires a JDK 17 toolchain with javac; set "
        "TURBOISM_QUALITY_JAVA_HOME or JAVA_HOME, or put javac on PATH"
    )


def _javadoc_findings(root: Path, sources: list[Path]) -> list[tuple[str, str, str, str]]:
    """Compiles the helper once, parses every source in one batch, returns (kind, name, path, line)."""
    home = _jdk_home()
    javac = home / "bin" / "javac"
    java = home / "bin" / "java"
    if not java.is_file():
        raise QualityToolError(f"javadoc rule requires a complete JDK; {java} is missing")
    try:
        with tempfile.TemporaryDirectory(prefix="turboism-quality-javadoc-") as work:
            workdir = Path(work)
            classes = workdir / "classes"
            classes.mkdir()
            compile_result = subprocess.run(
                [
                    str(javac), "--release", "17", "-encoding", "UTF-8",
                    "-d", str(classes), str(JAVADOC_HELPER),
                ],
                capture_output=True,
                text=True,
                timeout=JAVADOC_COMPILE_TIMEOUT_SECONDS,
            )
            if compile_result.returncode != 0:
                raise QualityToolError(
                    f"javadoc helper failed to compile with {javac} "
                    f"(exit {compile_result.returncode}): "
                    f"{(compile_result.stderr or compile_result.stdout).strip()}"
                )
            listing = workdir / "sources.txt"
            listing.write_text(
                "".join(
                    f"{source.relative_to(root).as_posix()}\t{source}\n"
                    for source in sources
                ),
                encoding="utf-8",
            )
            run = subprocess.run(
                [str(java), "-cp", str(classes), JAVADOC_HELPER_MAIN, str(listing)],
                capture_output=True,
                text=True,
                timeout=JAVADOC_HELPER_TIMEOUT_SECONDS,
            )
    except (OSError, subprocess.TimeoutExpired) as failure:
        raise QualityToolError(f"javadoc helper could not run: {failure}") from failure
    if run.returncode != 0:
        raise QualityToolError(
            f"javadoc scan failed (helper exit {run.returncode}): "
            f"{(run.stderr or run.stdout).strip()}"
        )
    findings = []
    for line in run.stdout.splitlines():
        fields = line.split("\t")
        if len(fields) != 5 or fields[0] != "FINDING":
            raise QualityToolError(f"javadoc helper produced unrecognised output: {line!r}")
        findings.append((fields[1], fields[2], fields[3], fields[4]))
    return findings


def check_javadoc(root: Path) -> list[str]:
    sources = [
        source
        for relative in PRODUCTION_ROOTS + (PLUGIN_ROOT,)
        for source in java_sources(root, relative)
        if source.name != "package-info.java"
    ]
    return [
        f"undocumented public {kind} {name}: {display}:{line}"
        for kind, name, display, line in _javadoc_findings(root, sources)
    ]


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
            if CUBISM_VERSION_TOKEN.search(source.stem):
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
    parser.add_argument(
        "--backlog-maximum",
        type=int,
        default=JAVADOC_BACKLOG_MAXIMUM,
        help=(
            "override the recorded javadoc maximum; exists so the self-test can exercise both "
            "ratchet branches without depending on the production constant's current value"
        ),
    )
    args = parser.parse_args()

    root = Path(args.root).resolve()
    selected = [rule.strip() for rule in args.rules.split(",") if rule.strip()]
    unknown = [rule for rule in selected if rule not in CHECKS]
    if unknown:
        print(f"FAIL: unknown rule(s): {', '.join(unknown)}", file=sys.stderr)
        return 2

    try:
        results = {rule: CHECKS[rule](root) for rule in selected}
    except QualityToolError as error:
        print(f"FAIL: {error}", file=sys.stderr)
        return 2

    if args.report:
        for rule, failures in results.items():
            print(f"{rule}: {len(failures)} finding(s)")
        return 0

    if args.ratchet and "javadoc" in results:
        return _ratchet(results, selected, args.backlog_maximum)

    failures = [failure for rule in selected for failure in results[rule]]
    if failures:
        for failure in failures:
            print(f"FAIL: {failure}")
        print(f"\n{len(failures)} code-quality finding(s)")
        return 1

    print(f"PASS: code quality clean for rules: {', '.join(selected)}")
    return 0


def _ratchet(results: dict[str, list[str]], selected: list[str], maximum: int) -> int:
    """Enforces javadoc as a non-increasing maximum and the other rules absolutely."""
    javadoc = results["javadoc"]
    strict = [failure for rule in selected if rule != "javadoc" for failure in results[rule]]

    for failure in strict:
        print(f"FAIL: {failure}")

    if len(javadoc) > maximum:
        added = len(javadoc) - maximum
        print(
            f"FAIL: {added} new undocumented public API item(s): "
            f"{len(javadoc)} findings exceeds the recorded backlog of {maximum}"
        )
        for failure in javadoc[:20]:
            print(f"  {failure}")
        return 1

    if strict:
        print(f"\n{len(strict)} code-quality finding(s)")
        return 1

    if len(javadoc) < maximum:
        print(
            f"FAIL: javadoc backlog is down to {len(javadoc)}; lower "
            f"JAVADOC_BACKLOG_MAXIMUM to {len(javadoc)} so the ratchet keeps holding"
        )
        return 1

    print(
        f"PASS: code quality clean; javadoc backlog holding at {maximum} "
        "with no new undocumented public API"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
