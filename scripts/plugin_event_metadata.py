#!/usr/bin/env python3
"""Deterministic plugin public-event descriptor metadata.

This module normalizes schema-v4 ``eventExports`` and ``eventImports`` for
release sidecars and documentation. Schema-v3 descriptors have an empty public
event inventory. Runtime admission remains the authority for ClassLoader and
ABI enforcement; this module makes the same declared contract reviewable and
fails closed on descriptor-local inconsistencies.
"""
from __future__ import annotations

import re
from collections.abc import Iterable

STRICT_VERSION = re.compile(r"^[0-9]+\.[0-9]+\.[0-9]+$")
EVENT_ID = re.compile(r"^[a-z0-9][a-z0-9._-]{0,127}$")
PLUGIN_ID = EVENT_ID
BINARY_TYPE = re.compile(r"^[A-Za-z_$][A-Za-z0-9_$]*(?:\.[A-Za-z_$][A-Za-z0-9_$]*)+$")
SHA256 = re.compile(r"^[0-9a-f]{64}$")
VERSION_RANGE = re.compile(r"^[\[(][0-9]+\.[0-9]+\.[0-9]+,[0-9]+\.[0-9]+\.[0-9]+[\])]$")


class EventMetadataError(ValueError):
    """A plugin descriptor's public-event metadata is malformed."""


def normalize_event_metadata(document: dict, label: str) -> tuple[list[dict], list[dict]]:
    """Return canonical exports/imports for one schema-v3 or schema-v4 descriptor."""
    schema = document.get("schemaVersion")
    if schema == 3:
        if "eventExports" in document or "eventImports" in document:
            raise EventMetadataError(f"{label}: event metadata requires descriptor schemaVersion 4")
        return [], []
    if schema != 4:
        raise EventMetadataError(f"{label}: descriptor schemaVersion must be 3 or 4")
    exports = _exports(document.get("eventExports", []), label)
    imports = _imports(document.get("eventImports", []), label)
    _require_import_dependencies(document.get("dependencies"), imports, label)
    return exports, imports


def validate_event_routes(descriptors: Iterable[dict], *, require_providers: bool) -> None:
    """Validate canonical event routes across a selected descriptor set."""
    documents = list(descriptors)
    providers = {item["id"]: item for item in documents}
    exports: dict[tuple[str, str], dict] = {}
    for descriptor in documents:
        for export in descriptor.get("eventExports", []):
            route = (descriptor["id"], export["id"])
            previous = exports.get(route)
            if previous is not None and previous != export:
                raise EventMetadataError(
                    f"public event route {route[0]}:{route[1]} has conflicting exports"
                )
            exports[route] = export
    for consumer in documents:
        dependencies = {
            item.get("id"): item.get("version")
            for item in consumer.get("dependencies", [])
            if isinstance(item, dict)
        }
        for event_import in consumer.get("eventImports", []):
            provider_id = event_import["provider"]
            exported = exports.get((provider_id, event_import["eventId"]))
            provider = providers.get(provider_id)
            if provider is None or exported is None:
                if require_providers and event_import["required"]:
                    raise EventMetadataError(
                        f"{consumer['id']}: required public event {provider_id}:{event_import['eventId']} is absent"
                    )
                continue
            if exported["eventType"] != event_import["eventType"]:
                raise EventMetadataError(
                    f"{consumer['id']}: public event type mismatch for {provider_id}:{event_import['eventId']}"
                )
            if exported["abiSha256"] != event_import["abiSha256"]:
                raise EventMetadataError(
                    f"{consumer['id']}: public event ABI mismatch for {provider_id}:{event_import['eventId']}"
                )
            if not version_in_range(exported["contractVersion"], event_import["contractVersion"]):
                raise EventMetadataError(
                    f"{consumer['id']}: public event contract version mismatch for {provider_id}:{event_import['eventId']}"
                )
            dependency_range = dependencies.get(provider_id)
            if dependency_range is None or not version_in_range(provider["version"], dependency_range):
                raise EventMetadataError(
                    f"{consumer['id']}: provider version mismatch for {provider_id}:{event_import['eventId']}"
                )


