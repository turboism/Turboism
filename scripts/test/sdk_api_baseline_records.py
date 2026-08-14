"""Raw canonical SDK API record generation."""
from __future__ import annotations

from pathlib import Path

from sdk_api_baseline_common import (
    BaselineError, EXCEPTED_API_TOKEN_PACKAGES, EXCEPTED_API_TOKENS,
    FORBIDDEN_API_TOKENS, HEADER, encode_list,
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


def canonical_records(input_path: Path, package_prefix: str | None) -> tuple[list[str], str, int]:
    parsed, artifact_sha, artifact_size = load_parsed_classes(input_path, package_prefix)
    exported = api_classes(parsed)
    if not exported:
        raise BaselineError("input contains no public/protected API classes")
    records = _package_records(parsed, exported)
    for parsed_class in sorted(exported, key=lambda item: item.info.name):
        records.extend(_class_records(parsed_class))
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
        class_record(parsed_class, exclude_preview_marker=False),
        *field_records(parsed_class),
        *component_records(parsed_class),
        *method_records(parsed_class, exclude_preview_markers=False),
    ]


def canonical_dump(input_path: Path, package_prefix: str | None) -> tuple[bytes, str, int]:
    records, artifact_sha, artifact_size = canonical_records(input_path, package_prefix)
    text = HEADER + "".join(record + "\n" for record in records)
    return text.encode("utf-8"), artifact_sha, artifact_size
