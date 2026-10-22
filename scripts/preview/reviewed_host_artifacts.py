#!/usr/bin/env python3
"""Read exact reviewed Cubism facts from reviewed-releases.json.

Tree snapshots keep the repository-relative JSON path; flat runners stage a
sibling reviewed-releases.json. Missing or malformed input fails closed.

Usage: reviewed_host_artifacts.py sha256 VERSION | versions
"""
from __future__ import annotations

import json
import re
import sys
from pathlib import Path
from typing import NamedTuple

SOURCE_RELATIVE = "compatibility/cubism/reviewed-releases.json"
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

class ReviewedArtifact(NamedTuple):
    """One reviewed Editor jar identity."""

    version: str
    size: int
    sha256: str


def unique_object(pairs: list) -> dict:
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError(f"duplicate JSON key: {key}")
        result[key] = value
    return result


def load_releases(source: Path = DEFAULT_SOURCE) -> list[dict]:
    """Validate the canonical table; preserve its strictly increasing order."""
    try:
        document = json.loads(source.read_text(encoding="utf-8"), object_pairs_hook=unique_object)
    except (OSError, ValueError) as failure:
        raise ValueError(f"reviewed release source unreadable: {source}: {failure}") from failure
    if not isinstance(document, dict) or set(document) != {"format", "releases"} or document["format"] != "turboism.reviewed-releases/v1":
        raise ValueError("invalid reviewed releases format")
    releases = document["releases"]
    if not isinstance(releases, list) or not releases:
        raise ValueError("empty reviewed releases")
    previous = (-1, -1, -1)
    digests = set()
    codes = set()
    for release in releases:
        if not isinstance(release, dict) or set(release) != {"version", "build", "artifact", "core", "fullRuntime", "hostLabel"}:
            raise ValueError("invalid release fields")
        version = release["version"]
        if not isinstance(version, str) or not re.fullmatch(r"[1-9]\d*\.(?:0|[1-9]\d*)\.\d{2}", version):
            raise ValueError(f"invalid exact version: {version!r}")
        key = tuple(map(int, version.split(".")))
        if key <= previous or compact(version) in codes:
            raise ValueError("reviewed versions must be unique and strictly increasing")
        previous = key
        codes.add(compact(version))
        if type(release["build"]) is not int or not 0 < release["build"] <= 2147483647:
            raise ValueError(f"invalid build: {version}")
        artifact = release["artifact"]
        if not isinstance(artifact, dict) or set(artifact) != {"name", "size", "sha256"} or artifact["name"] != "Live2D_Cubism.jar":
            raise ValueError(f"invalid artifact: {version}")
        if type(artifact["size"]) is not int or not 0 < artifact["size"] <= 9223372036854775807:
            raise ValueError(f"invalid artifact size: {version}")
        digest = artifact["sha256"]
        if not isinstance(digest, str) or not re.fullmatch(r"[0-9a-f]{64}", digest) or digest in digests:
            raise ValueError(f"invalid or duplicate artifact sha256: {version}")
        digests.add(digest)
        core = release["core"]
        if not isinstance(core, dict) or set(core) != {"major", "minor", "patch"} or any(type(v) is not int or not 0 <= v <= 2147483647 for v in core.values()):
            raise ValueError(f"invalid Core tuple: {version}")
        if type(release["fullRuntime"]) is not bool:
            raise ValueError(f"invalid fullRuntime: {version}")
        label = release["hostLabel"]
        if not isinstance(label, str) or not label.strip() or any(c in label for c in "/\\\r\n") or label in {".", ".."}:
            raise ValueError(f"invalid hostLabel: {version}")
    return releases


def load(source: Path = DEFAULT_SOURCE) -> dict[str, ReviewedArtifact]:
    """Return reviewed artifacts keyed by exact dotted version, oldest first."""
    return {r["version"]: ReviewedArtifact(r["version"], r["artifact"]["size"], r["artifact"]["sha256"])
            for r in load_releases(source)}


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
