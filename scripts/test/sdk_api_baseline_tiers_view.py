"""Normalized tier-facing SDK API records and marker facts."""
from __future__ import annotations

from pathlib import Path

from sdk_api_baseline_common import API_VISIBILITY, BaselineError, PREVIEW_ANNOTATION_DESCRIPTOR, encode_list
from sdk_api_baseline_identity import canonical_identity, split_canonical_record
from sdk_api_baseline_marker_legal import all_preview_marker_usages, has_preview_annotation, tier_exported_classes
from sdk_api_baseline_model import ParsedClass, TierMarkerFacts
from sdk_api_baseline_parse import load_parsed_classes
from sdk_api_baseline_record_builders import class_record, component_records, field_records, method_records, package_record
from sdk_api_baseline_records import check_forbidden
from sdk_api_baseline_render import annotations_value


def canonical_records_for_tiers(input_path: Path, package_prefix: str | None) -> tuple[list[str], TierMarkerFacts, str, int]:
    parsed, artifact_sha, artifact_size = load_parsed_classes(input_path, package_prefix)
    exported = tier_exported_classes(parsed)
    api_classes = [item for item in parsed if exported.get(item.info.name, False)]
    if not api_classes:
        raise BaselineError("input contains no public/protected API classes")
    records, markers, invalid = _tier_records(parsed, api_classes, exported)
    for record in records:
        check_forbidden(record)
    facts = TierMarkerFacts(markers, tuple(sorted(set(invalid))))
    return sorted(records), facts, artifact_sha, artifact_size


def _tier_records(parsed, api_classes, exported):
    annotations, package_invalid = _package_annotations(parsed)
    records, markers = _package_records(api_classes, annotations)
    for parsed_class in sorted(api_classes, key=lambda item: item.info.name):
        class_records, class_markers = _class_records(parsed_class)
        records.extend(class_records)
        markers.update(class_markers)
    invalid = all_preview_marker_usages(parsed, exported) + package_invalid
    return records, markers, invalid


def _package_annotations(parsed):
    annotations = {}
    invalid = []
    for parsed_class in parsed:
        item = parsed_class.info
        if item.name.endswith("/package-info"):
            annotations[item.package_name] = annotations_value(item.attributes)
            if has_preview_annotation(item.attributes):
                invalid.append(f"package:{item.package_name}")
    return annotations, invalid


def _package_records(api_classes, annotations):
    records, markers = [], {}
    for package_name in sorted({item.info.package_name for item in api_classes}):
        record = package_record(package_name, annotations.get(package_name, encode_list([])))
        records.append(record)
        markers[canonical_identity(record)] = False
    return records, markers


def _class_records(parsed_class: ParsedClass):
    item = parsed_class.info
    class_entry = class_record(parsed_class, exclude_preview_marker=True)
    records = [class_entry, *field_records(parsed_class), *component_records(parsed_class)]
    records.extend(method_records(parsed_class, exclude_preview_markers=True))
    markers = {canonical_identity(class_entry): has_preview_annotation(item.attributes)}
    for record in records[1:]:
        markers[canonical_identity(record)] = _method_marker(parsed_class, record)
    return records, markers


def _method_marker(parsed_class: ParsedClass, record: str) -> bool:
    if not record.startswith("method\t"):
        return False
    _kind, values = split_canonical_record(record)
    for method in parsed_class.info.methods:
        if method.name == values["name"] and method.descriptor == values["descriptor"]:
            return method.name not in ("<init>", "<clinit>") and has_preview_annotation(method.attributes)
    return False
