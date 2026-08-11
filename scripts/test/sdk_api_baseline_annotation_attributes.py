"""Class-file annotation attribute decoding for SDK canonicalization."""
from __future__ import annotations

from sdk_api_baseline_annotation_values import (
    annotation_value,
    parse_annotation,
    parse_annotations,
    parse_parameter_annotations,
    target_info,
    type_path,
)
from sdk_api_baseline_common import BaselineError
from sdk_api_baseline_model import Attributes, ConstantPool, Reader, RecordComponent, TypeAnnotation


def parse_type_annotations(reader: Reader, cp: ConstantPool) -> list[TypeAnnotation]:
    return [_type_annotation(reader, cp) for _ in range(reader.u2())]


def _type_annotation(reader: Reader, cp: ConstantPool) -> TypeAnnotation:
    target_type = reader.u1()
    target = f"0x{target_type:02X}:{target_info(reader, target_type)}"
    return TypeAnnotation(target, type_path(reader), parse_annotation(reader, cp))


def parse_attributes(reader: Reader, cp: ConstantPool) -> Attributes:
    result = Attributes()
    for _ in range(reader.u2()):
        _parse_attribute(reader, cp, result)
    return result


def _parse_attribute(reader: Reader, cp: ConstantPool, result: Attributes) -> None:
    name = cp.utf(reader.u2())
    length = reader.u4()
    end = reader.pos + length
    if end > len(reader.data):
        raise BaselineError(f"malformed {name} attribute")
    handler = _ATTRIBUTE_HANDLERS.get(name)
    if handler is None:
        _skip_attribute(reader, cp, result, end)
    else:
        handler(reader, cp, result, end)
    if reader.pos != end:
        raise BaselineError(f"malformed {name} attribute")


def _skip_attribute(reader: Reader, _cp: ConstantPool, _result: Attributes, end: int) -> None:
    reader.pos = end


def _signature(reader: Reader, cp: ConstantPool, result: Attributes, _end: int) -> None:
    result.signature = cp.utf(reader.u2())


def _constant_value(reader: Reader, _cp: ConstantPool, result: Attributes, _end: int) -> None:
    result.constant_index = reader.u2()


def _exceptions(reader: Reader, cp: ConstantPool, result: Attributes, _end: int) -> None:
    result.exceptions = [cp.class_name(reader.u2()) for _ in range(reader.u2())]


def _visible_annotations(reader: Reader, cp: ConstantPool, result: Attributes, _end: int) -> None:
    result.visible_annotations = parse_annotations(reader, cp)


def _invisible_annotations(reader: Reader, cp: ConstantPool, result: Attributes, _end: int) -> None:
    result.invisible_annotations = parse_annotations(reader, cp)


def _visible_parameters(reader: Reader, cp: ConstantPool, result: Attributes, _end: int) -> None:
    result.visible_parameter_annotations = parse_parameter_annotations(reader, cp)


def _invisible_parameters(reader: Reader, cp: ConstantPool, result: Attributes, _end: int) -> None:
    result.invisible_parameter_annotations = parse_parameter_annotations(reader, cp)


def _visible_types(reader: Reader, cp: ConstantPool, result: Attributes, _end: int) -> None:
    result.visible_type_annotations = parse_type_annotations(reader, cp)


def _invisible_types(reader: Reader, cp: ConstantPool, result: Attributes, _end: int) -> None:
    result.invisible_type_annotations = parse_type_annotations(reader, cp)


def _annotation_default(reader: Reader, cp: ConstantPool, result: Attributes, _end: int) -> None:
    result.annotation_default = annotation_value(reader, cp)


def _record(reader: Reader, cp: ConstantPool, result: Attributes, _end: int) -> None:
    result.record_components.extend(_record_component(reader, cp, index) for index in range(reader.u2()))


def _record_component(reader: Reader, cp: ConstantPool, index: int) -> RecordComponent:
    return RecordComponent(cp.utf(reader.u2()), cp.utf(reader.u2()), parse_attributes(reader, cp), index)


def _permitted(reader: Reader, cp: ConstantPool, result: Attributes, _end: int) -> None:
    result.permitted_subclasses = [cp.class_name(reader.u2()) for _ in range(reader.u2())]


def _inner_classes(reader: Reader, cp: ConstantPool, result: Attributes, _end: int) -> None:
    result.inner_classes = [_inner_class(reader, cp) for _ in range(reader.u2())]


def _inner_class(reader: Reader, cp: ConstantPool) -> tuple[str | None, str | None, str | None, int]:
    inner, outer, simple, access = reader.u2(), reader.u2(), reader.u2(), reader.u2()
    return (
        cp.class_name(inner) if inner else None,
        cp.class_name(outer) if outer else None,
        cp.utf(simple) if simple else None,
        access,
    )


def _method_parameters(reader: Reader, cp: ConstantPool, result: Attributes, _end: int) -> None:
    result.method_parameters = [_method_parameter(reader, cp) for _ in range(reader.u1())]


def _method_parameter(reader: Reader, cp: ConstantPool) -> tuple[str | None, int]:
    name_index = reader.u2()
    return cp.utf(name_index) if name_index else None, reader.u2()


_ATTRIBUTE_HANDLERS = {
    "Signature": _signature,
    "ConstantValue": _constant_value,
    "Exceptions": _exceptions,
    "RuntimeVisibleAnnotations": _visible_annotations,
    "RuntimeInvisibleAnnotations": _invisible_annotations,
    "RuntimeVisibleParameterAnnotations": _visible_parameters,
    "RuntimeInvisibleParameterAnnotations": _invisible_parameters,
    "RuntimeVisibleTypeAnnotations": _visible_types,
    "RuntimeInvisibleTypeAnnotations": _invisible_types,
    "AnnotationDefault": _annotation_default,
    "Record": _record,
    "PermittedSubclasses": _permitted,
    "InnerClasses": _inner_classes,
    "MethodParameters": _method_parameters,
}
