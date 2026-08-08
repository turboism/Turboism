"""PreviewApi marker placement validation for the tier view."""
from __future__ import annotations

from sdk_api_baseline_common import API_VISIBILITY, PREVIEW_ANNOTATION_DESCRIPTOR
from sdk_api_baseline_model import Attributes, ParsedClass


def tier_exported_classes(parsed: list[ParsedClass]) -> dict[str, bool]:
    by_name = {item.info.name: item.info for item in parsed}
    access, enclosing = _class_hierarchy(parsed, by_name)
    resolved: dict[str, bool] = {}
    resolving: set[str] = set()
    return {name: _exported(name, by_name, access, enclosing, resolved, resolving) for name in by_name}


def _class_hierarchy(parsed, by_name):
    access = {name: info.effective_access for name, info in by_name.items()}
    enclosing = explicit_enclosing(parsed, by_name, access)
    for name in by_name:
        _infer_enclosing(name, by_name, enclosing)
    return access, enclosing


def explicit_enclosing(parsed, by_name, access):
    enclosing = {}
    for parsed_class in parsed:
        add_inner_class_facts(parsed_class.info.attributes.inner_classes, by_name, access, enclosing)
    return enclosing


def add_inner_class_facts(entries, by_name, access, enclosing) -> None:
    for inner, outer, _simple, flags in entries:
        if inner in by_name:
            access[inner] = flags
            add_enclosing_fact(enclosing, inner, outer)


def add_enclosing_fact(enclosing, inner, outer) -> None:
    if outer is not None:
        enclosing.setdefault(inner, outer)


def _infer_enclosing(name, by_name, enclosing):
    if name in enclosing:
        return
    prefix = name
    while "$" in prefix:
        prefix = prefix.rsplit("$", 1)[0]
        if prefix in by_name:
            enclosing[name] = prefix
            return


def _exported(name, by_name, access, enclosing, resolved, resolving):
    if name in resolved:
        return resolved[name]
    if name in resolving:
        return False
    info = by_name.get(name)
    if info is None or not (access.get(name, info.effective_access) & API_VISIBILITY):
        resolved[name] = False
        return False
    resolving.add(name)
    outer = enclosing.get(name)
    value = outer is None or _exported(outer, by_name, access, enclosing, resolved, resolving)
    resolving.remove(name)
    resolved[name] = value
    return value


def all_preview_marker_usages(parsed: list[ParsedClass], exported: dict[str, bool]) -> list[str]:
    invalid: list[str] = []
    for parsed_class in parsed:
        invalid.extend(_class_marker_usages(parsed_class.info, exported.get(parsed_class.info.name, False)))
    return invalid


def _class_marker_usages(item, exported):
    location = f"class:{item.name}"
    invalid = _invalid_declaration(item.attributes, location, exported)
    for field in item.fields:
        invalid.extend(_invalid_declaration(field.attributes, f"field:{item.name}#{field.name}:{field.descriptor}", False))
    for component in item.attributes.record_components:
        location = f"record-component:{item.name}#{component.name}:{component.descriptor}"
        invalid.extend(_invalid_declaration(component.attributes, location, False))
    for method in item.methods:
        legal = exported and bool(method.access & API_VISIBILITY) and method.name not in ("<init>", "<clinit>")
        location = f"method:{item.name}#{method.name}{method.descriptor}"
        invalid.extend(_invalid_declaration(method.attributes, location, legal))
    return invalid


def _invalid_declaration(attributes: Attributes, location: str, legal: bool) -> list[str]:
    invalid = []
    if has_preview_annotation(attributes) and not legal:
        invalid.append(location)
    if attributes_contain_nested_preview_marker(attributes):
        invalid.append(f"{location}:nested-annotation")
    invalid.extend(invalid_preview_locations(attributes, location))
    return invalid


def has_preview_annotation(attributes: Attributes) -> bool:
    return any(annotation.descriptor == PREVIEW_ANNOTATION_DESCRIPTOR for annotation in declaration_annotations(attributes))


def declaration_annotations(attributes: Attributes):
    return (*attributes.visible_annotations, *attributes.invisible_annotations)


def attributes_contain_nested_preview_marker(attributes: Attributes) -> bool:
    return any(
        annotation_value_contains_preview_marker(value)
        for annotation in declaration_annotations(attributes)
        for _name, value in annotation.pairs
    )


def invalid_preview_locations(attributes: Attributes, location: str) -> list[str]:
    invalid = []
    if type_annotations_contain_marker(attributes):
        invalid.append(f"{location}:type-use")
    if parameter_annotations_contain_marker(attributes):
        invalid.append(f"{location}:parameter")
    return invalid


def type_annotations_contain_marker(attributes: Attributes) -> bool:
    annotations = (*attributes.visible_type_annotations, *attributes.invisible_type_annotations)
    return any(annotation_contains_preview_marker(annotation.annotation) for annotation in annotations)


def parameter_annotations_contain_marker(attributes: Attributes) -> bool:
    groups = (*attributes.visible_parameter_annotations, *attributes.invisible_parameter_annotations)
    return any(annotation_contains_preview_marker(annotation) for group in groups for annotation in group)


def annotation_contains_preview_marker(annotation) -> bool:
    return annotation.descriptor == PREVIEW_ANNOTATION_DESCRIPTOR or any(
        annotation_value_contains_preview_marker(value) for _name, value in annotation.pairs
    )


def annotation_value_contains_preview_marker(value: str) -> bool:
    return f"annotation:{len(PREVIEW_ANNOTATION_DESCRIPTOR)}:{PREVIEW_ANNOTATION_DESCRIPTOR}:" in value
