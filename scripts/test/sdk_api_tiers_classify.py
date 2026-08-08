"""Tier ownership and record classification helpers."""
from __future__ import annotations

from typing import Iterable

from sdk_api_baseline_identity import canonical_identity, split_canonical_record


def owner_from_identity(identity: str) -> str | None:
    if identity.startswith("class:"):
        return identity[len("class:"):]
    if identity.startswith(("method:", "field:", "record-component:")):
        return identity.split(":", 1)[1].split("#", 1)[0]
    return None


def package_from_owner(owner: str) -> str:
    return owner.rpartition("/")[0]


def identity_is_owned_by_root(identity: str, root_identity: str) -> bool:
    if root_identity.startswith("class:"):
        owner = root_identity[len("class:"):]
        return identity == root_identity or identity.startswith(_member_prefixes(owner))
    return identity == root_identity


def _member_prefixes(owner: str) -> tuple[str, str, str]:
    return f"field:{owner}#", f"record-component:{owner}#", f"method:{owner}#"


def root_owned_records(records: list[str], root_identity: str) -> list[str]:
    return [record for record in records if identity_is_owned_by_root(canonical_identity(record), root_identity)]


def package_is_owned_by_promotions(package_identity: str, promotions: set[str], records: Iterable[str], historical_preview_roots: set[str]) -> bool:
    if not package_identity.startswith("package:"):
        return False
    package_name = package_identity[len("package:"):]
    required = _historical_package_roots(package_name, records, historical_preview_roots)
    promoted = _promoted_package_roots(package_name, promotions)
    return bool(required) and required <= promoted


def _historical_package_roots(package_name: str, records: Iterable[str], roots: set[str]) -> set[str]:
    return {
        f"class:{values['name']}"
        for record in records
        for kind, values in [split_canonical_record(record)]
        if kind == "class" and package_from_owner(values["name"]) == package_name
        and f"class:{values['name']}" in roots
    }


def _promoted_package_roots(package_name: str, promotions: set[str]) -> set[str]:
    return {
        promotion for promotion in promotions
        if promotion.startswith("class:") and package_from_owner(promotion[len("class:"):]) == package_name
    }


def historical_identity_is_promoted(identity: str, promotions: set[str], records: Iterable[str], historical_preview_roots: set[str]) -> bool:
    if identity.startswith("package:"):
        return package_is_owned_by_promotions(identity, promotions, records, historical_preview_roots)
    return any(identity_is_owned_by_root(identity, promotion) for promotion in promotions)


def record_tiers(records: list[str], direct_markers: dict[str, bool], preview_roots: set[str]) -> dict[str, str]:
    class_marked, method_marked = _marker_roots(direct_markers)
    preview_classes = class_marked | _class_roots(preview_roots)
    package_classes, package_annotations = _package_facts(records)
    return {
        canonical_identity(record): _record_tier(record, preview_classes, method_marked, preview_roots, package_classes, package_annotations)
        for record in records
    }


def _marker_roots(markers: dict[str, bool]) -> tuple[set[str], set[str]]:
    classes = {identity[len("class:"):] for identity, marked in markers.items() if marked and identity.startswith("class:")}
    methods = {identity for identity, marked in markers.items() if marked and identity.startswith("method:")}
    return classes, methods


def _class_roots(roots: set[str]) -> set[str]:
    return {identity[len("class:"):] for identity in roots if identity.startswith("class:")}


def _package_facts(records: list[str]):
    classes, annotations = {}, {}
    for record in records:
        kind, values = split_canonical_record(record)
        if kind == "class":
            classes.setdefault(package_from_owner(values["name"]), []).append(values["name"])
        elif kind == "package":
            annotations[values["name"]] = values["annotations"] != "list:0:[]"
    return classes, annotations


def _record_tier(record, preview_classes, method_marked, preview_roots, package_classes, package_annotations):
    identity = canonical_identity(record)
    kind, values = split_canonical_record(record)
    if kind == "package":
        return _package_tier(values["name"], preview_classes, package_classes, package_annotations)
    owner = values["owner"] if kind in ("field", "record-component", "method") else values["name"]
    preview = owner in preview_classes or (kind == "method" and identity in (method_marked | preview_roots))
    return "preview" if preview else "stable"


def _package_tier(name, preview_classes, package_classes, package_annotations):
    classes = package_classes.get(name, [])
    preview = classes and not package_annotations.get(name, False) and all(item in preview_classes for item in classes)
    return "preview" if preview else "stable"
