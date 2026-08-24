"""Shared canonical-record builders for raw and tier-aware SDK views."""
from __future__ import annotations

from sdk_api_baseline_common import ACC_ENUM, ACC_INTERFACE, API_VISIBILITY, encode_list, encode_name, encode_string
from sdk_api_baseline_model import ParsedClass
from sdk_api_baseline_render import annotations_value, class_flags, encode_constant, field_flags, method_flags, parameter_annotations_value


def package_record(name: str, annotations: str) -> str:
    return "package" f"\tname={name}" f"\tannotations={annotations}"


def class_record(parsed_class: ParsedClass) -> str:
    item = parsed_class.info
    return (
        "class" f"\tname={item.name}" f"\tflags={class_flags(item)}"
        f"\tsuper={item.super_name or '-'}" f"\tinterfaces={encode_list(item.interfaces)}"
        f"\tsignature={_signature(item.attributes.signature)}"
        f"\tpermitted={encode_list(sorted(item.attributes.permitted_subclasses))}"
        f"\tannotations={annotations_value(item.attributes)}"
    )


def field_records(parsed_class: ParsedClass) -> list[str]:
    records: list[str] = []
    enum_ordinal = 0
    for field_info in parsed_class.info.fields:
        if not field_info.access & API_VISIBILITY:
            continue
        records.append(_field_record(parsed_class, field_info, enum_ordinal))
        if field_info.access & ACC_ENUM:
            enum_ordinal += 1
    return records


def _field_record(parsed_class: ParsedClass, field_info, enum_ordinal: int) -> str:
    return (
        "field" f"\towner={parsed_class.info.name}" f"\tname={field_info.name}"
        f"\tdescriptor={field_info.descriptor}" f"\tflags={field_flags(field_info.access)}"
        f"\tsignature={_signature(field_info.attributes.signature)}"
        f"\tconstant={_field_constant(parsed_class, field_info)}"
        f"\tenum-ordinal={_enum_order(field_info.access, enum_ordinal)}"
        f"\tannotations={annotations_value(field_info.attributes)}"
    )


def _field_constant(parsed_class: ParsedClass, field_info) -> str:
    index = field_info.attributes.constant_index
    if index is None:
        return "none"
    entry = parsed_class.cp.entry(index, (3, 4, 5, 6, 8))
    return encode_constant(field_info.descriptor, entry, parsed_class.cp)


def _enum_order(access: int, ordinal: int) -> str:
    return str(ordinal) if access & ACC_ENUM else "-"


def component_records(parsed_class: ParsedClass) -> list[str]:
    owner = parsed_class.info.name
    return [_component_record(owner, component) for component in parsed_class.info.attributes.record_components]


def _component_record(owner: str, component) -> str:
    return (
        "record-component" f"\towner={owner}" f"\tname={component.name}"
        f"\tdescriptor={component.descriptor}"
        f"\tsignature={_signature(component.attributes.signature)}"
        f"\tindex={component.declaration_index}"
        f"\tannotations={annotations_value(component.attributes)}"
    )


def method_records(parsed_class: ParsedClass) -> list[str]:
    item = parsed_class.info
    owner_interface = bool(item.access & ACC_INTERFACE)
    return [
        _method_record(item.name, owner_interface, method)
        for method in item.methods
        if method.access & API_VISIBILITY
    ]


def _method_record(owner: str, owner_interface: bool, method) -> str:
    return (
        "method" f"\towner={owner}" f"\tname={method.name}" f"\tdescriptor={method.descriptor}"
        f"\tflags={method_flags(method.access, owner_interface)}"
        f"\tsignature={_signature(method.attributes.signature)}"
        f"\tthrows={encode_list(method.attributes.exceptions)}"
        f"\tparameters={_method_parameters(method.attributes.method_parameters)}"
        f"\tannotations={annotations_value(method.attributes)}"
        f"\tparameter-annotations={parameter_annotations_value(method.attributes)}"
        f"\t{_annotation_default(method.attributes.annotation_default)}"
    )


def _signature(value: str | None) -> str:
    return encode_string(value) if value is not None else "-"


def _method_parameters(values: list[tuple[str | None, int]]) -> str:
    return encode_list(
        f"{index}:{encode_name(name) if name is not None else '-'}:{flags}"
        for index, (name, flags) in enumerate(values)
    )


def _annotation_default(value: str | None) -> str:
    return "annotation-default:none" if value is None else "annotation-default:value:" + value
