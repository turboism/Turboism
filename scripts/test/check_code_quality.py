#!/usr/bin/env python3
"""Reject undocumented public API, duplicated host digests, retired naming, direct EDT waits, and
literal control characters.

Six rules, all fail-closed:

1. Every publicly reachable type and every non-``@Override`` public method in the production
   roots carries Javadoc, decided by the JDK compiler tree API (``JavacTask.parse`` +
   ``DocTrees``) rather than line patterns: interface-implicit ``public``/``default``/``static``
   methods and nested public types count, ordinary block comments and string literals do not,
   and ``@Override`` implementations inherit their supertype documentation and are exempt.
   The scan needs a full JDK 17 toolchain and fails closed when one is unavailable or a
   source file cannot be parsed.
2. The reviewed Cubism digests appear only in their single production declaration and its guard
   test. A second copy can drift from the reviewed record and silently widen admission.
3. No production type name (file name or declared class/interface/enum/record name)
   encodes a Cubism version. Versions are declared as data so that no type can quietly
   mean "the other version".
4. No retired governance token (``m14``/``m15``) survives in ``compatibility/cubism/`` asset filenames.
5. Runtime production code never calls ``invokeAndWait`` directly: synchronous EDT handoffs go
   through ``EdtDispatch`` so an interrupted or timed-out caller can never leave a queued
   mutation behind to run after the caller already reported failure. The token is banned
   outright (comments included) except inside ``EdtDispatch`` itself.
6. No literal C0 control character (bytes other than ``\\t``, ``\\n``, ``\\r``) appears in a
   scanned source file. A raw control byte in a string literal is invisible in review and
   diffs; spell it as an escape sequence instead.

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
    "core-contract/src/main/java",
    "event-processor/src/main/java",
    "graal-host/src/main/java",
    # Generated verification manifests/contracts are authored as templates here;
    # scanning them keeps Javadoc and naming coverage on the production source
    # of truth instead of the byte-identical build output.
    "scripts/verification-sources/templates",
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

# Synchronous EDT dispatch is centralized in EdtDispatch so interrupt/timeout abandonment is
# provable. The unqualified token is banned in runtime production sources (comments included)
# outside the dispatcher itself; invokeLater posts do not block and stay legal.
EDT_DISPATCH_SCAN_ROOT = "runtime/src/main/java"
EDT_DISPATCH_EXEMPT = "runtime/src/main/java/dev/turboism/ui/host/EdtDispatch.java"
EDT_DISPATCH_TOKEN = re.compile(r"\binvokeAndWait\b")

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

# A Cubism version fused into a type name appears as a digit run shaped like the governed
# versions: "52"/"53" encode major.minor and may be followed by a one- or two-digit patch
# ("520", "5203", "5302", "5303"). Runs of other shapes are not version claims and stay
# legal: non-52/53 minors (v55, Moc500, Sha256), digit runs longer than four digits
# (Manifest52030Overflow), and runs not starting with 5 (Point2, M12ReadSnapshotSource).
# A bare "52"/"53" still counts even at the end of a name -- it is the exact major.minor
# shape, so a Vector53-style suffix remains flagged by design.
CUBISM_VERSION_TOKEN = re.compile(r"(?<!\d)5[23]\d{0,2}(?!\d)")

# Type declarations are matched after stripping comments and string/char literals so only
# real class/interface/enum/record names count (mirrors the Gradle-side stripping rules).
JAVA_TYPE_DECLARATION = re.compile(
    r"\b(?:class|interface|enum|record|@interface)\s+([A-Za-z_$][\w$]*)"
)


def _strip_java_comments_and_strings(source: str) -> str:
    """Blank out // and /* */ comments plus string/char literals, preserving newlines."""
    output = []
    state = "code"
    escaped = False
    index = 0
    length = len(source)
    while index < length:
        character = source[index]
        if state == "code":
            if source.startswith("//", index):
                output.append(" ")
                state = "line"
                index += 2
            elif source.startswith("/*", index):
                output.append(" ")
                state = "block"
                index += 2
            elif character == '"':
                output.append(" ")
                state = "string"
                index += 1
            elif character == "'":
                output.append(" ")
                state = "char"
                index += 1
            else:
                output.append(character)
                index += 1
        elif state == "line":
            if character == "\n":
                output.append("\n")
                state = "code"
            index += 1
        elif state == "block":
            if source.startswith("*/", index):
                output.append(" ")
                state = "code"
                index += 2
            else:
                if character == "\n":
                    output.append("\n")
                index += 1
        else:
            closing = '"' if state == "string" else "'"
            if character == "\\" and not escaped:
                escaped = True
                index += 1
            elif escaped:
                escaped = False
                index += 1
            elif character == closing:
                output.append(" ")
                state = "code"
                index += 1
            elif character == "\n":
                output.append("\n")
                state = "code"
                index += 1
            else:
                index += 1
    return "".join(output)

