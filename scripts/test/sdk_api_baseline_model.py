"""Parsed class-file model for SDK API canonicalization."""
from __future__ import annotations

import struct
from dataclasses import dataclass, field
from typing import Any

from sdk_api_baseline_common import BaselineError, encode_list, encode_name


class Reader:
    def __init__(self, data: bytes, label: str):
        self.data = data
        self.label = label
        self.pos = 0

    def take(self, size: int) -> bytes:
        if size < 0 or self.pos + size > len(self.data):
            raise BaselineError(f"truncated class structure at offset {self.pos}: {self.label}")
        result = self.data[self.pos:self.pos + size]
        self.pos += size
        return result

    def u1(self) -> int:
        return self.take(1)[0]

    def u2(self) -> int:
        return struct.unpack(">H", self.take(2))[0]

    def u4(self) -> int:
        return struct.unpack(">I", self.take(4))[0]


class ConstantPool:
    def __init__(self, reader: Reader):
        count = reader.u2()
        self.entries: list[Any] = [None] * count
        self._read_entries(reader, count)

    def _read_entries(self, reader: Reader, count: int) -> None:
        index = 1
        while index < count:
            tag = reader.u1()
            self.entries[index] = self._entry(reader, tag)
            index += 2 if tag in (5, 6) else 1

    @staticmethod
    def _entry(reader: Reader, tag: int) -> tuple[Any, ...]:
        if tag == 1:
            return tag, ConstantPool._utf_value(reader)
        if tag in (3, 4):
            return tag, reader.u4()
        if tag in (5, 6):
            return tag, (reader.u4() << 32) | reader.u4()
        if tag in (7, 8, 16, 19, 20):
            return tag, reader.u2()
        if tag in (9, 10, 11, 12, 17, 18):
            return tag, reader.u2(), reader.u2()
        if tag == 15:
            return tag, reader.u1(), reader.u2()
        raise BaselineError(f"unsupported constant-pool tag {tag}: {reader.label}")

    @staticmethod
    def _utf_value(reader: Reader) -> str:
        raw = reader.take(reader.u2())
        try:
            return raw.decode("utf-8")
        except UnicodeDecodeError:
            return raw.replace(b"\xC0\x80", b"\x00").decode("utf-8", "surrogatepass")

    def entry(self, index: int, expected: int | tuple[int, ...] | None = None) -> Any:
        if index <= 0 or index >= len(self.entries) or self.entries[index] is None:
            raise BaselineError(f"invalid constant-pool index {index}")
        value = self.entries[index]
        if expected is not None:
            allowed = (expected,) if isinstance(expected, int) else expected
            if value[0] not in allowed:
                raise BaselineError(f"constant-pool index {index} has tag {value[0]}, expected {allowed}")
        return value

    def utf(self, index: int) -> str:
        return self.entry(index, 1)[1]

    def class_name(self, index: int) -> str:
        return self.utf(self.entry(index, 7)[1])


@dataclass(frozen=True)
class Annotation:
    descriptor: str
    pairs: tuple[tuple[str, str], ...]

    def encode(self) -> str:
        pairs = [f"{encode_name(name)}={value}" for name, value in self.pairs]
        return f"annotation:{len(self.descriptor)}:{self.descriptor}:" + encode_list(pairs)


@dataclass(frozen=True)
class TypeAnnotation:
    target: str
    path: str
    annotation: Annotation

    def encode(self) -> str:
        return f"type-annotation:target={self.target}:path={self.path}:value={self.annotation.encode()}"


@dataclass
class Attributes:
    signature: str | None = None
    constant_index: int | None = None
    exceptions: list[str] = field(default_factory=list)
    visible_annotations: list[Annotation] = field(default_factory=list)
    invisible_annotations: list[Annotation] = field(default_factory=list)
    visible_parameter_annotations: list[list[Annotation]] = field(default_factory=list)
    invisible_parameter_annotations: list[list[Annotation]] = field(default_factory=list)
    visible_type_annotations: list[TypeAnnotation] = field(default_factory=list)
    invisible_type_annotations: list[TypeAnnotation] = field(default_factory=list)
    annotation_default: str | None = None
    record_components: list['RecordComponent'] = field(default_factory=list)
    permitted_subclasses: list[str] = field(default_factory=list)
    inner_classes: list[tuple[str | None, str | None, str | None, int]] = field(default_factory=list)
    method_parameters: list[tuple[str | None, int]] = field(default_factory=list)


@dataclass
class FieldInfo:
    access: int
    name: str
    descriptor: str
    attributes: Attributes
    declaration_index: int


@dataclass
class MethodInfo:
    access: int
    name: str
    descriptor: str
    attributes: Attributes


@dataclass
class RecordComponent:
    name: str
    descriptor: str
    attributes: Attributes
    declaration_index: int


@dataclass
class ClassInfo:
    name: str
    access: int
    super_name: str | None
    interfaces: list[str]
    attributes: Attributes
    fields: list[FieldInfo]
    methods: list[MethodInfo]
    effective_access: int
    package_name: str
    is_record: bool
    non_sealed: bool = False


@dataclass
class ParsedClass:
    info: ClassInfo
    cp: ConstantPool


@dataclass(frozen=True)
class TierMarkerFacts:
    direct_markers: dict[str, bool]
    invalid_usages: tuple[str, ...]
