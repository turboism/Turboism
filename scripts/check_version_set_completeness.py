#!/usr/bin/env python3
"""Fail when the admitted Cubism Editor version set drifts between restatements.

``ReviewedHostArtifacts`` is the canonical declaration. Every other surface that
restates the admitted exact versions must carry the identical set:

- ``ReviewedHostArtifacts.CUBISM_*_VERSION`` constants and artifact constants
- ``CubismEditorReleaseDetector.pinnedBuild`` switch arms
- ``CubismEditorAvailabilityPolicy.REVIEWED_VERSIONS``
- ``bootstrap/build.gradle.kts`` verification record filenames
- ``scripts/preview/host-validation-tasks.json`` task versions (union; every
  listed version must be admitted)
- ``compatibility/cubism/profiles/draft/cubism-*.json`` filenames, profileIds
  and cubismVersion fields
- ``compatibility/cubism/index.md`` exact-version prose
- ``scripts/preview/host_validation.py`` supported-version whitelist

Usage: check_version_set_completeness.py [repo-root]
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

CANONICAL = "ReviewedHostArtifacts.CUBISM_*_VERSION"
REVIEWED_HOST_ARTIFACTS = Path(
    "runtime/src/main/java/dev/turboism/mapping/verification/ReviewedHostArtifacts.java"
)
RELEASE_DETECTOR = Path(
    "runtime/src/main/java/dev/turboism/mapping/verification/CubismEditorReleaseDetector.java"
)
AVAILABILITY_POLICY = Path(
    "runtime/src/main/java/dev/turboism/core/plugin/context/CubismEditorAvailabilityPolicy.java"
)
BOOTSTRAP_BUILD = Path("bootstrap/build.gradle.kts")
HOST_VALIDATION_TASKS = Path("scripts/preview/host-validation-tasks.json")
HOST_VALIDATION_PY = Path("scripts/preview/host_validation.py")
PROFILES_DIR = Path("compatibility/cubism/profiles/draft")
INDEX_MD = Path("compatibility/cubism/index.md")

DOTTED = re.compile(r"^5\.\d{1,2}\.\d{2}$")
COMPACT = re.compile(r"^5\d{3}$")

VERSION_CONSTANT = re.compile(
    r'CUBISM_(\d+)_(\d+)_(\d+)_VERSION\s*=\s*"([^"]+)"'
)
ARTIFACT_CONSTANT = re.compile(
    r'CUBISM_(\d+)_(\d+)_(\d+)\s*=\s*new HostArtifactDigest'
)
RECORD_FILENAME = re.compile(r'"cubism-(\d+\.\d+\.\d+)-[^"]+\.json"')
PROFILE_FILENAME = re.compile(r"^cubism-(\d+\.\d+\.\d+)\.json$")
INDEX_VERSION = re.compile(r"\b5\.\d{1,2}\.\d{2}\b")
SUPPORTED_SET = re.compile(r"version not in \{([^}]*)\}")


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
    text = _read(root, REVIEWED_HOST_ARTIFACTS)
    problems = []
    versions = set()
    artifacts = set()
    for major, minor, patch, literal in VERSION_CONSTANT.findall(text):
        expected = dotted(major, minor, patch)
        if literal != expected:
            problems.append(
                f"{REVIEWED_HOST_ARTIFACTS}: CUBISM_{major}_{minor}_{patch}_VERSION "
                f"is {literal!r}, expected {expected!r}"
            )
        versions.add(expected)
    for major, minor, patch in ARTIFACT_CONSTANT.findall(text):
        artifacts.add(dotted(major, minor, patch))
    if not versions:
        problems.append(f"{REVIEWED_HOST_ARTIFACTS}: no CUBISM_*_VERSION constants found")
    if artifacts != versions:
        problems.append(
            f"{REVIEWED_HOST_ARTIFACTS}: artifact constants {sorted(artifacts)} "
            f"!= version constants {sorted(versions)}"
        )
    return versions, problems


def release_detector(root: Path) -> set[str]:
    text = _read(root, RELEASE_DETECTOR)
    match = re.search(r"pinnedBuild\(final String version\)\s*\{(.*?)\n\s*\}", text, re.DOTALL)
    if match is None:
        raise CompletenessError(f"{RELEASE_DETECTOR}: pinnedBuild switch not found")
    return set(re.findall(r'case\s+"(5\.\d{1,2}\.\d{2})"', match.group(1)))


def availability_policy(root: Path) -> set[str]:
    text = _read(root, AVAILABILITY_POLICY)
    match = re.search(r"REVIEWED_VERSIONS\s*=\s*List\.of\(([^)]*)\)", text)
    if match is None:
        raise CompletenessError(f"{AVAILABILITY_POLICY}: REVIEWED_VERSIONS not found")
    return set(re.findall(r'"(5\.\d{1,2}\.\d{2})"', match.group(1)))


def bootstrap_records(root: Path) -> set[str]:
    return set(RECORD_FILENAME.findall(_read(root, BOOTSTRAP_BUILD)))


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


def host_validation_py(root: Path) -> set[str]:
    text = _read(root, HOST_VALIDATION_PY)
    match = SUPPORTED_SET.search(text)
    if match is None:
        raise CompletenessError(
            f"{HOST_VALIDATION_PY}: supported-version whitelist not found"
        )
    return {
        compact_to_dotted(token)
        for token in re.findall(r'"(\d+)"', match.group(1))
    }


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
            document = json.loads(profile.read_text(encoding="utf-8"))
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


def collect(root: Path) -> tuple[dict[str, set[str]], list[str]]:
    canonical, problems = reviewed_host_artifacts(root)
    tasks, task_problems = host_validation_tasks(root)
    profiles, profile_problems = draft_profiles(root)
    problems.extend(task_problems)
    problems.extend(profile_problems)
    sites = {
        CANONICAL: canonical,
        "CubismEditorReleaseDetector.pinnedBuild": release_detector(root),
        "CubismEditorAvailabilityPolicy.REVIEWED_VERSIONS": availability_policy(root),
        "bootstrap/build.gradle.kts record list": bootstrap_records(root),
        "host-validation-tasks.json task versions": tasks,
        "profiles/draft/cubism-*.json": profiles,
        "compatibility/cubism/index.md": index_md(root),
        "host_validation.py supported versions": host_validation_py(root),
    }
    return sites, problems


def check(root: Path) -> list[str]:
    sites, problems = collect(root)
    canonical = sites[CANONICAL]
    violations = list(problems)
    for name, versions in sites.items():
        if name == CANONICAL:
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
