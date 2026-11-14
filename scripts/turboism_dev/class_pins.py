"""Shared reader and writer for ClassPinTable JSON documents.

A class-pin table names, per reviewed Cubism Editor version, the host classes a
hook rewrites or reads. Two on-disk shapes carry the same facts:

- compact: ``{"default": [...], "overrides": {"5.3.04": [...]}}``
- legacy flat: ``{"5.0.00": [...], ...}``

The compact shape states the shared class list once, so a newly reviewed version
inherits ``default`` with no edit and no second copy of the same names. The
legacy shape stays readable because synthetic fixtures and older branches still
use it.

Every reader in ``scripts/`` and ``testing/`` loads tables through this module so
one validation rule set decides what a table may declare.
"""
from __future__ import annotations

import re
from pathlib import Path
from typing import Any, Mapping, Sequence

from .json_io import read_json, write_json

DEFAULT_KEY = "default"
OVERRIDES_KEY = "overrides"
COMPACT_KEYS = frozenset({DEFAULT_KEY, OVERRIDES_KEY})
VERSION_KEY = re.compile(r"^\d+\.\d+\.\d+$")

INDENT = 2


class ClassPinError(ValueError):
    """A class-pin document cannot be read, expanded, or folded."""


def is_compact(document: Mapping[str, Any]) -> bool:
    """Return whether ``document`` uses the compact default/overrides shape."""
    return DEFAULT_KEY in document or OVERRIDES_KEY in document


def _class_list(value: Any, label: str) -> list[str]:
    """Return a validated copy of one class list."""
    if not isinstance(value, list):
        raise ClassPinError(f"{label} must be a list of target classes")
    if not value:
        raise ClassPinError(f"{label} must list at least one target class")
    seen: set[str] = set()
    result: list[str] = []
    for name in value:
        if not isinstance(name, str) or not name.strip():
            raise ClassPinError(f"{label} names a blank target class")
        if name in seen:
            raise ClassPinError(f"{label} repeats target class {name}")
        seen.add(name)
        result.append(name)
    return result


def expand(document: Any, versions: Sequence[str] | None = None) -> dict[str, list[str]]:
    """Return version -> target classes for a compact or legacy flat document.

    ``versions`` is the admitted version universe. A compact document needs it to
    resolve which versions inherit ``default``; a legacy flat document is
    self-describing and ignores it.
    """
    if not isinstance(document, dict):
        raise ClassPinError("top-level object expected")
    if not is_compact(document):
        return {
            str(version): _class_list(block, f"version '{version}'")
            for version, block in document.items()
        }
    unknown = sorted(set(document) - COMPACT_KEYS)
    if unknown:
        raise ClassPinError(f"unknown top-level field(s) {unknown} in a compact table")
    if DEFAULT_KEY not in document:
        raise ClassPinError("a compact table requires a 'default' block")
    if versions is None:
        raise ClassPinError("a compact table needs the admitted version list")
    defaults = _class_list(document[DEFAULT_KEY], f"'{DEFAULT_KEY}'")
    overrides = document.get(OVERRIDES_KEY, {})
    if not isinstance(overrides, dict):
        raise ClassPinError(f"'{OVERRIDES_KEY}' must be an object")
    admitted = [str(version) for version in versions]
    known = set(admitted)
    resolved: dict[str, list[str]] = {}
    for version, block in overrides.items():
        if version not in known:
            raise ClassPinError(f"override for unadmitted version '{version}'")
        classes = _class_list(block, f"override '{version}'")
        if classes == defaults:
            raise ClassPinError(
                f"override '{version}' repeats 'default' and must be removed")
        resolved[version] = classes
    return {version: resolved.get(version, defaults) for version in admitted}


def fold(document: Any, versions: Sequence[str] | None = None) -> dict[str, Any]:
    """Return the compact form of ``document`` with the smallest override set.

    The most frequent class list becomes ``default``; a tie prefers the newest
    version so a fresh generation carries the shared block. Versions that differ
    stay in ``overrides``, and an all-identical table loses the block entirely.
    """
    flat = expand(document, versions)
    if not flat:
        raise ClassPinError("a class-pin table must declare at least one version")
    counts: dict[tuple[str, ...], int] = {}
    newest: dict[tuple[str, ...], int] = {}
    for index, block in enumerate(flat.values()):
        key = tuple(block)
        counts[key] = counts.get(key, 0) + 1
        newest[key] = index
    # Most frequent block wins; a tie prefers the newest version so a freshly
    # generated table keeps the shared list as its default.
    default_key = max(counts, key=lambda key: (counts[key], newest[key]))
    overrides = {
        version: flat[version]
        for version in flat
        if tuple(flat[version]) != default_key
    }
    folded: dict[str, Any] = {DEFAULT_KEY: list(default_key)}
    if overrides:
        folded[OVERRIDES_KEY] = overrides
    return folded


def load(path: Path | str, versions: Sequence[str] | None = None) -> dict[str, list[str]]:
    """Read a class-pin file and return its expanded version -> classes map."""
    try:
        document = read_json(path)
    except (OSError, ValueError) as failure:
        raise ClassPinError(f"{path}: unreadable JSON: {failure}") from failure
    try:
        return expand(document, versions)
    except ClassPinError as failure:
        raise ClassPinError(f"{path}: {failure}") from failure


def write(path: Path | str, document: Mapping[str, Any]) -> None:
    """Write a class-pin document with canonical key order and indentation."""
    write_json(path, canonical(document), indent=INDENT)


def canonical(document: Mapping[str, Any]) -> dict[str, Any]:
    """Return ``document`` with ``default`` first and override keys sorted."""
    if not is_compact(document):
        return dict(document)
    ordered: dict[str, Any] = {DEFAULT_KEY: list(document[DEFAULT_KEY])}
    overrides = document.get(OVERRIDES_KEY) or {}
    if overrides:
        ordered[OVERRIDES_KEY] = {
            version: list(overrides[version]) for version in sorted(overrides)
        }
    return ordered


def declared_versions(document: Mapping[str, Any]) -> list[str]:
    """Return the version keys a legacy flat document declares, in file order."""
    return [str(key) for key in document if VERSION_KEY.fullmatch(str(key))]
