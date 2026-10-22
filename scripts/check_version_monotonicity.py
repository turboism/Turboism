#!/usr/bin/env python3
"""Fail when a Cubism version set drops a higher patch of the same minor line.

Within one Cubism minor line, capability is downward compatible: a newer patch
release exposes a superset of every older patch's capabilities
(5.3.04 ⊇ 5.3.03 ⊇ ... ; 5.2.03 ⊇ 5.2.02 ⊇ ...). Cross-minor changes
(5.2 -> 5.3) may genuinely remove or rename features, so the rule is enforced
per minor line only.

Every site that restates a set of exact Cubism versions must therefore be
upward-closed inside each minor line: when a set contains version ``v`` it must
also contain every higher admitted patch of ``v``'s line. A set that names only
older patches (a feature introduced late, or removed at a minor boundary) is
legal; a set that skips a higher patch is a silent admission hole.

Checked sites:

- ``build/generated/verification/cubism-*-*.json``: per family, the
  ``capabilityIds`` list and the selector ``alias`` set of a newer patch must
  include the older patch's.
- Java sources under ``JAVA_ROOTS``: ``Set.of(...)``/``List.of(...)``/
  ``Map.of*(...)`` argument lists, ``@CubismEditor`` annotations (``value``
  lists and ``from``/``to``/``exclude`` selections), and any other exact-version
  literal or ``CUBISM_*`` constant reference left over in a file (constructor
  tables, switch arms, equals pins) are folded into a per-file set.
- ``runtime/src/main/resources/**/class-pins/*.json`` version keys.
- ``scripts/preview/host-validation-tasks.json`` per-task ``versions``.
- ``plugins/mcp/.../mcp-sdk-coverage.json`` per-entry ``supportedVersions``.

``scripts/version-monotonicity-exceptions.json`` lists sanctioned violations.
Each entry names the site and the exact missing items it covers plus either a
``pending`` branch that owns the repair or free-text ``evidence`` (for example,
bytecode that differs across the line). An exception that no longer matches a
live violation fails the check so the list cannot rot.

Usage: check_version_monotonicity.py [repo-root]
"""
from __future__ import annotations

from preview.reviewed_host_artifacts import load_releases

import argparse
import json
import re
import sys
from dataclasses import dataclass
from pathlib import Path

from turboism_dev.json_io import read_json
from turboism_dev.paths import repo_root
from turboism_dev.versions import (
    compact_to_dotted as compact_to_dotted_impl,
    missing_higher,
    parse_cubism_version,
)

ROOT = repo_root()

REVIEWED_HOST_ARTIFACTS = Path(
    "compatibility/cubism/reviewed-releases.json"
)
VERIFICATION_DIR = Path("build/generated/verification")
CLASS_PINS_DIR = Path(
    "runtime/src/main/resources/dev/turboism/adapter/cubism/class-pins"
)
HOST_VALIDATION_TASKS = Path("scripts/preview/host-validation-tasks.json")
MCP_SDK_COVERAGE = Path(
    "plugins/mcp/src/main/resources/META-INF/turboism/mcp-sdk-coverage.json"
)
EXCEPTIONS_FILE = Path("scripts/version-monotonicity-exceptions.json")

JAVA_ROOTS = (
    "sdk/src/main",
    "runtime/src/main",
    "bootstrap/src/main",
    "core-contract/src/main",
    "event-processor/src/main",
    "graal-host/src/main",
    "packaging/java-installer/listener-src",
    "buildSrc/src/main",
    "templates",
    "scripts/verification-sources/templates",
)
JAVA_ROOT_GLOBS = ("plugins/*/src/main",)
JAVA_EXCLUDE = re.compile(
    r"(?:^|/)(?:src/test|test|selfcheck|regression-src|isolationFixtures)(?:/|$)"
)

DOTTED = re.compile(r"^5\.(\d{1,2})\.(\d{2})$")
COMPACT = re.compile(r"^5\d{3}$")

RECORD_FILENAME = re.compile(r"^cubism-(5\.\d{1,2}\.\d{2})-(.+)\.json$")

