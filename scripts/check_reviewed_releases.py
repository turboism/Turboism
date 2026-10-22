#!/usr/bin/env python3
"""Reviewed release gates: R1 integrity, R2 patch closure, R3 literal ban, R4 task coverage.

Generated trust roots and materialized records are checked against their sources.
Exceptions must match live sites; version-monotonicity-exceptions.json retains its
existing evidence/pending semantics. SDK annotation coverage remains a separate gate.
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from dataclasses import dataclass
from pathlib import Path

from turboism_dev.json_io import dump_json, read_json
from turboism_dev.paths import repo_root
from turboism_dev.versions import (reviewed_range_versions, cubism_version_key, dotted_to_compact,
    compact_to_dotted as _compact_to_dotted, missing_higher, parse_cubism_version)
from preview.reviewed_host_artifacts import SOURCE_RELATIVE, load_releases
import generate_reviewed_releases as generated
from materialize_verification_records import render_records, check_records

sys.path.insert(0, str(Path(__file__).resolve().parent / "preview"))
from host_validation_static_gates import TASK_REQUIRED_CAPABILITIES


ROOT = repo_root()
RELEASES = Path(SOURCE_RELATIVE)
VERIFICATION_DIR = Path("build/generated/verification")
CLASS_PINS_DIR = Path("runtime/src/main/resources/dev/turboism/adapter/cubism/class-pins")
HOST_TASKS = Path("scripts/preview/host-validation-tasks.json")
COMPACT_TOKEN = re.compile(r"^5\d{3}$")
COMPACT_LITERAL = re.compile(r'"(5\d{3})"')


class ReleaseCheckError(Exception):
    """A checked source cannot be read or parsed."""


def read_source(root: Path, relative: Path | None = None) -> str:
    path = root / relative if relative is not None else root
    try:
        return path.read_text(encoding="utf-8")
    except OSError as exc:
        raise ReleaseCheckError(f"unable to read {relative if relative is not None else path}: {exc}") from exc


def admitted_versions(root: Path) -> set[str]:
    try:
        return {release["version"] for release in load_releases(root / RELEASES)}
    except ValueError as exc:
        raise ReleaseCheckError(str(exc)) from exc


def parse_dotted(token: str) -> tuple[int, int]:
    try:
        return parse_cubism_version(token)
    except ValueError as exc:
        raise ReleaseCheckError(str(exc)) from exc


def compact_to_dotted(token: str) -> str:
    try:
        return _compact_to_dotted(token)
    except ValueError as exc:
        raise ReleaseCheckError(str(exc)) from exc


# R1


r1_CANONICAL = "reviewed-releases.json"
r1_RELEASES = RELEASES
r1_REVIEWED_HOST_ARTIFACTS = Path(
    "runtime/src/main/java/dev/turboism/mapping/verification/ReviewedHostArtifacts.java"
)
r1_COMPATIBILITY_CATALOG = Path(
    "scripts/verification-sources/families/compatibility-catalog.json"
)
r1_AVAILABILITY_POLICY = Path(
    "runtime/src/main/java/dev/turboism/core/plugin/context/CubismEditorAvailabilityPolicy.java"
)
r1_HOST_VALIDATION_TASKS = HOST_TASKS
r1_HOST_VALIDATION_PY = Path("scripts/preview/host_validation.py")
r1_INDEX_MD = Path("compatibility/cubism/index.md")
r1_CLASS_PINS_DIR = CLASS_PINS_DIR
r1_PROBE_CLOSE_ROUTES = (
    Path(
        "testing/integration-tests/src/test/java/dev/turboism/tests/plugin/"
        "WindowsParameterValidationProbe.java"
    ),
    Path(
        "testing/integration-tests/src/test/java/dev/turboism/tests/plugin/"
        "WindowsRecentPreviewValidationProbe.java"
    ),
    Path(
        "testing/integration-tests/src/test/java/dev/turboism/tests/plugin/"
        "WindowsHistoryNativeUiHostClose.java"
    ),
)
r1_CLOSE_ROUTE_SWITCH = re.compile(
    r"hostCloseRoute\(final String hostVersion\).*?switch \(hostVersion\) \{(.*?)\};",
    re.DOTALL,
)


r1_RECORD_FILENAME = re.compile(r'"cubism-(\d+\.\d+\.\d+)-[^"]+\.json"')
r1_INDEX_VERSION = re.compile(r"\b5\.\d{1,2}\.\d{2}\b")


r1_CompletenessError = ReleaseCheckError


def r1_reviewed_host_artifacts(root: Path) -> tuple[set[str], list[str]]:
    try:
        versions = admitted_versions(root)
        return versions, generated.check(root)
    except (ValueError, OSError) as exc:
        raise r1_CompletenessError(str(exc)) from exc


def r1_compatibility_catalog(root: Path) -> set[str]:
    try:
        document = json.loads(read_source(root, r1_COMPATIBILITY_CATALOG))
    except json.JSONDecodeError as exc:
        raise r1_CompletenessError(f"{r1_COMPATIBILITY_CATALOG}: invalid JSON: {exc}") from exc
    records = document.get("records")
    if not isinstance(records, list) or not records:
        raise r1_CompletenessError(f"{r1_COMPATIBILITY_CATALOG}: records list not found")
    versions = set()
    for entry in records:
        match = re.fullmatch(r"cubism-(5\.\d{1,2}\.\d{2})-[^.]+\.json", str(entry))
        if match:
            versions.add(match.group(1))
    return versions


def r1_derived_consumers(root: Path) -> list[str]:
    """Reject a stale consumer that stops using the generated table."""
    expected = {
        r1_AVAILABILITY_POLICY: 'REVIEWED_VERSIONS = ReviewedReleaseTable.versions()',
        Path('runtime/src/main/java/dev/turboism/mapping/verification/ReviewedCubismReleases.java'): 'ReviewedReleaseTable.find(version)',
        Path('runtime/src/main/java/dev/turboism/mapping/verification/CorePublicApiTrustRoots.java'): 'ReviewedReleaseTable.find(profile)',
        Path('runtime/src/main/java/dev/turboism/adapter/cubism/core/CoreVersionExpectation.java'): 'ReviewedReleaseTable.find(profile)',
        r1_HOST_VALIDATION_PY: 'reviewed_host_artifacts.load()',
    }
    return [f'{path}: must derive reviewed versions from the table'
            for path, expression in expected.items() if expression not in read_source(root, path)]


def r1_materialized_record_versions(root: Path) -> set[str]:
    return {v for path in (root / "build/generated/verification").glob("*.json")
            for v in r1_RECORD_FILENAME.findall(f'"{path.name}"')}


def r1_host_validation_tasks(root: Path) -> tuple[set[str], list[str]]:
    try:
        document = json.loads(read_source(root, r1_HOST_VALIDATION_TASKS))
    except json.JSONDecodeError as exc:
        raise r1_CompletenessError(f"{r1_HOST_VALIDATION_TASKS}: invalid JSON: {exc}") from exc
    versions: set[str] = set()
    problems = []
    for name, task in sorted(document.get("tasks", {}).items()):
        for token in task.get("versions", []):
            if not COMPACT_TOKEN.fullmatch(str(token)):
                problems.append(
                    f"{r1_HOST_VALIDATION_TASKS}: task {name} has non-exact version {token!r}"
                )
                continue
            versions.add(compact_to_dotted(str(token)))
    return versions, problems


def r1_index_md(root: Path) -> set[str]:
    return set(r1_INDEX_VERSION.findall(read_source(root, r1_INDEX_MD)))


r1_SHA256_HEX = re.compile(r"^[0-9a-f]{64}$")


def r1_class_pin_tables(root: Path) -> tuple[set[str], list[str]]:
    """Union of version keys across class-pins/*.json plus per-file problems."""
    base = root / r1_CLASS_PINS_DIR
    versions: set[str] = set()
    problems: list[str] = []
    if not base.is_dir():
        problems.append(f"{r1_CLASS_PINS_DIR}: directory missing")
        return versions, problems
    for pin in sorted(base.glob("*.json")):
        try:
            document = read_json(pin)
        except json.JSONDecodeError as exc:
            problems.append(f"{r1_CLASS_PINS_DIR}/{pin.name}: invalid JSON: {exc}")
            continue
        if not isinstance(document, dict):
            problems.append(f"{r1_CLASS_PINS_DIR}/{pin.name}: top-level object expected")
            continue
        for version, block in document.items():
            versions.add(str(version))
            if not isinstance(block, dict):
                problems.append(
                    f"{r1_CLASS_PINS_DIR}/{pin.name}: version '{version}' is not an object"
                )
                continue
            for class_name, sha in block.items():
                if not isinstance(sha, str) or not r1_SHA256_HEX.fullmatch(sha):
                    problems.append(
                        f"{r1_CLASS_PINS_DIR}/{pin.name}: '{class_name}' under "
                        f"'{version}' is not a 64-char lowercase hex SHA-256"
                    )
    return versions, problems


def r1_probe_close_routes(root: Path) -> dict[str, set[str]]:
    """Compact version codes admitted by each probe's hostCloseRoute switch."""
    routes: dict[str, set[str]] = {}
    for relative in r1_PROBE_CLOSE_ROUTES:
        try:
            text = read_source(root, relative)
        except r1_CompletenessError:
            continue  # Minimal fixture roots legitimately lack probe sources.
        match = r1_CLOSE_ROUTE_SWITCH.search(text)
        if match is None:
            raise r1_CompletenessError(f"{relative}: hostCloseRoute switch not found")
        routes[f"{relative} hostCloseRoute versions"] = {
            compact_to_dotted(token) for token in COMPACT_LITERAL.findall(match.group(1))
        }
    return routes


def r1_collect(root: Path) -> tuple[dict[str, set[str]], list[str]]:
    canonical, problems = r1_reviewed_host_artifacts(root)
    tasks, task_problems = r1_host_validation_tasks(root)
    pins, pin_problems = r1_class_pin_tables(root)
    problems.extend(r1_derived_consumers(root))
    problems.extend(task_problems)
    problems.extend(pin_problems)
    sites = {
        r1_CANONICAL: canonical,
        "compatibility-catalog.json record versions": r1_compatibility_catalog(root),
        "materialized verification records": r1_materialized_record_versions(root),
        "host-validation-tasks.json task versions (subset)": tasks,
        "compatibility/cubism/index.md": r1_index_md(root),
        "class-pins/*.json version keys (subset)": pins,
        **r1_probe_close_routes(root),
    }
    return sites, problems


def r1_family_range_coverage(root: Path) -> list[str]:
    """Family-owned range selections require materialized VERIFIED_STATIC records."""
    admitted = admitted_versions(root)
    records = {name: json.loads(raw) for name, raw in render_records(root).items()}
    problems = []
    for path in sorted((root / "scripts/verification-sources/families").glob("*.json")):
        family = read_json(path)
        covered = {
            records[name]["cubismVersion"] for name in family.get("records", [])
            if name in records and records[name].get("status") == "VERIFIED_STATIC"
        }
        sources = [Path("scripts/verification-sources/templates") / name
                   for name in family.get("files", [])]
        sources.extend(Path(name) for name in family.get("coverageSources", []))
        for relative in sources:
            text = strip_comments(read_source(root, relative))
            missing = r2_range_versions(text, admitted) - covered
            if missing:
                problems.append(
                    f"{relative}: {family['family']} range admits versions without "
                    f"VERIFIED_STATIC family records: {sorted(missing)}"
                )
    return problems


def r1_check(root: Path) -> list[str]:
    sites, problems = r1_collect(root)
    canonical = sites[r1_CANONICAL]
    violations = list(problems)
    for name, versions in sites.items():
        if name == r1_CANONICAL:
            continue
        if name.endswith("(subset)"):
            if not versions <= canonical:
                violations.append(
                    f"{name} names unadmitted versions "
                    f"{sorted(versions - canonical)}; "
                    f"admitted set is {sorted(canonical)}"
                )
            continue
        if versions != canonical:
            missing = sorted(canonical - versions)
            extra = sorted(versions - canonical)
            detail = []
            if missing:
                detail.append(f"missing {missing}")
            if extra:
                detail.append(f"unadmitted {extra}")
            violations.append(
                f"{name} restates {sorted(versions)}; "
                f"admitted set is {sorted(canonical)} ({'; '.join(detail)})"
            )
    return violations

# R2


r2_REVIEWED_HOST_ARTIFACTS = RELEASES
r2_VERIFICATION_DIR = VERIFICATION_DIR
r2_CLASS_PINS_DIR = CLASS_PINS_DIR
r2_HOST_VALIDATION_TASKS = HOST_TASKS
r2_MCP_SDK_COVERAGE = Path(
    "plugins/mcp/src/main/resources/META-INF/turboism/mcp-sdk-coverage.json"
)
r2_EXCEPTIONS_FILE = Path("scripts/version-monotonicity-exceptions.json")

r2_JAVA_ROOTS = (
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
r2_JAVA_ROOT_GLOBS = ("plugins/*/src/main",)
r2_JAVA_EXCLUDE = re.compile(
    r"(?:^|/)(?:src/test|test|selfcheck|regression-src|isolationFixtures)(?:/|$)"
)


