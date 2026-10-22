#!/usr/bin/env python3
"""Check generated Java == reviewed-releases.json and remaining version rosters.

Bootstrap records, profiles, prose and close probes still cover the table.
Host tasks and class pins may cover subsets, but never unknown versions.
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

from turboism_dev.json_io import read_json
from preview.reviewed_host_artifacts import SOURCE_RELATIVE, load_releases
import generate_reviewed_releases as generated
from turboism_dev.paths import repo_root

ROOT = repo_root()

CANONICAL = "reviewed-releases.json"
RELEASES = Path(SOURCE_RELATIVE)
REVIEWED_HOST_ARTIFACTS = Path(
    "runtime/src/main/java/dev/turboism/mapping/verification/ReviewedHostArtifacts.java"
)
COMPATIBILITY_CATALOG = Path(
    "scripts/verification-sources/families/compatibility-catalog.json"
)
AVAILABILITY_POLICY = Path(
    "runtime/src/main/java/dev/turboism/core/plugin/context/CubismEditorAvailabilityPolicy.java"
)
HOST_VALIDATION_TASKS = Path("scripts/preview/host-validation-tasks.json")
HOST_VALIDATION_PY = Path("scripts/preview/host_validation.py")
PROFILES_DIR = Path("compatibility/cubism/profiles/draft")
INDEX_MD = Path("compatibility/cubism/index.md")
CLASS_PINS_DIR = Path(
    "runtime/src/main/resources/dev/turboism/adapter/cubism/class-pins"
)
PROBE_CLOSE_ROUTES = (
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
CLOSE_ROUTE_SWITCH = re.compile(
    r"hostCloseRoute\(final String hostVersion\).*?switch \(hostVersion\) \{(.*?)\};",
    re.DOTALL,
)
COMPACT_LITERAL = re.compile(r'"(5\d{3})"')

COMPACT = re.compile(r"^5\d{3}$")

RECORD_FILENAME = re.compile(r'"cubism-(\d+\.\d+\.\d+)-[^"]+\.json"')
PROFILE_FILENAME = re.compile(r"^cubism-(\d+\.\d+\.\d+)\.json$")
INDEX_VERSION = re.compile(r"\b5\.\d{1,2}\.\d{2}\b")


class CompletenessError(Exception):
    """Raised when a restatement site cannot be parsed at all."""


def dotted(major: str, minor: str, patch: str) -> str:
    return f"{int(major)}.{int(minor)}.{int(patch):0>2}"


def compact_to_dotted(token: str) -> str:
    if not COMPACT.fullmatch(token):
        raise CompletenessError(f"unsupported compact version token: {token!r}")
    return dotted(token[0], token[1:-2], token[-2:])


def _read(root: Path, relative: Path) -> str:
    path = root / relative
    try:
        return path.read_text(encoding="utf-8")
    except OSError as exc:
        raise CompletenessError(f"unable to read {relative}: {exc}") from exc


def reviewed_host_artifacts(root: Path) -> tuple[set[str], list[str]]:
    try:
        versions = {r['version'] for r in load_releases(root / RELEASES)}
        return versions, generated.check(root)
    except (ValueError, OSError) as exc:
        raise CompletenessError(str(exc)) from exc


def compatibility_catalog(root: Path) -> set[str]:
    try:
        document = json.loads(_read(root, COMPATIBILITY_CATALOG))
    except json.JSONDecodeError as exc:
        raise CompletenessError(f"{COMPATIBILITY_CATALOG}: invalid JSON: {exc}") from exc
    records = document.get("records")
    if not isinstance(records, list) or not records:
        raise CompletenessError(f"{COMPATIBILITY_CATALOG}: records list not found")
    versions = set()
    for entry in records:
        match = re.fullmatch(r"cubism-(5\.\d{1,2}\.\d{2})-[^.]+\.json", str(entry))
        if match:
            versions.add(match.group(1))
    return versions


def derived_consumers(root: Path) -> list[str]:
    """Reject a stale consumer that stops using the generated table."""
    expected = {
        AVAILABILITY_POLICY: 'REVIEWED_VERSIONS = ReviewedReleaseTable.versions()',
        Path('runtime/src/main/java/dev/turboism/mapping/verification/ReviewedCubismReleases.java'): 'ReviewedReleaseTable.find(version)',
        Path('runtime/src/main/java/dev/turboism/mapping/verification/CorePublicApiTrustRoots.java'): 'ReviewedReleaseTable.find(profile)',
        Path('runtime/src/main/java/dev/turboism/adapter/cubism/core/CoreVersionExpectation.java'): 'ReviewedReleaseTable.find(profile)',
        HOST_VALIDATION_PY: 'reviewed_host_artifacts.load()',
    }
    return [f'{path}: must derive reviewed versions from the table'
            for path, expression in expected.items() if expression not in _read(root, path)]


def materialized_record_versions(root: Path) -> set[str]:
    return {v for path in (root / "build/generated/verification").glob("*.json")
            for v in RECORD_FILENAME.findall(f'"{path.name}"')}


def host_validation_tasks(root: Path) -> tuple[set[str], list[str]]:
    try:
        document = json.loads(_read(root, HOST_VALIDATION_TASKS))
    except json.JSONDecodeError as exc:
        raise CompletenessError(f"{HOST_VALIDATION_TASKS}: invalid JSON: {exc}") from exc
    versions: set[str] = set()
    problems = []
    for name, task in sorted(document.get("tasks", {}).items()):
        for token in task.get("versions", []):
            if not COMPACT.fullmatch(str(token)):
                problems.append(
                    f"{HOST_VALIDATION_TASKS}: task {name} has non-exact version {token!r}"
                )
                continue
            versions.add(compact_to_dotted(str(token)))
    return versions, problems



def draft_profiles(root: Path) -> tuple[set[str], list[str]]:
    base = root / PROFILES_DIR
    versions = set()
    problems = []
    for profile in sorted(base.glob("cubism-*.json")):
        match = PROFILE_FILENAME.match(profile.name)
        if match is None:
            continue
        version = match.group(1)
        versions.add(version)
        try:
            document = read_json(profile)
        except json.JSONDecodeError as exc:
            problems.append(f"{profile.name}: invalid JSON: {exc}")
            continue
        if document.get("profileId") != f"cubism-{version}":
            problems.append(
                f"{profile.name}: profileId {document.get('profileId')!r} "
                f"!= cubism-{version}"
            )
        if document.get("cubismVersion") != version:
            problems.append(
                f"{profile.name}: cubismVersion {document.get('cubismVersion')!r} "
                f"!= {version}"
            )
    return versions, problems


def index_md(root: Path) -> set[str]:
    return set(INDEX_VERSION.findall(_read(root, INDEX_MD)))


SHA256_HEX = re.compile(r"^[0-9a-f]{64}$")


def class_pin_tables(root: Path) -> tuple[set[str], list[str]]:
    """Union of version keys across class-pins/*.json plus per-file problems."""
    base = root / CLASS_PINS_DIR
    versions: set[str] = set()
    problems: list[str] = []
    if not base.is_dir():
        problems.append(f"{CLASS_PINS_DIR}: directory missing")
        return versions, problems
    for pin in sorted(base.glob("*.json")):
        try:
            document = read_json(pin)
        except json.JSONDecodeError as exc:
            problems.append(f"{CLASS_PINS_DIR}/{pin.name}: invalid JSON: {exc}")
            continue
        if not isinstance(document, dict):
            problems.append(f"{CLASS_PINS_DIR}/{pin.name}: top-level object expected")
            continue
        for version, block in document.items():
            versions.add(str(version))
            if not isinstance(block, dict):
                problems.append(
                    f"{CLASS_PINS_DIR}/{pin.name}: version '{version}' is not an object"
                )
                continue
            for class_name, sha in block.items():
                if not isinstance(sha, str) or not SHA256_HEX.fullmatch(sha):
                    problems.append(
                        f"{CLASS_PINS_DIR}/{pin.name}: '{class_name}' under "
                        f"'{version}' is not a 64-char lowercase hex SHA-256"
                    )
    return versions, problems


def probe_close_routes(root: Path) -> dict[str, set[str]]:
    """Compact version codes admitted by each probe's hostCloseRoute switch."""
    routes: dict[str, set[str]] = {}
    for relative in PROBE_CLOSE_ROUTES:
        try:
            text = _read(root, relative)
        except CompletenessError:
            continue  # Minimal fixture roots legitimately lack probe sources.
        match = CLOSE_ROUTE_SWITCH.search(text)
        if match is None:
            raise CompletenessError(f"{relative}: hostCloseRoute switch not found")
        routes[f"{relative} hostCloseRoute versions"] = {
            compact_to_dotted(token) for token in COMPACT_LITERAL.findall(match.group(1))
        }
    return routes


def collect(root: Path) -> tuple[dict[str, set[str]], list[str]]:
    canonical, problems = reviewed_host_artifacts(root)
    tasks, task_problems = host_validation_tasks(root)
    profiles, profile_problems = draft_profiles(root)
    pins, pin_problems = class_pin_tables(root)
    problems.extend(derived_consumers(root))
    problems.extend(task_problems)
    problems.extend(profile_problems)
    problems.extend(pin_problems)
    sites = {
        CANONICAL: canonical,
        "compatibility-catalog.json record versions": compatibility_catalog(root),
        "materialized verification records": materialized_record_versions(root),
        "host-validation-tasks.json task versions (subset)": tasks,
        "profiles/draft/cubism-*.json": profiles,
        "compatibility/cubism/index.md": index_md(root),
        "class-pins/*.json version keys (subset)": pins,
        **probe_close_routes(root),
    }
    return sites, problems


def check(root: Path) -> list[str]:
    sites, problems = collect(root)
    canonical = sites[CANONICAL]
    violations = list(problems)
    for name, versions in sites.items():
        if name == CANONICAL:
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


def run(root: Path) -> int:
    try:
        violations = check(root)
    except CompletenessError as exc:
        print(f"FAIL: {exc}", file=sys.stderr)
        return 2
    for violation in violations:
        print(f"FAIL: {violation}")
    if violations:
        print(f"\n{len(violations)} version-set finding(s)")
        return 1
    sites, _ = collect(root)
    admitted = sorted(sites[CANONICAL])
    print(f"PASS: admitted Cubism version set {admitted} is consistent across "
          f"{len(sites)} restatements")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("root", nargs="?", default=str(ROOT))
    args = parser.parse_args()
    return run(Path(args.root).resolve())


if __name__ == "__main__":
    raise SystemExit(main())