# Java-site patterns.
ANNOTATION_OPEN = re.compile(r"@(?:dev\.turboism\.sdk\.)?CubismEditor\s*\(")
COLLECTION_OPEN = re.compile(
    r"\b(?:java\.util\.)?(?:Set|List|Map|EnumSet)\s*\.\s*"
    r"(?:of|ofEntries|copyOf)\s*\(")
DOTTED_LITERAL = re.compile(r'"(5\.\d{1,2}\.\d{2})"')
RANGE_LITERAL = re.compile(r'"(5\.\d{1,2}\.\d{2})-(5\.\d{1,2}\.\d{2})"')
COMPACT_LITERAL = re.compile(r'"(5\d{3})"')
CONST_REF = re.compile(
    r"\bCUBISM_(?:VERSION_)?5_(\d{1,2})_(\d{2})(?:_VERSION)?\b"
    r"|\bCUBISM_5(\d{1,2})(\d{2})\b"
)


class MonotonicityError(Exception):
    """Raised when a checked surface cannot be parsed at all."""


@dataclass
class Finding:
    """One non-monotonic version set (or a stale exception)."""

    kind: str
    path: str
    site: str
    missing: tuple[str, ...] = ()
    detail: str = ""

    def message(self) -> str:
        if self.kind == "stale-exception":
            return f"{self.path}: exception '{self.site}' matches no live violation"
        if self.kind == "error":
            return self.site
        missing = ", ".join(self.missing)
        return f"{self.path}: {self.site} skips {missing} ({self.detail})"


def parse_dotted(token: str) -> tuple[int, int]:
    try:
        return parse_cubism_version(token)
    except ValueError as exc:
        raise MonotonicityError(str(exc)) from exc


def compact_to_dotted(token: str) -> str:
    try:
        return compact_to_dotted_impl(token)
    except ValueError as exc:
        raise MonotonicityError(str(exc)) from exc


def _read(root: Path, relative: Path) -> str:
    path = root / relative
    try:
        return path.read_text(encoding="utf-8")
    except OSError as exc:
        raise MonotonicityError(f"unable to read {relative}: {exc}") from exc


def admitted_versions(root: Path) -> set[str]:
    try:
        return {r['version'] for r in load_releases(root / REVIEWED_HOST_ARTIFACTS)}
    except ValueError as exc:
        raise MonotonicityError(str(exc)) from exc


def strip_comments(text: str) -> str:
    """Blank out // and /* */ comments, preserving offsets and newlines."""
    out = list(text)
    i, n = 0, len(text)
    in_string = in_char = in_textblock = False
    while i < n:
        c = text[i]
        if in_string:
            if c == "\\":
                i += 2
                continue
            if c == '"':
                in_string = False
        elif in_char:
            if c == "\\":
                i += 2
                continue
            if c == "'":
                in_char = False
        elif in_textblock:
            if text.startswith('"""', i):
                in_textblock = False
                i += 2
        elif c == '"':
            if text.startswith('"""', i):
                in_textblock = True
                i += 2
            else:
                in_string = True
        elif c == "'":
            in_char = True
        elif c == "/" and i + 1 < n and text[i + 1] == "/":
            j = i
            while j < n and text[j] != "\n":
                out[j] = " "
                j += 1
            i = j
            continue
        elif c == "/" and i + 1 < n and text[i + 1] == "*":
            j = i
            while j + 1 < n and text[j : j + 2] != "*/":
                if text[j] != "\n":
                    out[j] = " "
                j += 1
            end = min(j + 2, n)
            for k in range(j, end):
                if text[k] != "\n":
                    out[k] = " "
            i = end
            continue
        i += 1
    return "".join(out)