r2_RECORD_FILENAME = re.compile(r"^cubism-(5\.\d{1,2}\.\d{2})-(.+)\.json$")

# Java-site patterns.
r2_ANNOTATION_OPEN = re.compile(r"@(?:dev\.turboism\.sdk\.)?CubismEditor\s*\(")
r2_COLLECTION_OPEN = re.compile(
    r"\b(?:java\.util\.)?(?:Set|List|Map|EnumSet)\s*\.\s*"
    r"(?:of|ofEntries|copyOf)\s*\(")
r2_DOTTED_LITERAL = re.compile(r'"(5\.\d{1,2}\.\d{2})"')
r2_RANGE_LITERAL = re.compile(r'"(5\.\d{1,2}\.\d{2})-(5\.\d{1,2}\.\d{2})"')
r2_CONST_REF = re.compile(
    r"\bCUBISM_(?:VERSION_)?5_(\d{1,2})_(\d{2})(?:_VERSION)?\b"
    r"|\bCUBISM_5(\d{1,2})(\d{2})\b"
)


r2_MonotonicityError = ReleaseCheckError


@dataclass
class r2_Finding:
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


def r2_range_versions(text: str, admitted: set[str]) -> set[str]:
    try:
        return reviewed_range_versions(text, admitted)
    except ValueError as exc:
        raise r2_MonotonicityError(str(exc)) from exc


