"""Raw canonical SDK API record generation."""
from __future__ import annotations

import dataclasses
from pathlib import Path

from sdk_api_baseline_common import (
    BaselineError, EXCEPTED_API_TOKEN_PACKAGES, EXCEPTED_API_TOKENS,
    FORBIDDEN_API_TOKENS, HEADER, encode_list, is_incubating,
)
from sdk_api_baseline_model import ParsedClass
from sdk_api_baseline_parse import api_classes, load_parsed_classes
from sdk_api_baseline_record_builders import class_record, component_records, field_records, method_records, package_record
from sdk_api_baseline_render import annotations_value


def check_forbidden(record: str) -> None:
    # Package-scoped no-Swing/no-AWT exception, authorized by
    # specs/window-icon-port-v1.md section 4a: records owned by the excepted
    # package may expose the tokens in EXCEPTED_API_TOKENS; every other
    # forbidden token still applies to them.
    owner = _record_owner(record)
    excepted_owner = any(
        owner.startswith(prefix) for prefix in EXCEPTED_API_TOKEN_PACKAGES
    )
    for token in FORBIDDEN_API_TOKENS:
        if token not in record:
            continue
        if excepted_owner and token in EXCEPTED_API_TOKENS:
            continue
        raise BaselineError(f"public SDK API exposes forbidden type token {token}")


def _record_owner(record: str) -> str:
    """Returns the owning class name (or class/package name) of a record."""
    kind, _, rest = record.partition("\t")
    label = "owner" if kind in ("method", "field", "record-component") else "name"
    prefix = label + "="
    for part in rest.split("\t"):
        if part.startswith(prefix):
            return part[len(prefix):]
    return ""


def _stable_class(parsed_class: ParsedClass) -> ParsedClass:
    """Returns the class with its @Incubating members stripped for baseline purposes.

    Incubating API is excluded from the exact-API compatibility contract: an incubating
    member may change or disappear in any release, so recording it would either pin an
    unstable contract or flag its normal evolution as a compatibility break.
    """
    info = parsed_class.info
    fields = [field for field in info.fields if not is_incubating(field.attributes)]
    methods = [method for method in info.methods if not is_incubating(method.attributes)]
    attributes = info.attributes
    components = [component for component in attributes.record_components if not is_incubating(component.attributes)]
    if len(fields) == len(info.fields) and len(methods) == len(info.methods) and len(components) == len(attributes.record_components):
        return parsed_class
    return dataclasses.replace(
        parsed_class,
        info=dataclasses.replace(
            info,
            fields=fields,
            methods=methods,
            attributes=dataclasses.replace(attributes, record_components=components),
        ),
    )


def canonical_records(input_path: Path, package_prefix: str | None) -> tuple[list[str], str, int]:
    parsed, artifact_sha, artifact_size = load_parsed_classes(input_path, package_prefix)
    exported = api_classes(parsed)
    incubating_names = {item.info.name for item in exported if is_incubating(item.info.attributes)}

    def incubated(name: str) -> bool:
        # Nested classes do not inherit annotations: a class nested inside an
        # incubating owner (A$B, A$B$C, ...) is excluded with its owner.
        segments = name.split("$")
        return any("$".join(segments[:index]) in incubating_names for index in range(1, len(segments) + 1))

    stable = [item for item in exported if not is_incubating(item.info.attributes) and not incubated(item.info.name)]
    if not stable:
        raise BaselineError("input contains no public/protected API classes")
    records = _package_records(parsed, stable)
    for parsed_class in sorted(stable, key=lambda item: item.info.name):
        records.extend(_class_records(_stable_class(parsed_class)))
    for record in records:
        check_forbidden(record)
    return sorted(records), artifact_sha, artifact_size


def _package_records(parsed: list[ParsedClass], exported: list[ParsedClass]) -> list[str]:
    annotations = {
        item.info.package_name: annotations_value(item.info.attributes)
        for item in parsed
        if item.info.name.endswith("/package-info")
    }
    return [
        package_record(name, annotations.get(name, encode_list([])))
        for name in sorted({item.info.package_name for item in exported})
    ]


def _class_records(parsed_class: ParsedClass) -> list[str]:
    return [
        class_record(parsed_class),
        *field_records(parsed_class),
        *component_records(parsed_class),
        *method_records(parsed_class),
    ]


def canonical_dump(input_path: Path, package_prefix: str | None) -> tuple[bytes, str, int]:
    records, artifact_sha, artifact_size = canonical_records(input_path, package_prefix)
    text = HEADER + "".join(record + "\n" for record in records)
    return text.encode("utf-8"), artifact_sha, artifact_size