ALL_RULES = ("javadoc", "digests", "naming", "assets", "edt-dispatch", "controlchars")

# Tab, LF and CR are the only control bytes legitimate in text sources; the rest must be
# written as escape sequences so they stay visible in review.
CONTROL_CHAR_PATTERN = re.compile(rb"[\x00-\x08\x0b\x0c\x0e-\x1f]")
CONTROL_CHAR_JAVA_ROOTS = DIGEST_SCAN_ROOTS + ("buildSrc/src/main/java",)

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
            display = source.relative_to(root).as_posix()
            if CUBISM_VERSION_TOKEN.search(source.stem):
                failures.append(
                    f"production type name encodes a Cubism version: {display}"
                )
                continue
            stripped = _strip_java_comments_and_strings(
                source.read_text(encoding="utf-8")
            )
            if any(
                CUBISM_VERSION_TOKEN.search(name)
                for name in JAVA_TYPE_DECLARATION.findall(stripped)
            ):
                failures.append(
                    f"production type name encodes a Cubism version: {display}"
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
        parts = asset.stem.lower().split("-")
        if any(token in part for part in parts for token in RETIRED_ASSET_TOKENS):
            failures.append(f"retired governance token in asset name: {relative}")
    return failures


def check_edt_dispatch(root: Path) -> list[str]:
    failures = []
    for source in java_sources(root, EDT_DISPATCH_SCAN_ROOT):
        relative = source.relative_to(root).as_posix()
        if relative == EDT_DISPATCH_EXEMPT:
            continue
        for lineno, line in enumerate(source.read_text(encoding="utf-8").splitlines(), 1):
            if EDT_DISPATCH_TOKEN.search(line):
                failures.append(
                    "direct invokeAndWait dispatch is forbidden; route through EdtDispatch: "
                    f"{relative}:{lineno}"
                )
    return failures


def check_controlchars(root: Path) -> list[str]:
    candidates = [
        source
        for relative in CONTROL_CHAR_JAVA_ROOTS
        for source in java_sources(root, relative)
    ]
    scripts = root / "scripts"
    if scripts.exists():
        candidates.extend(sorted(scripts.rglob("*.py")))
    candidates.extend(sorted(root.glob("*.gradle.kts")))
    for scope in (root / "gradle", root / "buildSrc"):
        if scope.exists():
            candidates.extend(sorted(scope.rglob("*.kts")))
    failures = []
    for source in candidates:
        data = source.read_bytes()
        match = CONTROL_CHAR_PATTERN.search(data)
        if match:
            line = data.count(b"\n", 0, match.start()) + 1
            failures.append(
                f"literal control character 0x{match.group()[0]:02x} in source: "
                f"{source.relative_to(root).as_posix()}:{line}"
            )
    return failures


CHECKS = {
    "javadoc": check_javadoc,
    "digests": check_digests,
    "naming": check_naming,
    "assets": check_assets,
    "edt-dispatch": check_edt_dispatch,
    "controlchars": check_controlchars,
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