def strip_reviewed_ranges(text: str) -> str:
    """Range boundaries are selections from generated data, not literal rosters."""
    return re.sub(r'\bReviewedVersions\.(?:since|line|between)\([^)]*\)',
                  lambda match: ' ' * len(match.group()), text)


def r2_match_paren(text: str, open_index: int) -> int:
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
    raise r2_MonotonicityError(f"unbalanced '(' at offset {open_index}")


def r2_literal_versions(span: str, admitted: set[str]) -> set[str]:
    """Exact/range string literals and CUBISM_* constant refs inside a span."""
    versions: set[str] = set()
    covered: list[tuple[int, int]] = []
    for match in r2_RANGE_LITERAL.finditer(span):
        lo, hi = parse_dotted(match.group(1)), parse_dotted(match.group(2))
        for version in admitted:
            parsed = parse_dotted(version)
            if lo <= parsed <= hi:
                versions.add(version)
        covered.append(match.span())
    for match in r2_DOTTED_LITERAL.finditer(span):
        if any(s <= match.start() < e for s, e in covered):
            continue
        versions.add(match.group(1))
    for match in COMPACT_LITERAL.finditer(span):
        versions.add(compact_to_dotted(match.group(1)))
    for match in r2_CONST_REF.finditer(span):
        if match.group(1) is not None:
            versions.add(f"5.{int(match.group(1))}.{int(match.group(2)):0>2}")
        else:
            versions.add(f"5.{int(match.group(3))}.{int(match.group(4)):0>2}")
    return versions


def r2_annotation_versions(span: str, admitted: set[str]) -> set[str]:
    """The set selected by one @CubismEditor(...) annotation body."""
    excluded: set[str] = set()
    rest = span
    for attr in ("exclude",):
        match = re.search(attr + r"\s*=\s*(\{[^}]*\}|\"[^\"]*\")", rest)
        if match:
            excluded |= set(r2_DOTTED_LITERAL.findall(match.group(1)))
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
        selected = set(r2_DOTTED_LITERAL.findall(array.group(1)))
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


def r2_line_of(text: str, offset: int) -> int:
    return text.count("\n", 0, offset) + 1


def r2_java_findings(
    relative: str, text: str, admitted: set[str]
) -> tuple[list[r2_Finding], list[str]]:
    findings: list[r2_Finding] = []
    problems: list[str] = []
    stripped = strip_reviewed_ranges(strip_comments(text))
    spans: list[tuple[int, int]] = []
    for match in r2_ANNOTATION_OPEN.finditer(stripped):
        try:
            end = r2_match_paren(stripped, match.end() - 1)
        except r2_MonotonicityError as exc:
            problems.append(f"{relative}: {exc}")
            continue
        spans.append((match.start(), end))
        declared = r2_annotation_versions(stripped[match.end() : end - 1], admitted)
        if not declared:
            continue
        missing = missing_higher(declared, admitted)
        if missing:
            findings.append(
                r2_Finding(
                    "java-annotation",
                    relative,
                    ",".join(sorted(declared, key=parse_dotted)),
                    tuple(sorted(missing, key=parse_dotted)),
                    f"@CubismEditor at line {r2_line_of(text, match.start())} "
                    f"admits {sorted(declared)}",
                )
            )
    for match in r2_COLLECTION_OPEN.finditer(stripped):
        try:
            end = r2_match_paren(stripped, match.end() - 1)
        except r2_MonotonicityError as exc:
            problems.append(f"{relative}: {exc}")
            continue
        spans.append((match.start(), end))
        declared = r2_literal_versions(stripped[match.start() : end], admitted)
        if not declared:
            continue
        missing = missing_higher(declared, admitted)
        if missing:
            findings.append(
                r2_Finding(
                    "java-set",
                    relative,
                    ",".join(sorted(declared, key=parse_dotted)),
                    tuple(sorted(missing, key=parse_dotted)),
                    f"collection literal at line {r2_line_of(text, match.start())} "
                    f"admits {sorted(declared)}",
                )
            )
    residual = list(stripped)
    for start, end in spans:
        for i in range(start, end):
            if residual[i] != "\n":
                residual[i] = " "
    declared = r2_literal_versions("".join(residual), admitted)
    missing = missing_higher(declared, admitted)
    if missing:
        findings.append(
            r2_Finding(
                "java-file",
                relative,
                ",".join(sorted(declared, key=parse_dotted)),
                tuple(sorted(missing, key=parse_dotted)),
                f"version tokens outside collection literals admit "
                f"{sorted(declared)}",
            )
        )
    ranges = r2_range_versions(strip_comments(text), admitted)
    missing = missing_higher(ranges, admitted)
    if missing:
        findings.append(r2_Finding("java-range", relative,
                ",".join(sorted(ranges, key=parse_dotted)),
                tuple(sorted(missing, key=parse_dotted)), "reviewed range selections"))
    return findings, problems


def r2_java_roots(root: Path) -> list[Path]:
    resolved = [root / base for base in r2_JAVA_ROOTS]
    for pattern in r2_JAVA_ROOT_GLOBS:
        resolved.extend(sorted(root.glob(pattern)))
    return resolved