def match_paren(text: str, open_index: int) -> int:
    """Index just past the ')' balancing text[open_index] == '('."""
    depth = 0
    i, n = open_index, len(text)
    in_string = in_char = in_textblock = False
    while i < n:
        c = text[i]
        if in_string:
            if c == "\\":
                i += 1
            elif c == '"':
                in_string = False
        elif in_char:
            if c == "\\":
                i += 1
            elif c == "'":
                in_char = False
        elif in_textblock:
            if text.startswith('"""', i):
                in_textblock = False
                i += 2
        elif c == '"':
            if text.startswith('"""', i):
                in_textblock = True
                i += 2
            else:
                in_string = True
        elif c == "'":
            in_char = True
        elif c == "(":
            depth += 1
        elif c == ")":
            depth -= 1
            if depth == 0:
                return i + 1
        i += 1
    raise MonotonicityError(f"unbalanced '(' at offset {open_index}")


def literal_versions(span: str, admitted: set[str]) -> set[str]:
    """Exact/range string literals and CUBISM_* constant refs inside a span."""
    versions: set[str] = set()
    covered: list[tuple[int, int]] = []
    for match in RANGE_LITERAL.finditer(span):
        lo, hi = parse_dotted(match.group(1)), parse_dotted(match.group(2))
        for version in admitted:
            parsed = parse_dotted(version)
            if lo <= parsed <= hi:
                versions.add(version)
        covered.append(match.span())
    for match in DOTTED_LITERAL.finditer(span):
        if any(s <= match.start() < e for s, e in covered):
            continue
        versions.add(match.group(1))
    for match in COMPACT_LITERAL.finditer(span):
        versions.add(compact_to_dotted(match.group(1)))
    for match in CONST_REF.finditer(span):
        if match.group(1) is not None:
            versions.add(f"5.{int(match.group(1))}.{int(match.group(2)):0>2}")
        else:
            versions.add(f"5.{int(match.group(3))}.{int(match.group(4)):0>2}")
    return versions


def annotation_versions(span: str, admitted: set[str]) -> set[str]:
    """The set selected by one @CubismEditor(...) annotation body."""
    excluded: set[str] = set()
    rest = span
    for attr in ("exclude",):
        match = re.search(attr + r"\s*=\s*(\{[^}]*\}|\"[^\"]*\")", rest)
        if match:
            excluded |= set(DOTTED_LITERAL.findall(match.group(1)))
            rest = rest[: match.start()] + " " + rest[match.end() :]
    bounds: dict[str, tuple[int, int] | None] = {"from": None, "to": None}
    for attr in bounds:
        match = re.search(attr + r'\s*=\s*"(5\.\d{1,2}\.\d{2})"', rest)
        if match:
            bounds[attr] = parse_dotted(match.group(1))
            rest = rest[: match.start()] + " " + rest[match.end() :]
    array = re.search(r"\{([^}]*)\}", rest)
    scalar = re.search(r'"(5\.\d{1,2}\.\d{2})"', rest)
    if array:
        selected = set(DOTTED_LITERAL.findall(array.group(1)))
    elif scalar:
        selected = {scalar.group(1)}
    else:
        selected = set()
        for version in admitted:
            parsed = parse_dotted(version)
            if bounds["from"] is not None and parsed < bounds["from"]:
                continue
            if bounds["to"] is not None and parsed > bounds["to"]:
                continue
            selected.add(version)
    return selected - excluded


def line_of(text: str, offset: int) -> int:
    return text.count("\n", 0, offset) + 1


