"""Strict release version and source-ref validation."""
from __future__ import annotations

import re
import subprocess
from pathlib import Path

from .contracts import ReleaseError


STRICT_VERSION = re.compile(r"^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$")
SOURCE_SHA = re.compile(r"^[0-9a-f]{40}$")
FRAMEWORK_VERSION = re.compile(
    r'turboismFrameworkVersion"\]\s*=\s*"((?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*))"'
)
CHANGELOG_HEADING = re.compile(
    r"^## \[((?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*))\] - (\d{4}-\d{2}-\d{2})$",
    re.MULTILINE,
)


def framework_version(repo_root: Path) -> str:
    path = repo_root / "gradle/common-java.gradle.kts"
    try:
        text = path.read_text(encoding="utf-8")
    except OSError as failure:
        raise ReleaseError(f"cannot read framework version from {path}: {failure}") from failure
    matches = FRAMEWORK_VERSION.findall(text)
    if len(matches) != 1:
        raise ReleaseError(f"expected exactly one framework version in {path}, found {len(matches)}")
    return matches[0]


def changelog_entry(repo_root: Path, version: str) -> dict[str, str]:
    if not STRICT_VERSION.fullmatch(version):
        raise ReleaseError(f"invalid release version: {version}")
    path = repo_root / "CHANGELOG.md"
    try:
        text = path.read_text(encoding="utf-8")
    except OSError as failure:
        raise ReleaseError(f"cannot read changelog {path}: {failure}") from failure
    matches = [(match.group(1), match.group(2)) for match in CHANGELOG_HEADING.finditer(text)]
    version_dates = [date for candidate, date in matches if candidate == version]
    if len(version_dates) != 1:
        raise ReleaseError(f"CHANGELOG.md must contain exactly one release section for {version}")
    extractor = repo_root / "scripts/release/extract-release-notes.py"
    completed = _run(
        ["python3", str(extractor), str(path), version],
        repo_root,
        "extract release notes",
    )
    import hashlib

    return {
        "date": version_dates[0],
        "sha256": hashlib.sha256(completed.stdout.encode("utf-8")).hexdigest(),
    }


def git_source(repo_root: Path, *, require_tag: bool) -> dict[str, str | None]:
    revision = _run(["git", "rev-parse", "HEAD"], repo_root, "resolve HEAD").stdout.strip()
    if not SOURCE_SHA.fullmatch(revision):
        raise ReleaseError("git HEAD is not a 40-character lowercase commit SHA")
    exact_tags = [
        line
        for line in _run(
            ["git", "tag", "--points-at", revision],
            repo_root,
            "resolve tags at HEAD",
        ).stdout.splitlines()
        if re.fullmatch(r"v" + STRICT_VERSION.pattern[1:-1], line)
    ]
    if len(exact_tags) > 1:
        raise ReleaseError(f"more than one release tag points at HEAD: {exact_tags}")
    tag = exact_tags[0] if exact_tags else None
    if require_tag and tag is None:
        raise ReleaseError("framework publication requires an exact vMAJOR.MINOR.PATCH tag at HEAD")
    if tag is not None:
        kind = _run(
            ["git", "cat-file", "-t", f"refs/tags/{tag}"],
            repo_root,
            "inspect release tag",
        ).stdout.strip()
        if kind != "tag":
            raise ReleaseError(f"release tag {tag} must be annotated, got Git object type {kind!r}")
        target = _run(
            ["git", "rev-list", "-n", "1", tag],
            repo_root,
            "resolve release tag target",
        ).stdout.strip()
        if target != revision:
            raise ReleaseError(f"release tag {tag} does not target HEAD")
    return {"repository": "turboism/Turboism", "revision": revision, "tag": tag}


def assert_version_binding(source: dict[str, str | None], version: str) -> None:
    tag = source.get("tag")
    if tag is not None and tag != f"v{version}":
        raise ReleaseError(f"release tag {tag} does not match framework version {version}")


def compare_versions(left: str, right: str) -> int:
    if not STRICT_VERSION.fullmatch(left) or not STRICT_VERSION.fullmatch(right):
        raise ReleaseError("version comparison requires strict MAJOR.MINOR.PATCH values")
    left_parts = tuple(int(part) for part in left.split("."))
    right_parts = tuple(int(part) for part in right.split("."))
    return (left_parts > right_parts) - (left_parts < right_parts)


def _run(command: list[str], cwd: Path, action: str) -> subprocess.CompletedProcess[str]:
    completed = subprocess.run(command, cwd=cwd, capture_output=True, text=True)
    if completed.returncode != 0:
        detail = completed.stderr.strip() or completed.stdout.strip() or f"exit {completed.returncode}"
        raise ReleaseError(f"cannot {action}: {detail}")
    return completed