def r2_collect_java(root: Path, admitted: set[str]) -> tuple[list[r2_Finding], list[str]]:
    findings: list[r2_Finding] = []
    problems: list[str] = []
    for base in r2_java_roots(root):
        if not base.is_dir():
            continue
        for source in sorted(base.rglob("*.java")):
            relative = source.relative_to(root).as_posix()
            if r2_JAVA_EXCLUDE.search(relative):
                continue
            file_findings, file_problems = r2_java_findings(
                relative, read_source(source), admitted
            )
            findings.extend(file_findings)
            problems.extend(file_problems)
    return findings, problems


# --------------------------------------------------------------------------
# JSON surfaces
# --------------------------------------------------------------------------


def r2_collect_verification_records(
    root: Path, admitted: set[str]
) -> tuple[list[r2_Finding], list[str]]:
    findings: list[r2_Finding] = []
    problems: list[str] = []
    records: dict[str, dict[str, tuple[set[str], set[str]]]] = {}
    for name, raw in sorted(render_records(root).items()):
        name_match = r2_RECORD_FILENAME.match(name)
        if name_match is None:
            continue
        try:
            document = json.loads(raw)
        except json.JSONDecodeError as exc:
            problems.append(f"{name}: invalid JSON: {exc}")
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
                r2_Finding(
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
                        r2_Finding(
                            "verification-record",
                            "compatibility/cubism/verification",
                            f"{family} {older}>{newer} {label}",
                            tuple(sorted(lost)),
                            f"{family} record {newer} drops {len(lost)} {label} "
                            f"present in {older}",
                        )
                    )
    return findings, problems


def r2_collect_class_pins(
    root: Path, admitted: set[str]
) -> tuple[list[r2_Finding], list[str]]:
    findings: list[r2_Finding] = []
    problems: list[str] = []
    base = root / r2_CLASS_PINS_DIR
    if not base.is_dir():
        problems.append(f"{r2_CLASS_PINS_DIR}: directory missing")
        return findings, problems
    for pin in sorted(base.glob("*.json")):
        try:
            document = read_json(pin)
        except json.JSONDecodeError as exc:
            problems.append(f"{r2_CLASS_PINS_DIR}/{pin.name}: invalid JSON: {exc}")
            continue
        if not isinstance(document, dict):
            problems.append(f"{r2_CLASS_PINS_DIR}/{pin.name}: top-level object expected")
            continue
        declared = {str(v) for v in document}
        missing = missing_higher(declared, admitted)
        if missing:
            findings.append(
                r2_Finding(
                    "class-pins",
                    f"{r2_CLASS_PINS_DIR.as_posix()}/{pin.name}",
                    pin.name,
                    tuple(sorted(missing, key=parse_dotted)),
                    f"pins cover {sorted(declared)}",
                )
            )
    return findings, problems


def r2_collect_host_validation_tasks(
    root: Path, admitted: set[str]
) -> tuple[list[r2_Finding], list[str]]:
    findings: list[r2_Finding] = []
    problems: list[str] = []
    try:
        document = json.loads(read_source(root, r2_HOST_VALIDATION_TASKS))
    except json.JSONDecodeError as exc:
        raise r2_MonotonicityError(f"{r2_HOST_VALIDATION_TASKS}: invalid JSON: {exc}") from exc
    for name, task in sorted(document.get("tasks", {}).items()):
        declared = set()
        for token in task.get("versions", []):
            if not COMPACT_TOKEN.fullmatch(str(token)):
                problems.append(
                    f"{r2_HOST_VALIDATION_TASKS}: task {name} has non-exact "
                    f"version {token!r}"
                )
                continue
            declared.add(compact_to_dotted(str(token)))
        missing = missing_higher(declared, admitted)
        if missing:
            findings.append(
                r2_Finding(
                    "host-validation-task",
                    r2_HOST_VALIDATION_TASKS.as_posix(),
                    name,
                    tuple(sorted(missing, key=parse_dotted)),
                    f"task {name} runs on {sorted(declared)}",
                )
            )
    return findings, problems


def r2_collect_mcp_coverage(
    root: Path, admitted: set[str]
) -> tuple[list[r2_Finding], list[str]]:
    findings: list[r2_Finding] = []
    problems: list[str] = []
    path = root / r2_MCP_SDK_COVERAGE
    if not path.is_file():
        return findings, problems
    try:
        document = read_json(path)
    except json.JSONDecodeError as exc:
        problems.append(f"{r2_MCP_SDK_COVERAGE}: invalid JSON: {exc}")
        return findings, problems
    for index, entry in enumerate(document.get("entries", [])):
        versions = entry.get("supportedVersions")
        if not isinstance(versions, list):
            continue
        declared = {str(v) for v in versions}
        missing = missing_higher(declared, admitted)
        if missing:
            findings.append(
                r2_Finding(
                    "mcp-coverage",
                    r2_MCP_SDK_COVERAGE.as_posix(),
                    str(entry.get("sdkMethod", f"entries[{index}]")),
                    tuple(sorted(missing, key=parse_dotted)),
                    f"entry {index} covers {sorted(declared)}",
                )
            )
    return findings, problems


# --------------------------------------------------------------------------
# Exceptions
# --------------------------------------------------------------------------


def r2_load_exceptions(root: Path) -> tuple[list[dict], list[str]]:
    path = root / r2_EXCEPTIONS_FILE
    if not path.is_file():
        return [], []
    try:
        document = read_json(path)
    except json.JSONDecodeError as exc:
        raise r2_MonotonicityError(f"{r2_EXCEPTIONS_FILE}: invalid JSON: {exc}") from exc
    problems = []
    entries = document.get("exceptions")
    if not isinstance(entries, list):
        raise r2_MonotonicityError(f"{r2_EXCEPTIONS_FILE}: 'exceptions' list not found")
    for index, entry in enumerate(entries):
        for key in ("kind", "path", "site", "missing", "reason"):
            if key not in entry:
                problems.append(
                    f"{r2_EXCEPTIONS_FILE}: exception {index} lacks {key!r}"
                )
        if "pending" not in entry and "evidence" not in entry:
            problems.append(
                f"{r2_EXCEPTIONS_FILE}: exception {index} needs 'pending' or 'evidence'"
            )
        if not isinstance(entry.get("missing", []), list):
            problems.append(
                f"{r2_EXCEPTIONS_FILE}: exception {index} 'missing' must be a list"
            )
    return entries, problems


