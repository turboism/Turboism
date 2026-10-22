"""Cubism and framework version parsing, formatting, and comparison."""
from __future__ import annotations

import re
from pathlib import Path

# Match standard Cubism versions like 5.2.03, 5.3.00
DOTTED_CUBISM_VERSION = re.compile(r"^5\.(\d{1,2})\.(\d{2})$")
# Match compact Cubism versions like 5203, 5300
COMPACT_CUBISM_VERSION = re.compile(r"^5(\d{1,2})(\d{2})$")
# Match generic dotted version tokens
GENERIC_VERSION = re.compile(r"^\d+(?:\.\d+)+$")

# Framework version declaration in gradle/common-java.gradle.kts
FRAMEWORK_VERSION_RE = re.compile(
    r'turboismFrameworkVersion"\]\s*=\s*"((?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*))"'
)


def cubism_version_key(version: str) -> tuple[int, ...]:
    """Return a tuple of integers for sorting Cubism or generic dotted versions.

    Handles tokens like '5.2.03' -> (5, 2, 3), '5.3.00' -> (5, 3, 0).
    """
    cleaned = version.strip()
    if COMPACT_CUBISM_VERSION.fullmatch(cleaned):
        cleaned = compact_to_dotted(cleaned)
    return tuple(int(component) for component in cleaned.split("."))


def parse_cubism_version(token: str) -> tuple[int, int]:
    """Parse a dotted Cubism version like '5.2.03' into (minor, patch).

    Raises ValueError if token does not match the standard Cubism pattern.
    """
    match = DOTTED_CUBISM_VERSION.fullmatch(token.strip())
    if match is None:
        raise ValueError(f"unsupported Cubism version token: {token!r}")
    return int(match.group(1)), int(match.group(2))


def compare_cubism_versions(v1: str, v2: str) -> int:
    """Compare two Cubism version strings numerically.

    Returns -1 if v1 < v2, 0 if v1 == v2, 1 if v1 > v2.
    """
    k1 = cubism_version_key(v1)
    k2 = cubism_version_key(v2)
    if k1 < k2:
        return -1
    if k1 > k2:
        return 1
    return 0


def compact_to_dotted(token: str) -> str:
    """Convert compact version '5203' to dotted '5.2.03'."""
    cleaned = token.strip()
    match = COMPACT_CUBISM_VERSION.fullmatch(cleaned)
    if match is None:
        raise ValueError(f"unsupported compact version token: {token!r}")
    return f"5.{int(match.group(1))}.{int(match.group(2)):0>2}"


def dotted_to_compact(version: str) -> str:
    """Convert dotted version '5.2.03' to compact '5203'."""
    cleaned = version.strip()
    parts = cleaned.split(".")
    if len(parts) == 3:
        return f"{parts[0]}{parts[1]}{parts[2]}"
    raise ValueError(f"unsupported dotted version format: {version!r}")


def version_tag(version: str) -> str:
    """Convert dotted version '5.2.03' to constant tag format '5_2_03'."""
    return version.strip().replace(".", "_")


def missing_higher(declared: set[str], admitted: set[str]) -> set[str]:
    """Admitted patches required by upward closure that ``declared`` skips."""
    missing = set()
    for version in declared:
        minor, patch = parse_cubism_version(version)
        for other in admitted:
            other_minor, other_patch = parse_cubism_version(other)
            if other_minor == minor and other_patch > patch and other not in declared:
                missing.add(other)
    return missing


def framework_version(repo_root: Path | str | None = None) -> str:
    """Read the current framework version from gradle/common-java.gradle.kts."""
    from .paths import repo_root as find_root

    root = find_root(repo_root)
    path = root / "gradle/common-java.gradle.kts"
    try:
        text = path.read_text(encoding="utf-8")
    except OSError as failure:
        raise RuntimeError(f"cannot read framework version from {path}: {failure}") from failure
    matches = FRAMEWORK_VERSION_RE.findall(text)
    if len(matches) != 1:
        raise RuntimeError(
            f"expected exactly one framework version in {path}, found {len(matches)}"
        )
    return matches[0]


def reviewed_range_versions(text: str, admitted: set[str]) -> set[str]:
    versions = sorted(admitted, key=parse_cubism_version)
    selected: set[str] = set()
    for method, body in re.findall(r'\bReviewedVersions\.(since|line|between)\(([^)]*)\)', text):
        args = re.findall(r'"([^"]+)"', body)
        if method == "line":
            if len(args) != 1 or not re.fullmatch(r"5\.\d{1,2}", args[0]):
                raise ValueError(f"invalid reviewed line: {body}")
            selected.update(v for v in versions if v.startswith(args[0] + "."))
        else:
            if len(args) != (2 if method == "between" else 1) or any(v not in admitted for v in args):
                raise ValueError(f"unknown reviewed range boundary: {body}")
            first = versions.index(args[0])
            last = versions.index(args[-1]) if method == "between" else len(versions) - 1
            if first > last:
                raise ValueError(f"reversed reviewed range: {body}")
            selected.update(versions[first:last + 1])
    return selected
