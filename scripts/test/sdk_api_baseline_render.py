"""Canonical SDK API record rendering from parsed class-file models."""
from __future__ import annotations

from typing import Any

from sdk_api_baseline_common import (
    ACC_ABSTRACT,
    ACC_ANNOTATION,
    ACC_BRIDGE,
    ACC_ENUM,
    ACC_FINAL,
    ACC_INTERFACE,
    ACC_MODULE,
    ACC_NATIVE,
    ACC_PRIVATE,
    ACC_PROTECTED,
    ACC_PUBLIC,
    ACC_STATIC,
    ACC_STRICT,
    ACC_SYNCHRONIZED,
    ACC_SYNTHETIC,
    ACC_TRANSIENT,
    ACC_VARARGS,
    ACC_VOLATILE,
    BaselineError,
    encode_float32_bits,
    encode_float64_bits,
    encode_list,
    encode_name,
    encode_string,
)
from sdk_api_baseline_model import Annotation, Attributes, ClassInfo, ConstantPool


def annotations_value(attributes: Attributes) -> str:
    values = _declaration_annotations(attributes)
    values.extend(_type_annotations(attributes))
    return encode_list(sorted(values))


def _declaration_annotations(attributes: Attributes) -> list[str]:
    return [
        f"{visibility}:{annotation.encode()}"
        for visibility, source in (("visible", attributes.visible_annotations), ("invisible", attributes.invisible_annotations))
        for annotation in source
    ]


def _type_annotations(attributes: Attributes) -> list[str]:
    return [
        f"{visibility}:{annotation.encode()}"
        for visibility, source in (
            ("visible-type", attributes.visible_type_annotations),
            ("invisible-type", attributes.invisible_type_annotations),
        )
        for annotation in source
    ]


def parameter_annotations_value(attributes: Attributes) -> str:
    values = _parameter_annotation_values("visible", attributes.visible_parameter_annotations)
    values.extend(_parameter_annotation_values("invisible", attributes.invisible_parameter_annotations))
    return encode_list(sorted(values))


def _parameter_annotation_values(visibility: str, groups: list[list[Annotation]]) -> list[str]:
    return [
        f"{visibility}:{index}:{annotation.encode()}"
        for index, group in enumerate(groups)
        for annotation in group
    ]


def class_flags(item: ClassInfo) -> str:
    values = _flag_names(item.effective_access, _CLASS_FLAGS)
    if item.is_record:
        values.append("record")
    if item.attributes.permitted_subclasses:
        values.append("sealed")
    if item.non_sealed:
        values.append("non-sealed")
    return ",".join(values) if values else "package"


def field_flags(access: int) -> str:
    return ",".join(_flag_names(access, _FIELD_FLAGS))


def method_flags(access: int, owner_interface: bool) -> str:
    values = _flag_names(access, _METHOD_FLAGS)
    if owner_interface and not (access & (ACC_ABSTRACT | ACC_STATIC | ACC_PRIVATE)):
        values.append("default")
    return ",".join(values)


def _flag_names(access: int, definitions: tuple[tuple[int, str], ...]) -> list[str]:
    return [name for flag, name in definitions if access & flag]


def encode_constant(descriptor: str, entry: Any, cp: ConstantPool) -> str:
    tag = entry[0]
    if descriptor in _INTEGER_CONSTANT_ENCODERS:
        return _INTEGER_CONSTANT_ENCODERS[descriptor](entry[1])
    if descriptor == "J":
        return f"long:{_signed64(entry[1])}"
    if descriptor == "F":
        return encode_float32_bits(entry[1])
    if descriptor == "D":
        return encode_float64_bits(entry[1])
    if descriptor == "Ljava/lang/String;" and tag == 8:
        return encode_string(cp.utf(entry[1]))
    raise BaselineError(f"invalid ConstantValue for descriptor {descriptor}")


def _boolean(raw: int) -> str:
    return "boolean:true" if raw != 0 else "boolean:false"


def _byte(raw: int) -> str:
    return f"byte:{_signed32(raw)}"


def _char(raw: int) -> str:
    return f"char:u{raw & 0xFFFF:04X}"


def _short(raw: int) -> str:
    return f"short:{_signed32(raw)}"


def _integer(raw: int) -> str:
    return f"int:{_signed32(raw)}"


def _signed32(raw: int) -> int:
    return raw if raw < 0x80000000 else raw - 0x100000000


def _signed64(raw: int) -> int:
    return raw if raw < 0x8000000000000000 else raw - 0x10000000000000000


_CLASS_FLAGS = (
    (ACC_PUBLIC, "public"),
    (ACC_PROTECTED, "protected"),
    (ACC_PRIVATE, "private"),
    (ACC_STATIC, "static"),
    (ACC_FINAL, "final"),
    (ACC_INTERFACE, "interface"),
    (ACC_ABSTRACT, "abstract"),
    (ACC_SYNTHETIC, "synthetic"),
    (ACC_ANNOTATION, "annotation"),
    (ACC_ENUM, "enum"),
    (ACC_MODULE, "module"),
)
_FIELD_FLAGS = (
    (ACC_PUBLIC, "public"),
    (ACC_PROTECTED, "protected"),
    (ACC_PRIVATE, "private"),
    (ACC_STATIC, "static"),
    (ACC_FINAL, "final"),
    (ACC_VOLATILE, "volatile"),
    (ACC_TRANSIENT, "transient"),
    (ACC_SYNTHETIC, "synthetic"),
    (ACC_ENUM, "enum-constant"),
)
_METHOD_FLAGS = (
    (ACC_PUBLIC, "public"),
    (ACC_PROTECTED, "protected"),
    (ACC_PRIVATE, "private"),
    (ACC_STATIC, "static"),
    (ACC_FINAL, "final"),
    (ACC_SYNCHRONIZED, "synchronized"),
    (ACC_BRIDGE, "bridge"),
    (ACC_VARARGS, "varargs"),
    (ACC_NATIVE, "native"),
    (ACC_ABSTRACT, "abstract"),
    (ACC_STRICT, "strict"),
    (ACC_SYNTHETIC, "synthetic"),
)
_INTEGER_CONSTANT_ENCODERS = {
    "Z": _boolean,
    "B": _byte,
    "C": _char,
    "S": _short,
    "I": _integer,
}