def r2_apply_exceptions(
    findings: list[r2_Finding], exceptions: list[dict]
) -> tuple[list[r2_Finding], list[r2_Finding], list[r2_Finding]]:
    """Split findings into (suppressed, live); append stale-exception findings."""
    suppressed: list[r2_Finding] = []
    live: list[r2_Finding] = []
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
                r2_Finding(finding.kind, finding.path, finding.site,
                        tuple(remainder), finding.detail)
            )
    for index, entry in enumerate(exceptions):
        if not used[index]:
            live.append(
                r2_Finding(
                    "stale-exception",
                    r2_EXCEPTIONS_FILE.as_posix(),
                    f"{entry.get('kind')} {entry.get('path')} {entry.get('site')}",
                )
            )
    return suppressed, live


# --------------------------------------------------------------------------
# Driver
# --------------------------------------------------------------------------


def r2_collect(root: Path) -> tuple[list[r2_Finding], list[str]]:
    admitted = admitted_versions(root)
    findings: list[r2_Finding] = []
    problems: list[str] = []
    for collector in (
        r2_collect_verification_records,
        r2_collect_class_pins,
        r2_collect_host_validation_tasks,
        r2_collect_mcp_coverage,
        r2_collect_java,
    ):
        partial, partial_problems = collector(root, admitted)
        findings.extend(partial)
        problems.extend(partial_problems)
    return findings, problems


def r2_check(root: Path) -> tuple[list[r2_Finding], list[r2_Finding]]:
    findings, problems = r2_collect(root)
    live = [r2_Finding("error", "", p) for p in problems]
    exceptions, exception_problems = r2_load_exceptions(root)
    live.extend(r2_Finding("error", r2_EXCEPTIONS_FILE.as_posix(), p) for p in exception_problems)
    suppressed, remainder = r2_apply_exceptions(findings, exceptions)
    live.extend(remainder)
    return suppressed, live

# R3


r3_LITERAL_EXCEPTIONS = Path("scripts/reviewed-release-literal-exceptions.json")
r3_LITERAL_EXCEPTIONS_FORMAT = "turboism.reviewed-release-literal-exceptions/v1"
r3_PREVIEW_WRAPPERS_DIR = Path("scripts/preview")
r3_GENERIC_RUNNER_NAME = "run-cubism-host-validation.sh"
r3_SHELL_COMPACT_VERSION = re.compile(r"\b(5[0-3]\d{2})\b")
r3_SHELL_DOTTED_VERSION = re.compile(r"\b(5\.\d{1,2}\.\d{2})\b")


r3_LiteralExceptionError = ReleaseCheckError


def r3_sort_versions(versions: set[str]) -> list[str]:
    return sorted(versions, key=cubism_version_key)


def wrapper_versions(root: Path, admitted: set[str]):
    for wrapper in sorted((root / r3_PREVIEW_WRAPPERS_DIR).glob("run-*.sh")):
        if wrapper.name == r3_GENERIC_RUNNER_NAME:
            continue
        code = "\n".join(line for line in read_source(wrapper).splitlines()
                         if not line.lstrip().startswith("#"))
        versions = {compact_to_dotted(token) for token in r3_SHELL_COMPACT_VERSION.findall(code)}
        versions.update(r3_SHELL_DOTTED_VERSION.findall(code))
        yield wrapper.relative_to(root).as_posix(), versions & admitted


def r3_collect_sites(root: Path) -> dict[tuple[str, str, str], int]:
    """Observed sites: (kind, path, sorted-version-csv) -> occurrence count.

    ``kind`` is ``collection`` for collection literals and ``file`` for the
    per-file residual set.
    """
    from generate_reviewed_releases import HOST, TABLE, VERSIONS
    generated_paths = {HOST.as_posix(), TABLE.as_posix(), VERSIONS.as_posix()}
    admitted = admitted_versions(root)
    sites: dict[tuple[str, str, str], int] = {}
    for base in r2_java_roots(root):
        if not base.is_dir():
            continue
        for source in sorted(base.rglob("*.java")):
            relative = source.relative_to(root).as_posix()
            # These outputs are checked byte-for-byte against the table by completeness.
            if relative in generated_paths or r2_JAVA_EXCLUDE.search(relative):
                continue
            stripped = strip_reviewed_ranges(strip_comments(
                read_source(source)
            ))
            spans: list[tuple[int, int]] = []
            for match in r2_ANNOTATION_OPEN.finditer(stripped):
                try:
                    end = r2_match_paren(stripped, match.end() - 1)
                except r2_MonotonicityError:
                    continue
                spans.append((match.start(), end))
            for match in r2_COLLECTION_OPEN.finditer(stripped):
                try:
                    end = r2_match_paren(stripped, match.end() - 1)
                except r2_MonotonicityError:
                    continue
                spans.append((match.start(), end))
                declared = r2_literal_versions(
                    stripped[match.start() : end], admitted
                )
                if declared:
                    key = (
                        "collection",
                        relative,
                        ",".join(r3_sort_versions(declared)),
                    )
                    sites[key] = sites.get(key, 0) + 1
            residual = list(stripped)
            for start, end in spans:
                for i in range(start, end):
                    if residual[i] != "\n":
                        residual[i] = " "
            declared = r2_literal_versions("".join(residual), admitted)
            if declared:
                key = ("file", relative, ",".join(r3_sort_versions(declared)))
                sites[key] = sites.get(key, 0) + 1
    for relative, declared in wrapper_versions(root, admitted):
        if declared:
            key = ("file", relative, ",".join(r3_sort_versions(declared)))
            sites[key] = sites.get(key, 0) + 1
    return sites


def r3_load_literal_exceptions(root: Path) -> tuple[list[dict], list[str]]:
    path = root / r3_LITERAL_EXCEPTIONS
    if not path.is_file():
        return [], []
    document = read_json(path)
    if document.get("format") != r3_LITERAL_EXCEPTIONS_FORMAT:
        raise r3_LiteralExceptionError(f"{path}: unexpected exception format")
    entries = document.get("sites")
    if not isinstance(entries, list):
        raise r3_LiteralExceptionError(f"{path}: 'sites' list not found")
    problems = []
    seen = set()
    for index, entry in enumerate(entries):
        entry.setdefault("kind", "file")
        if entry["kind"] not in ("collection", "file"):
            problems.append(f"site {index}: invalid exception kind")
        if not isinstance(entry.get("path"), str) or not entry["path"]:
            problems.append(f"site {index}: 'path' missing")
        if not isinstance(entry.get("evidence"), str) or not entry["evidence"].strip():
            problems.append(f"site {index}: 'evidence' must justify the exception")
        versions = entry.get("versions")
        if versions is not None and (not isinstance(versions, list) or not versions
                                    or versions != r3_sort_versions(set(versions))):
            problems.append(f"site {index}: versions must be nonempty, unique and sorted")
        if entry["kind"] == "collection" and versions is None:
            problems.append(f"site {index}: collection exception needs exact versions")
        count = entry.get("count", 1)
        if not isinstance(count, int) or count < 1:
            problems.append(f"site {index}: count must be positive")
        key = (entry["kind"], entry.get("path"), tuple(versions or ()))
        if key in seen:
            problems.append(f"site {index}: duplicate exception")
        seen.add(key)
    return entries, problems


