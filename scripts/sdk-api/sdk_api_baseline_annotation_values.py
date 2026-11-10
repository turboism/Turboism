"""Primitive annotation-value decoding for SDK class-file parsing."""
from __future__ import annotations

from sdk_api_baseline_common import (
    BaselineError,
    encode_float32_bits,
    encode_float64_bits,
    encode_list,
    encode_name,
    encode_string,
)
from sdk_api_baseline_model import Annotation, ConstantPool, Reader


def target_info(reader: Reader, target_type: int) -> str:
    if target_type in (0x00, 0x01):
        return f"type-parameter:{reader.u1()}"
    if target_type == 0x10:
        return f"supertype:{reader.u2()}"
    if target_type in (0x11, 0x12):
        return f"type-parameter-bound:{reader.u1()}:{reader.u1()}"
    if target_type in (0x13, 0x14, 0x15):
        return "empty"
    if target_type == 0x16:
        return f"formal-parameter:{reader.u1()}"
    if target_type == 0x17:
        return f"throws:{reader.u2()}"
    return _non_parameter_target(reader, target_type)


def _non_parameter_target(reader: Reader, target_type: int) -> str:
    if target_type in (0x40, 0x41):
        entries = [_local_variable_target(reader) for _ in range(reader.u2())]
        return f"localvar:{encode_list(entries)}"
    if target_type == 0x42:
        return f"catch:{reader.u2()}"
    if target_type in (0x43, 0x44, 0x45, 0x46):
        return f"offset:{reader.u2()}"
    if 0x47 <= target_type <= 0x4B:
        return f"type-argument:{reader.u2()}:{reader.u1()}"
    raise BaselineError(f"invalid type annotation target type 0x{target_type:02X}")


def _local_variable_target(reader: Reader) -> str:
    return f"{reader.u2()}:{reader.u2()}:{reader.u2()}"


def type_path(reader: Reader) -> str:
    return encode_list(_path_element(reader) for _ in range(reader.u1()))


def _path_element(reader: Reader) -> str:
    kind = reader.u1()
    argument = reader.u1()
    if kind > 3 or (kind != 3 and argument != 0):
        raise BaselineError("invalid type annotation path")
    return f"{kind}:{argument}"


def annotation_value(reader: Reader, cp: ConstantPool) -> str:
    tag = chr(reader.u1())
    if tag in "BCISZ":
        return _integer_annotation_value(tag, cp.entry(reader.u2(), 3)[1])
    if tag == "J":
        return f"long:{_signed64(cp.entry(reader.u2(), 5)[1])}"
    if tag == "F":
        return encode_float32_bits(cp.entry(reader.u2(), 4)[1])
    if tag == "D":
        return encode_float64_bits(cp.entry(reader.u2(), 6)[1])
    return _complex_annotation_value(tag, reader, cp)


def _integer_annotation_value(tag: str, raw: int) -> str:
    signed = _signed32(raw)
    if tag == "B":
        return f"byte:{signed}"
    if tag == "C":
        return f"char:u{raw & 0xFFFF:04X}"
    if tag == "I":
        return f"int:{signed}"
    if tag == "S":
        return f"short:{signed}"
    return "boolean:true" if raw != 0 else "boolean:false"


def _complex_annotation_value(tag: str, reader: Reader, cp: ConstantPool) -> str:
    if tag == "s":
        return encode_string(cp.utf(reader.u2()))
    if tag == "e":
        return _enum_annotation_value(reader, cp)
    if tag == "c":
        return _class_annotation_value(reader, cp)
    if tag == "@":
        return parse_annotation(reader, cp).encode()
    if tag == "[":
        return "array:" + encode_list(annotation_value(reader, cp) for _ in range(reader.u2()))
    raise BaselineError(f"invalid annotation element-value tag {tag!r}")


def _enum_annotation_value(reader: Reader, cp: ConstantPool) -> str:
    descriptor = cp.utf(reader.u2())
    return f"enum:{len(descriptor)}:{descriptor}:{encode_name(cp.utf(reader.u2()))}"


def _class_annotation_value(reader: Reader, cp: ConstantPool) -> str:
    descriptor = cp.utf(reader.u2())
    return f"class:{len(descriptor)}:{descriptor}"


def _signed32(raw: int) -> int:
    return raw if raw < 0x80000000 else raw - 0x100000000


def _signed64(raw: int) -> int:
    return raw if raw < 0x8000000000000000 else raw - 0x10000000000000000


def parse_annotation(reader: Reader, cp: ConstantPool) -> Annotation:
    descriptor = cp.utf(reader.u2())
    pairs = [_annotation_pair(reader, cp) for _ in range(reader.u2())]
    return Annotation(descriptor, tuple(sorted(pairs)))


def _annotation_pair(reader: Reader, cp: ConstantPool) -> tuple[str, str]:
    return cp.utf(reader.u2()), annotation_value(reader, cp)


def parse_annotations(reader: Reader, cp: ConstantPool) -> list[Annotation]:
    return [parse_annotation(reader, cp) for _ in range(reader.u2())]


def parse_parameter_annotations(reader: Reader, cp: ConstantPool) -> list[list[Annotation]]:
    return [parse_annotations(reader, cp) for _ in range(reader.u1())]
