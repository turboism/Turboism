"""Artifact discovery, verification record loading, and checksum verification."""
from __future__ import annotations

import hashlib
import json
from pathlib import Path
import re

from .constants import REF_DIR, VERIFICATION_DIR


def manifest_hashes(ref_dir: Path | None = None) -> dict[str, str]:
    if ref_dir is None:
        ref_dir = REF_DIR
    expected = {}
    for line in (ref_dir / "MANIFEST.txt").read_text().splitlines():
        parts = line.split()
        if len(parts) == 2 and re.fullmatch(r"[0-9a-f]{64}", parts[0]):
            expected[parts[1]] = parts[0]
    return expected


def artifact_facts(
    version: str,
    jar_name: str = "Live2D_Cubism.jar",
    ref_dir: Path | None = None,
) -> dict:
    if ref_dir is None:
        ref_dir = REF_DIR
    path = ref_dir / version / jar_name
    data = path.read_bytes()
    return {
        "name": jar_name,
        "size": len(data),
        "sha256": hashlib.sha256(data).hexdigest(),
    }


def load_source_records(
    verification_dir: Path | None = None,
) -> dict[str, list[tuple[str, dict]]]:
    """Group verification records by family, newest-reviewed first."""
    if verification_dir is None:
        verification_dir = VERIFICATION_DIR
    families: dict[str, list[tuple[str, dict]]] = {}
    for path in sorted(verification_dir.glob("cubism-*.json")):
        doc = json.loads(path.read_text())
        match = re.match(r"cubism-(\d+\.\d+\.\d+)-(.+)\.json", path.name)
        version, family = match.group(1), match.group(2)
        families.setdefault(family, []).append((version, doc))
    for entries in families.values():
        entries.sort(key=lambda item: item[0], reverse=True)
    return families


def family_of(record_name: str) -> str:
    return re.match(r"cubism-\d+\.\d+\.\d+-(.+)\.json", record_name).group(1)


def source_version_of(source: dict) -> str:
    return source["cubismVersion"]