def r3_bare_version_sets(root: Path) -> set[tuple[str, str]]:
    """File-level value-table exceptions never permit a bare Set.of roster."""
    bare = set()
    for base in r2_java_roots(root):
        if not base.is_dir():
            continue
        for source in base.rglob("*.java"):
            relative = source.relative_to(root).as_posix()
            if r2_JAVA_EXCLUDE.search(relative):
                continue
            text = strip_comments(read_source(source))
            for match in r2_COLLECTION_OPEN.finditer(text):
                end = r2_match_paren(text, match.end() - 1)
                body = text[match.end():end - 1]
                if re.fullmatch(r'\s*(?:"5\.\d{1,2}\.\d{2}"\s*,?\s*)+', body):
                    versions = r2_literal_versions(body, set())
                    bare.add((relative, ",".join(r3_sort_versions(versions))))
    return bare


def r3_check(root: Path) -> list[str]:
    observed = r3_collect_sites(root)
    entries, problems = r3_load_literal_exceptions(root)
    if problems:
        return problems
    bare = r3_bare_version_sets(root)
    used = set()
    for (kind, path, csv), count in sorted(observed.items()):
        explicit = [i for i, entry in enumerate(entries)
                    if entry["path"] == path and entry["kind"] == kind
                    and entry.get("versions") == csv.split(",")]
        general = [i for i, entry in enumerate(entries)
                   if entry["path"] == path and entry["kind"] == "file"
                   and "versions" not in entry]
        matches = explicit or ([] if (path, csv) in bare else general)
        if not matches:
            problems.append(f"unregistered {kind} version set at {path}: {csv}; "
                            "derive a range or document an evidence-backed exception")
            continue
        for index in matches:
            used.add(index)
            entry = entries[index]
            if kind == "collection" and index in explicit and entry.get("count", 1) != count:
                problems.append(f"{path}: set {csv} occurs {count} time(s), "
                                f"exception declares {entry.get('count', 1)}")
    for index, entry in enumerate(entries):
        if index not in used:
            problems.append(f"stale literal exception: {entry['path']}")
    return problems


# R4


sys.path.insert(0, str(Path(__file__).resolve().parent / "preview"))


r4_ROOT = ROOT
r4_MATRIX_PATH = Path("compatibility/cubism/compatibility-matrix.json")
r4_MANIFEST_PATH = HOST_TASKS

# Task -> explicit capability dependencies when taskGroupAliases/taskPluginAliases
# either do not cover the task or name a broader documentation group.
r4_TASK_EXPLICIT_CAPABILITIES: dict[str, tuple[str, ...]] = {
    "atlas-folders": ("cubism.texture-atlas.layout.write", "cubism.editor-model.read", "cubism.editor-model.write", "cubism.editor-model.texture.read"),
    "atlas-image": ("cubism.texture-atlas.data-model-hook",),
    "backup-interactive": (
        "cubism.autobackup.backup",
        "cubism.autobackup.settings",
    ),
    "bounding-box-overlay": (
        "cubism.editor-ui.bounding-box-overlay-button",
        "cubism.editor-model.read",
    ),
    "boundingbox-warp-mirror": (
        "cubism.editor-model.warp-mirror",
        "cubism.editor-ui.bounding-box-overlay-button",
    ),
    "dialog-automation": ("cubism.editor-model.read",),
    "edit-protocol": (
        "cubism.integration.edit-api-bridge.dispatch",
        "cubism.integration.external-app-settings.edit-toggle",
    ),
    "external-psd-edit": (
        "cubism.editor-model.psd.raw-image.export",
        "cubism.editor-model.psd.raw-image.replace",
        "cubism.editor-model.psd.snapshot.read",
    ),
    "external-psd-fixture-preparation": (
        "cubism.editor-model.psd.raw-image.export",
        "cubism.editor-model.psd.raw-image.replace",
        "cubism.editor-model.psd.snapshot.read",
    ),
    "graal-script": (
        "cubism.editor-model.read",
        "cubism.editor-model.write",
    ),
    "host-locale": ("cubism.editor-model.read",),
    "mcp": (
        "cubism.editor-model.read",
        "cubism.editor-model.write",
        "cubism.editor-history.read",
        "cubism.editor-history.move",
        "cubism.workspace.control",
    ),
    "mesh-edit": ("cubism.mesh-tool.session",),
    "model-import-3d": (
        "cubism.editor-model.object-hierarchy.art-mesh.create",
        "cubism.editor-model.art-mesh.write",
        "cubism.editor-model.art-mesh.parameter-bindings.write",
        "cubism.editor-model.texture.register-bind",
        "cubism.editor-model.render.read",
    ),
    "model-import-3d-user-entry": (
        "cubism.editor-model.object-hierarchy.art-mesh.create",
        "cubism.editor-model.art-mesh.write",
    ),
    "official-core": ("cubism.core.owned-moc.read",),
    "official-viewer": ("cubism.core.owned-moc.read",),
    "plugin-management-chooser": ("cubism.editor-model.read",),
    "resource": ("turboism.performance.stats.read",),
    "semantic-history": (
        "cubism.editor-history.move",
        "cubism.editor-history.read",
        "cubism.editor-history.semantic-read",
    ),
    "semantic-history-native-ui": (
        "cubism.editor-history.move",
        "cubism.editor-history.read",
        "cubism.editor-history.semantic-read",
    ),
    "semantic-history-primary": (
        "cubism.editor-history.move",
        "cubism.editor-history.read",
        "cubism.editor-history.semantic-read",
    ),
    "separate-save-path": ("cubism.project.read",),
    "session-standard": (
        "cubism.editor-model.texture.read",
        "cubism.editor-model.psd.raw-image.export",
        "ui.status.notify",
        "cubism.editor-model.art-mesh-clip-mask.read",
        "cubism.project.read",
        "cubism.core.owned-moc.read",
    ),
    "settings-page": ("ui.status.notify",),
    "theme": (
        "cubism.editor-model.native-control-appearance.read",
        "cubism.editor-model.native-control-appearance.write",
    ),
    "update-check": (
        "cubism.startup.suppression",
        "ui.canvas.hint",
    ),
    "warp-deformer-alt-symmetry": ("cubism.editor-model.warp-mirror",),
}

