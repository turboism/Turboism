#!/usr/bin/env python3
"""Read the reviewed Cubism host artifacts from their single source of truth.

``ReviewedHostArtifacts.java`` is the only place a reviewed Cubism Editor jar identity is
declared. Shell runners and Python tools resolve the same identities here instead of restating
SHA-256 literals, so adding a Cubism version is one reviewed edit to that Java type.

The source is located relative to this file (``<root>/scripts/preview/`` -> ``<root>/runtime/...``),
which holds both in the repository and inside a prepared validation snapshot. Flat staging
directories (a probe task directory, a task home ``validation/`` directory) carry no such tree, so
a ``ReviewedHostArtifacts.java`` copy sitting next to this file is used instead. Parsing fails
closed: a missing source, a malformed declaration, or an unknown version raises instead of
returning a guess.

Usage: reviewed_host_artifacts.py sha256 VERSION    (VERSION is 5303 or 5.3.03)
       reviewed_host_artifacts.py versions          (compact codes, oldest first)
"""
from __future__ import annotations

import re
import sys
from pathlib import Path
from typing import NamedTuple

SOURCE_RELATIVE = (
    "runtime/src/main/java/dev/turboism/mapping/verification/ReviewedHostArtifacts.java"
)
SOURCE_NAME = Path(SOURCE_RELATIVE).name


def default_source() -> Path:
    """Tree-relative source when present, else the flat sibling copy, else the tree path."""
    here = Path(__file__).resolve()
    tree = here.parents[2] / SOURCE_RELATIVE if len(here.parents) > 2 else None
    flat = here.with_name(SOURCE_NAME)
    if tree is not None and tree.is_file():
        return tree
    if flat.is_file():
        return flat
    return tree if tree is not None else flat


DEFAULT_SOURCE = default_source()

# ``CUBISM_5_3_03 = new HostArtifactDigest(42_010_633L, "<sha256>")``; the bundled JOGL
# dependency (``CUBISM_5_3_03_JOGL``) is not an Editor artifact and deliberately does not match.
_DECLARATION = re.compile(
    r'CUBISM_(\d+)_(\d+)_(\d+)\s*=\s*new HostArtifactDigest\(\s*([\d_]+)L,\s*"([0-9a-f]{64})"\s*\)'
)


class ReviewedArtifact(NamedTuple):
    """One reviewed Editor jar identity."""

    version: str
    size: int
    sha256: str


def load(source: Path = DEFAULT_SOURCE) -> dict[str, ReviewedArtifact]:
    """Return reviewed artifacts keyed by dotted version (``5.3.03``), oldest first."""
    try:
        text = source.read_text(encoding="utf-8")
    except OSError as failure:
        raise SystemExit(f"reviewed host artifact source unreadable: {source}: {failure}")
    artifacts: dict[str, ReviewedArtifact] = {}
    for major, minor, patch, size, sha256 in _DECLARATION.findall(text):
        version = f"{major}.{minor}.{patch}"
        if version in artifacts:
            raise SystemExit(f"duplicate reviewed artifact {version} in {source}")
        artifacts[version] = ReviewedArtifact(version, int(size.replace("_", "")), sha256)
    if not artifacts:
        raise SystemExit(f"no reviewed host artifacts found in {source}")
    return dict(sorted(artifacts.items(), key=lambda item: tuple(map(int, item[0].split(".")))))


def dotted(version: str) -> str:
    """Normalize ``5303`` or ``5.3.03`` to the dotted form."""
    if re.fullmatch(r"5\d{3}", version):
        return f"{version[0]}.{version[1]}.{version[2:]}"
    return version


def compact(version: str) -> str:
    """Normalize ``5.3.03`` to the compact code ``5303``."""
    major, minor, patch = version.split(".")
    return f"{major}{minor}{patch}"


def sha256(version: str, source: Path = DEFAULT_SOURCE) -> str:
    """Return the reviewed jar SHA-256 for a version, failing closed when unreviewed."""
    artifact = load(source).get(dotted(version))
    if artifact is None:
        raise SystemExit(f"unreviewed Cubism version: {version}")
    return artifact.sha256


def main(argv: list[str]) -> int:
    if len(argv) == 3 and argv[1] == "sha256":
        print(sha256(argv[2]))
        return 0
    if len(argv) == 2 and argv[1] == "versions":
        print(" ".join(compact(version) for version in load()))
        return 0
    print(__doc__, file=sys.stderr)
    return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv))