def java_findings(
    relative: str, text: str, admitted: set[str]
) -> tuple[list[Finding], list[str]]:
    findings: list[Finding] = []
    problems: list[str] = []
    stripped = strip_comments(text)
    spans: list[tuple[int, int]] = []
    for match in ANNOTATION_OPEN.finditer(stripped):
        try:
            end = match_paren(stripped, match.end() - 1)
        except MonotonicityError as exc:
            problems.append(f"{relative}: {exc}")
            continue
        spans.append((match.start(), end))
        declared = annotation_versions(stripped[match.end() : end - 1], admitted)
        if not declared:
            continue
        missing = missing_higher(declared, admitted)
        if missing:
            findings.append(
                Finding(
                    "java-annotation",
                    relative,
                    ",".join(sorted(declared, key=parse_dotted)),
                    tuple(sorted(missing, key=parse_dotted)),
                    f"@CubismEditor at line {line_of(text, match.start())} "
                    f"admits {sorted(declared)}",
                )
            )
    for match in COLLECTION_OPEN.finditer(stripped):
        try:
            end = match_paren(stripped, match.end() - 1)
        except MonotonicityError as exc:
            problems.append(f"{relative}: {exc}")
            continue
        spans.append((match.start(), end))
        declared = literal_versions(stripped[match.start() : end], admitted)
        if not declared:
            continue
        missing = missing_higher(declared, admitted)
        if missing:
            findings.append(
                Finding(
                    "java-set",
                    relative,
                    ",".join(sorted(declared, key=parse_dotted)),
                    tuple(sorted(missing, key=parse_dotted)),
                    f"collection literal at line {line_of(text, match.start())} "
                    f"admits {sorted(declared)}",
                )
            )
    residual = list(stripped)
    for start, end in spans:
        for i in range(start, end):
            if residual[i] != "\n":
                residual[i] = " "
    declared = literal_versions("".join(residual), admitted)
    missing = missing_higher(declared, admitted)
    if missing:
        findings.append(
            Finding(
                "java-file",
                relative,
                ",".join(sorted(declared, key=parse_dotted)),
                tuple(sorted(missing, key=parse_dotted)),
                f"version tokens outside collection literals admit "
                f"{sorted(declared)}",
            )
        )
    return findings, problems


def java_roots(root: Path) -> list[Path]:
    resolved = [root / base for base in JAVA_ROOTS]
    for pattern in JAVA_ROOT_GLOBS:
        resolved.extend(sorted(root.glob(pattern)))
    return resolved


def collect_java(root: Path, admitted: set[str]) -> tuple[list[Finding], list[str]]:
    findings: list[Finding] = []
    problems: list[str] = []
    for base in java_roots(root):
        if not base.is_dir():
            continue
        for source in sorted(base.rglob("*.java")):
            relative = source.relative_to(root).as_posix()
            if JAVA_EXCLUDE.search(relative):
                continue
            file_findings, file_problems = java_findings(
                relative, source.read_text(encoding="utf-8"), admitted
            )
            findings.extend(file_findings)
            problems.extend(file_problems)
    return findings, problems


# --------------------------------------------------------------------------
# JSON surfaces
# --------------------------------------------------------------------------


def collect_verification_records(
    root: Path, admitted: set[str]
) -> tuple[list[Finding], list[str]]:
    findings: list[Finding] = []
    problems: list[str] = []
    base = root / VERIFICATION_DIR
    if not base.is_dir():
        problems.append(f"{VERIFICATION_DIR}: directory missing")
        return findings, problems
    records: dict[str, dict[str, tuple[set[str], set[str]]]] = {}
    for record in sorted(base.glob("cubism-*.json")):
        name_match = RECORD_FILENAME.match(record.name)
        if name_match is None:
            continue
        try:
            document = read_json(record)
        except json.JSONDecodeError as exc:
            problems.append(f"{VERIFICATION_DIR}/{record.name}: invalid JSON: {exc}")
            continue
        family = name_match.group(2)
        version = name_match.group(1)
        capabilities = set(document.get("capabilityIds", []))
        aliases = {
            str(selector.get("alias"))
            for selector in document.get("selectors", [])
            if isinstance(selector, dict) and "alias" in selector
        }
        records.setdefault(family, {})[version] = (capabilities, aliases)
    for family, by_version in sorted(records.items()):
        coverage_missing = missing_higher(set(by_version) & admitted, admitted)
        if coverage_missing:
            findings.append(
                Finding(
                    "verification-record",
                    "compatibility/cubism/verification",
                    f"{family} record coverage",
                    tuple(sorted(coverage_missing, key=parse_dotted)),
                    f"{family} has records for {sorted(by_version)}",
                )
            )
        known = [v for v in sorted(by_version, key=parse_dotted) if v in admitted]
        for older, newer in zip(known, known[1:]):
            if parse_dotted(older)[0] != parse_dotted(newer)[0]:
                continue
            old_caps, old_aliases = by_version[older]
            new_caps, new_aliases = by_version[newer]
            for label, lost in (
                ("capabilityIds", old_caps - new_caps),
                ("aliases", old_aliases - new_aliases),
            ):
                if lost:
                    findings.append(
                        Finding(
                            "verification-record",
                            "compatibility/cubism/verification",
                            f"{family} {older}>{newer} {label}",
                            tuple(sorted(lost)),
                            f"{family} record {newer} drops {len(lost)} {label} "
                            f"present in {older}",
                        )
                    )
    return findings, problems