# Task -> optimization/hook class-pin table in compatibility-matrix.json.
r4_TASK_CLASS_PINS: dict[str, tuple[str, ...]] = {
    "boundingbox-warp-mirror": ("warp-alt-mirror",),
    "float-array": ("float-array-parse",),
    "image-archive": ("image-archive-reuse",),
    "incremental-update": ("incremental-update",),
    "mesh-edit": ("mesh-mirror",),
    "model-import-3d-user-entry": ("mesh-interpolation",),
    "model-update-skip": ("model-update-skip",),
    "startup-suppression": ("startup-suppression",),
    "texture-upload": ("texture-upload-preparation",),
    "warp-deformer-alt-symmetry": ("warp-alt-mirror",),
    "warp-position": ("warp-position-projection",),
}


def r4_derive_all_task_versions(
    root: Path = r4_ROOT,
) -> tuple[dict[str, list[str]], list[str]]:
    """Return ({task_name: [compact_versions, ...]}, problems)."""
    problems: list[str] = []
    matrix_file = root / r4_MATRIX_PATH
    manifest_file = root / r4_MANIFEST_PATH
    try:
        matrix = read_json(matrix_file)
    except Exception as exc:
        return {}, [f"{r4_MATRIX_PATH}: cannot read compatibility matrix: {exc}"]
    try:
        manifest = read_json(manifest_file)
    except Exception as exc:
        return {}, [f"{r4_MANIFEST_PATH}: cannot read task manifest: {exc}"]

    reviewed = sorted(admitted_versions(root), key=parse_dotted)
    reviewed_set = set(reviewed)
    cap_admitted: dict[str, set[str]] = {}
    for raw in render_records(root).values():
        record = json.loads(raw)
        if record.get("status") != "VERIFIED_STATIC":
            continue
        for capability in record.get("capabilityIds", []):
            cap_admitted.setdefault(capability, set()).add(record["cubismVersion"])
    group_caps: dict[str, list[str]] = {
        row["id"]: list(row.get("capabilities", []))
        for row in matrix.get("capabilityGroups", [])
    }
    plugin_supported: dict[str, set[str]] = {
        row["directory"]: set(row.get("supportedVersions", []))
        for row in matrix.get("plugins", [])
    }
    pin_versions = {
        path.stem: set(read_json(path))
        for path in (root / r2_CLASS_PINS_DIR).glob("*.json")
    }
    task_plugin_aliases: dict[str, str] = dict(
        matrix.get("taskPluginAliases", {})
    )
    task_group_aliases: dict[str, str] = dict(
        matrix.get("taskGroupAliases", {})
    )

    derived: dict[str, list[str]] = {}
    for task_name in sorted(manifest.get("tasks", {})):
        admitted = set(reviewed_set)
        matched_source = False

        for cap in TASK_REQUIRED_CAPABILITIES.get(task_name, ()):
            if cap not in cap_admitted:
                problems.append(
                    f"task {task_name}: required capability {cap!r} missing from {r4_MATRIX_PATH}"
                )
            else:
                admitted &= cap_admitted[cap]
                matched_source = True

        if task_name in r4_TASK_EXPLICIT_CAPABILITIES:
            for cap in r4_TASK_EXPLICIT_CAPABILITIES[task_name]:
                if cap not in cap_admitted:
                    problems.append(
                        f"task {task_name}: capability {cap!r} missing from {r4_MATRIX_PATH}"
                    )
                else:
                    admitted &= cap_admitted[cap]
                    matched_source = True
        elif task_name in task_group_aliases:
            group = task_group_aliases[task_name]
            caps = group_caps.get(group, [])
            if not caps:
                problems.append(
                    f"task {task_name}: group {group!r} has no capabilities in {r4_MATRIX_PATH}"
                )
            for cap in caps:
                admitted &= cap_admitted.get(cap, set())
                matched_source = True

        if task_name in task_plugin_aliases:
            plugin_dir = task_plugin_aliases[task_name]
            if plugin_dir not in plugin_supported:
                problems.append(
                    f"task {task_name}: plugin {plugin_dir!r} missing from {r4_MATRIX_PATH}"
                )
            else:
                admitted &= plugin_supported[plugin_dir]
                matched_source = True

        for pin_name in r4_TASK_CLASS_PINS.get(task_name, ()):
            if pin_name not in pin_versions:
                problems.append(
                    f"task {task_name}: classPin {pin_name!r} missing from {r4_MATRIX_PATH}"
                )
            else:
                admitted &= pin_versions[pin_name]
                matched_source = True

        if not matched_source:
            problems.append(
                f"task {task_name}: no capability, plugin, or classPin mapping declared"
            )

        ordered_dotted = sorted(admitted, key=parse_dotted)
        derived[task_name] = [dotted_to_compact(v) for v in ordered_dotted]

    return derived, problems


def r4_derive_task_versions(task_name: str, root: Path = r4_ROOT) -> tuple[str, ...]:
    """Return the derived compact version tuple for ``task_name``."""
    derived, problems = r4_derive_all_task_versions(root)
    if problems:
        raise RuntimeError("; ".join(problems))
    return tuple(derived[task_name])


def r4_check(root: Path = r4_ROOT) -> list[str]:
    failures: list[str] = []
    derived, problems = r4_derive_all_task_versions(root)
    failures.extend(problems)

    manifest_file = root / r4_MANIFEST_PATH
    try:
        manifest = read_json(manifest_file)
    except Exception as exc:
        return [f"{r4_MANIFEST_PATH}: cannot read task manifest: {exc}"]

    registry_entries: dict[tuple[str, str], dict] = {}
    if (root / r3_LITERAL_EXCEPTIONS).is_file():
        entries, reg_problems = r3_load_literal_exceptions(root)
        failures.extend(reg_problems)
        for entry in entries:
            registry_entries[(entry["kind"], entry["path"])] = entry

    for task_name, task_data in sorted(manifest.get("tasks", {}).items()):
        actual = list(task_data.get("versions", []))
        expected = derived.get(task_name, [])
        if actual != expected:
            reg_key = ("file", f"{r4_MANIFEST_PATH.as_posix()}#{task_name}")
            reg_entry = registry_entries.get(reg_key)
            expected_dotted = [
                compact_to_dotted(v) for v in actual
            ]
            if (
                reg_entry is None
                or reg_entry.get("versions") != expected_dotted
            ):
                failures.append(
                    f"{r4_MANIFEST_PATH}: task {task_name!r} versions {actual} != "
                    f"derived admitted versions {expected}"
                )

    admitted = admitted_versions(root)
    for relative, found in wrapper_versions(root, admitted):
        if found:
            sorted_found = r3_sort_versions(found)
            reg_entry = registry_entries.get(("file", relative))
            if reg_entry is None or reg_entry.get("versions") != sorted_found:
                failures.append(
                    f"unregistered wrapper version enumeration at {relative}: "
                    f"{','.join(sorted_found)}; use host-validation-env.sh "
                    f"manifest helpers or document an evidence-backed exception in "
                    f"{r3_LITERAL_EXCEPTIONS}"
                )

    return failures