def version_in_range(version: str, expression: str) -> bool:
    match = VERSION_RANGE.fullmatch(expression)
    if not match:
        return False
    lower_text, upper_text = expression[1:-1].split(",", 1)
    value = _version_tuple(version)
    lower = _version_tuple(lower_text)
    upper = _version_tuple(upper_text)
    lower_ok = value >= lower if expression[0] == "[" else value > lower
    upper_ok = value <= upper if expression[-1] == "]" else value < upper
    return lower_ok and upper_ok


def _exports(value, label: str) -> list[dict]:
    rows = _array(value, f"{label}.eventExports")
    result = []
    ids = set()
    types = set()
    for index, raw in enumerate(rows):
        where = f"{label}.eventExports[{index}]"
        item = _object(raw, where, {"id", "contractVersion", "eventType", "abiSha256"})
        event_id = _text(item, "id", where, EVENT_ID)
        event_type = _text(item, "eventType", where, BINARY_TYPE)
        if event_id in ids:
            raise EventMetadataError(f"{where}: duplicate exported event id {event_id!r}")
        if event_type in types:
            raise EventMetadataError(f"{where}: duplicate exported event type {event_type!r}")
        ids.add(event_id)
        types.add(event_type)
        result.append({
            "id": event_id,
            "contractVersion": _text(item, "contractVersion", where, STRICT_VERSION),
            "eventType": event_type,
            "abiSha256": _text(item, "abiSha256", where, SHA256),
        })
    return sorted(result, key=lambda item: (item["id"], item["eventType"]))


def _imports(value, label: str) -> list[dict]:
    rows = _array(value, f"{label}.eventImports")
    result = []
    routes = set()
    for index, raw in enumerate(rows):
        where = f"{label}.eventImports[{index}]"
        item = _object(
            raw,
            where,
            {"provider", "eventId", "contractVersion", "eventType", "abiSha256", "required"},
        )
        provider = _text(item, "provider", where, PLUGIN_ID)
        event_id = _text(item, "eventId", where, EVENT_ID)
        route = (provider, event_id)
        if route in routes:
            raise EventMetadataError(f"{where}: duplicate imported event route {provider}:{event_id}")
        routes.add(route)
        required = item.get("required")
        if type(required) is not bool:
            raise EventMetadataError(f"{where}.required: must be a boolean")
        result.append({
            "provider": provider,
            "eventId": event_id,
            "contractVersion": _text(item, "contractVersion", where, VERSION_RANGE),
            "eventType": _text(item, "eventType", where, BINARY_TYPE),
            "abiSha256": _text(item, "abiSha256", where, SHA256),
            "required": required,
        })
    return sorted(result, key=lambda item: (item["provider"], item["eventId"], item["eventType"]))


def _require_import_dependencies(value, imports: list[dict], label: str) -> None:
    dependencies = _array(value, f"{label}.dependencies")
    ids = {
        item.get("id")
        for item in dependencies
        if isinstance(item, dict) and isinstance(item.get("id"), str)
    }
    for event_import in imports:
        if event_import["provider"] not in ids:
            raise EventMetadataError(
                f"{label}: event import provider {event_import['provider']!r} must be a declared dependency"
            )


def _array(value, where: str) -> list:
    if not isinstance(value, list):
        raise EventMetadataError(f"{where}: must be an array")
    return value


def _object(value, where: str, keys: set[str]) -> dict:
    if not isinstance(value, dict):
        raise EventMetadataError(f"{where}: must be an object")
    unknown = sorted(set(value) - keys)
    missing = sorted(keys - set(value))
    if unknown or missing:
        detail = []
        if missing:
            detail.append("missing " + ", ".join(missing))
        if unknown:
            detail.append("unknown " + ", ".join(unknown))
        raise EventMetadataError(f"{where}: {'; '.join(detail)}")
    return value


def _text(value: dict, key: str, where: str, pattern: re.Pattern) -> str:
    item = value.get(key)
    if not isinstance(item, str) or not pattern.fullmatch(item):
        raise EventMetadataError(f"{where}.{key}: invalid value {item!r}")
    return item


def _version_tuple(value: str) -> tuple[int, int, int]:
    if not STRICT_VERSION.fullmatch(value):
        raise EventMetadataError(f"invalid strict version {value!r}")
    return tuple(int(part) for part in value.split("."))