def collect_class_pins(
    root: Path, admitted: set[str]
) -> tuple[list[Finding], list[str]]:
    findings: list[Finding] = []
    problems: list[str] = []
    base = root / CLASS_PINS_DIR
    if not base.is_dir():
        problems.append(f"{CLASS_PINS_DIR}: directory missing")
        return findings, problems
    for pin in sorted(base.glob("*.json")):
        try:
            document = read_json(pin)
        except json.JSONDecodeError as exc:
            problems.append(f"{CLASS_PINS_DIR}/{pin.name}: invalid JSON: {exc}")
            continue
        if not isinstance(document, dict):
            problems.append(f"{CLASS_PINS_DIR}/{pin.name}: top-level object expected")
            continue
        declared = {str(v) for v in document}
        missing = missing_higher(declared, admitted)
        if missing:
            findings.append(
                Finding(
                    "class-pins",
                    f"{CLASS_PINS_DIR.as_posix()}/{pin.name}",
                    pin.name,
                    tuple(sorted(missing, key=parse_dotted)),
                    f"pins cover {sorted(declared)}",
                )
            )
    return findings, problems


def collect_host_validation_tasks(
    root: Path, admitted: set[str]
) -> tuple[list[Finding], list[str]]:
    findings: list[Finding] = []
    problems: list[str] = []
    try:
        document = json.loads(_read(root, HOST_VALIDATION_TASKS))
    except json.JSONDecodeError as exc:
        raise MonotonicityError(f"{HOST_VALIDATION_TASKS}: invalid JSON: {exc}") from exc
    for name, task in sorted(document.get("tasks", {}).items()):
        declared = set()
        for token in task.get("versions", []):
            if not COMPACT.fullmatch(str(token)):
                problems.append(
                    f"{HOST_VALIDATION_TASKS}: task {name} has non-exact "
                    f"version {token!r}"
                )
                continue
            declared.add(compact_to_dotted(str(token)))
        missing = missing_higher(declared, admitted)
        if missing:
            findings.append(
                Finding(
                    "host-validation-task",
                    HOST_VALIDATION_TASKS.as_posix(),
                    name,
                    tuple(sorted(missing, key=parse_dotted)),
                    f"task {name} runs on {sorted(declared)}",
                )
            )
    return findings, problems


def collect_mcp_coverage(
    root: Path, admitted: set[str]
) -> tuple[list[Finding], list[str]]:
    findings: list[Finding] = []
    problems: list[str] = []
    path = root / MCP_SDK_COVERAGE
    if not path.is_file():
        return findings, problems
    try:
        document = read_json(path)
    except json.JSONDecodeError as exc:
        problems.append(f"{MCP_SDK_COVERAGE}: invalid JSON: {exc}")
        return findings, problems
    for index, entry in enumerate(document.get("entries", [])):
        versions = entry.get("supportedVersions")
        if not isinstance(versions, list):
            continue
        declared = {str(v) for v in versions}
        missing = missing_higher(declared, admitted)
        if missing:
            findings.append(
                Finding(
                    "mcp-coverage",
                    MCP_SDK_COVERAGE.as_posix(),
                    str(entry.get("sdkMethod", f"entries[{index}]")),
                    tuple(sorted(missing, key=parse_dotted)),
                    f"entry {index} covers {sorted(declared)}",
                )
            )
    return findings, problems


# --------------------------------------------------------------------------
# Exceptions
# --------------------------------------------------------------------------