def r4_update(root: Path = r4_ROOT) -> int:
    derived, problems = r4_derive_all_task_versions(root)
    if problems:
        for problem in problems:
            print(f"FAIL: {problem}", file=sys.stderr)
        return 2
    manifest_file = root / r4_MANIFEST_PATH
    manifest = read_json(manifest_file)
    for task_name, task_data in manifest.get("tasks", {}).items():
        if task_name in derived:
            task_data["versions"] = derived[task_name]
    manifest_file.write_text(
        dump_json(manifest, indent=2, ensure_ascii=False),
        encoding="utf-8",
    )
    print(f"updated {r4_MANIFEST_PATH} with {len(derived)} derived task(s)")
    return 0

# INDEX


index_VERIFICATION_DIR = VERIFICATION_DIR
index_MANIFEST_ROOT = Path("runtime/src/main/java/dev/turboism/mapping/verification")
index_TEMPLATE_ROOT = Path("scripts/verification-sources/templates")

index_VERIFICATION_ID_LITERAL = re.compile(r'"([a-z0-9][a-z0-9._-]*\.static)"')
index_TEMPLATE_RECORD_PIN = re.compile(
    r"\$\{record:(cubism-\d+\.\d+\.\d+-[^}:]+\.json):verificationId\}"
)
index_RECORD_FILENAME = re.compile(r"^cubism-(\d+\.\d+\.\d+)-.+\.json$")


index_IndexError_ = ReleaseCheckError


def index_on_disk_records(root: Path) -> list[str]:
    base = root / index_VERIFICATION_DIR
    return sorted(path.name for path in base.glob("*.json"))


def index_record_ids(root: Path) -> dict[str, str]:
    """Maps verificationId -> record filename for every record on disk."""
    ids: dict[str, str] = {}
    problems = []
    for name in index_on_disk_records(root):
        path = root / index_VERIFICATION_DIR / name
        try:
            document = read_json(path)
        except Exception as exc:
            raise index_IndexError_(f"{name}: invalid JSON: {exc}") from exc
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
        filename_match = index_RECORD_FILENAME.match(name)
        if filename_match and filename_match.group(1) not in verification_id:
            problems.append(
                f"{name}: verificationId {verification_id!r} does not carry "
                f"the filename version {filename_match.group(1)}"
            )
    if problems:
        raise index_IndexError_("; ".join(problems))
    return ids


def index_manifest_verification_ids(root: Path) -> dict[str, str]:
    """Maps each ``*.static`` id pinned in manifest sources or generated
    templates to its declaring file.

    Checked-in sources pin ``verificationId`` string literals directly; the
    generated targets' templates pin them through ``${record:<file>:
    verificationId}`` placeholders, which resolve through the record filename.
    """
    ids: dict[str, str] = {}
    base = root / index_MANIFEST_ROOT
    if base.is_dir():
        for source in sorted(base.rglob("*.java")):
            for literal in index_VERIFICATION_ID_LITERAL.findall(read_source(source)):
                ids.setdefault(literal, source.name)
    name_to_id = {name: vid for vid, name in index_record_ids(root).items()}
    for record_name, source in index_template_record_pins(root).items():
        if record_name in name_to_id:
            ids.setdefault(name_to_id[record_name], f"{source} (via ${{record:{record_name}}})")
    return ids


def index_template_record_pins(root: Path) -> dict[str, str]:
    """Maps record filenames referenced by template placeholders to the file."""
    pins: dict[str, str] = {}
    template_base = root / index_TEMPLATE_ROOT
    if template_base.is_dir():
        for source in sorted(template_base.rglob("*.java")):
            for record_name in index_TEMPLATE_RECORD_PIN.findall(read_source(source)):
                pins.setdefault(record_name, source.name)
    return pins


def index_check(root: Path) -> list[str]:
    violations = []
    try:
        check_records(render_records(root), root / index_VERIFICATION_DIR)
    except (ValueError, OSError, KeyError, TypeError) as exc:
        violations.append(f"materialized records differ from table/family sources: {exc}")
    ids = index_record_ids(root)
    manifest_ids = index_manifest_verification_ids(root)
    if not manifest_ids:
        violations.append(
            f"{index_MANIFEST_ROOT}: no verification id literals found; "
            "the manifest-pin cross-check would be vacuous"
        )
    for verification_id, source in sorted(manifest_ids.items()):
        if verification_id not in ids:
            violations.append(
                f"{source}: pins verificationId {verification_id!r} with no "
                "matching record under build/generated/verification/"
            )
    for record_name, source in sorted(index_template_record_pins(root).items()):
        if record_name not in ids.values():
            violations.append(
                f"{source}: placeholder pins missing record {record_name}"
            )
    return violations


def check(root: Path) -> list[str]:
    failures = ["R1: " + p for p in r1_check(root)]
    failures.extend("R1: " + p for p in index_check(root))
    failures.extend("R1: " + p for p in r1_family_range_coverage(root))
    _, live = r2_check(root)
    failures.extend("R2: " + f.message() for f in live)
    failures.extend("R3: " + p for p in r3_check(root))
    failures.extend("R4: " + p for p in r4_check(root))
    return failures


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("root", nargs="?", type=Path, default=repo_root())
    parser.add_argument("--update-host-tasks", action="store_true",
                        help="refresh task version declarations from R4 source intersections")
    args = parser.parse_args()
    try:
        if args.update_host_tasks:
            return r4_update(args.root.resolve())
        failures = check(args.root.resolve())
    except (ValueError, OSError, ReleaseCheckError) as exc:
        failures = [str(exc)]
    for failure in failures:
        print("FAIL: " + failure)
    if failures:
        return 1
    print("PASS: reviewed releases R1–R4")
    return 0


# Scheduler callers share R4's derivation instead of restating task coverage.
derive_all_task_versions = r4_derive_all_task_versions
derive_task_versions = r4_derive_task_versions

if __name__ == "__main__":
    raise SystemExit(main())