def load_exceptions(root: Path) -> tuple[list[dict], list[str]]:
    path = root / EXCEPTIONS_FILE
    if not path.is_file():
        return [], []
    try:
        document = read_json(path)
    except json.JSONDecodeError as exc:
        raise MonotonicityError(f"{EXCEPTIONS_FILE}: invalid JSON: {exc}") from exc
    problems = []
    entries = document.get("exceptions")
    if not isinstance(entries, list):
        raise MonotonicityError(f"{EXCEPTIONS_FILE}: 'exceptions' list not found")
    for index, entry in enumerate(entries):
        for key in ("kind", "path", "site", "missing", "reason"):
            if key not in entry:
                problems.append(
                    f"{EXCEPTIONS_FILE}: exception {index} lacks {key!r}"
                )
        if "pending" not in entry and "evidence" not in entry:
            problems.append(
                f"{EXCEPTIONS_FILE}: exception {index} needs 'pending' or 'evidence'"
            )
        if not isinstance(entry.get("missing", []), list):
            problems.append(
                f"{EXCEPTIONS_FILE}: exception {index} 'missing' must be a list"
            )
    return entries, problems


def apply_exceptions(
    findings: list[Finding], exceptions: list[dict]
) -> tuple[list[Finding], list[Finding], list[Finding]]:
    """Split findings into (suppressed, live); append stale-exception findings."""
    suppressed: list[Finding] = []
    live: list[Finding] = []
    used = [False] * len(exceptions)
    for finding in findings:
        remainder = list(finding.missing)
        for index, entry in enumerate(exceptions):
            if (
                entry.get("kind") == finding.kind
                and entry.get("path") == finding.path
                and entry.get("site") == finding.site
            ):
                covered_missing = set(entry.get("missing", []))
                remainder = [m for m in remainder if m not in covered_missing]
                used[index] = True
        if not remainder:
            suppressed.append(finding)
        else:
            live.append(
                Finding(finding.kind, finding.path, finding.site,
                        tuple(remainder), finding.detail)
            )
    for index, entry in enumerate(exceptions):
        if not used[index]:
            live.append(
                Finding(
                    "stale-exception",
                    EXCEPTIONS_FILE.as_posix(),
                    f"{entry.get('kind')} {entry.get('path')} {entry.get('site')}",
                )
            )
    return suppressed, live


# --------------------------------------------------------------------------
# Driver
# --------------------------------------------------------------------------


def collect(root: Path) -> tuple[list[Finding], list[str]]:
    admitted = admitted_versions(root)
    findings: list[Finding] = []
    problems: list[str] = []
    for collector in (
        collect_verification_records,
        collect_class_pins,
        collect_host_validation_tasks,
        collect_mcp_coverage,
        collect_java,
    ):
        partial, partial_problems = collector(root, admitted)
        findings.extend(partial)
        problems.extend(partial_problems)
    return findings, problems


def check(root: Path) -> tuple[list[Finding], list[Finding]]:
    findings, problems = collect(root)
    live = [Finding("error", "", p) for p in problems]
    exceptions, exception_problems = load_exceptions(root)
    live.extend(Finding("error", EXCEPTIONS_FILE.as_posix(), p) for p in exception_problems)
    suppressed, remainder = apply_exceptions(findings, exceptions)
    live.extend(remainder)
    return suppressed, live


def run(root: Path) -> int:
    try:
        suppressed, live = check(root)
    except MonotonicityError as exc:
        print(f"FAIL: {exc}", file=sys.stderr)
        return 2
    for finding in live:
        print(f"FAIL: {finding.message()}")
    for finding in suppressed:
        print(f"EXCEPTION: {finding.site} in {finding.path} "
              f"({len(finding.missing)} item(s) covered by exception)")
    if live:
        print(f"\n{len(live)} monotonicity finding(s); "
              f"{len(suppressed)} suppressed by exception")
        return 1
    print(
        f"PASS: every checked Cubism version set is upward-closed within its "
        f"minor line ({len(suppressed)} documented exception(s))"
    )
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("root", nargs="?", default=str(ROOT))
    args = parser.parse_args()
    return run(Path(args.root).resolve())


if __name__ == "__main__":
    raise SystemExit(main())
