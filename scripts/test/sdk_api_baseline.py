"""Deterministic Turboism SDK class-file API model and canonical dump."""
from __future__ import annotations

import hashlib
import struct
import zipfile
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Iterable

SCHEMA_VERSION = 1
GENERATOR_VERSION = 1
HEADER = (
    f"sdk-api-schema\t{SCHEMA_VERSION}\n"
    f"sdk-api-generator\t{GENERATOR_VERSION}\n"
)
API_VISIBILITY = 0x0001 | 0x0004
ACC_PUBLIC = 0x0001
ACC_PRIVATE = 0x0002
ACC_PROTECTED = 0x0004
ACC_STATIC = 0x0008
ACC_FINAL = 0x0010
ACC_SUPER = 0x0020
ACC_SYNCHRONIZED = 0x0020
ACC_VOLATILE = 0x0040
ACC_BRIDGE = 0x0040
ACC_TRANSIENT = 0x0080
ACC_VARARGS = 0x0080
ACC_NATIVE = 0x0100
ACC_INTERFACE = 0x0200
ACC_ABSTRACT = 0x0400
ACC_STRICT = 0x0800
ACC_SYNTHETIC = 0x1000
ACC_ANNOTATION = 0x2000
ACC_ENUM = 0x4000
ACC_MODULE = 0x8000

FORBIDDEN_API_TOKENS = (
    "com/live2d/",
    "dev/turboism/core/",
    "dev/turboism/hook/",
    "dev/turboism/mapping/",
    "dev/turboism/adapter/",
    "dev/turboism/internal/",
    "org/objectweb/asm/",
    "net/bytebuddy/",
    "com/fasterxml/jackson/",
    "org/slf4j/",
    "org/junit/",
    "com/google/common/",
    "lombok/",
    "javax/swing/",
    "java/awt/",
)


class BaselineError(RuntimeError):
    pass


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def utf16_units(value: str) -> list[int]:
    encoded = value.encode("utf-16-be", "surrogatepass")
    return [struct.unpack_from(">H", encoded, offset)[0] for offset in range(0, len(encoded), 2)]


def encode_string(value: str) -> str:
    units = utf16_units(value)
    body = "-".join(f"{unit:04X}" for unit in units)
    return f"string:{len(units)}:{body}"


def encode_name(value: str) -> str:
    return encode_string(value)


def encode_list(values: Iterable[str]) -> str:
    items = list(values)
    return f"list:{len(items)}:[" + ",".join(f"{len(item)}:{item}" for item in items) + "]"


def encode_float32_bits(bits: int) -> str:
    return f"float:0x{bits & 0xFFFFFFFF:08X}"


def encode_float64_bits(bits: int) -> str:
    return f"double:0x{bits & 0xFFFFFFFFFFFFFFFF:016X}"


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
        index = 1
        while index < count:
            tag = reader.u1()
            if tag == 1:
                raw = reader.take(reader.u2())
                try:
                    value = raw.decode("utf-8")
                except UnicodeDecodeError:
                    # Class files use modified UTF-8. Null is the only common
                    # divergence relevant here; keep fail-closed for other cases.
                    value = raw.replace(b"\xC0\x80", b"\x00").decode("utf-8", "surrogatepass")
                self.entries[index] = (tag, value)
            elif tag in (3, 4):
                self.entries[index] = (tag, reader.u4())
            elif tag in (5, 6):
                high = reader.u4()
                low = reader.u4()
                self.entries[index] = (tag, (high << 32) | low)
                index += 1
            elif tag in (7, 8, 16, 19, 20):
                self.entries[index] = (tag, reader.u2())
            elif tag in (9, 10, 11, 12, 17, 18):
                self.entries[index] = (tag, reader.u2(), reader.u2())
            elif tag == 15:
                self.entries[index] = (tag, reader.u1(), reader.u2())
            else:
                raise BaselineError(f"unsupported constant-pool tag {tag}: {reader.label}")
            index += 1

    def entry(self, index: int, expected: int | tuple[int, ...] | None = None) -> Any:
        if index <= 0 or index >= len(self.entries) or self.entries[index] is None:
            raise BaselineError(f"invalid constant-pool index {index}")
        value = self.entries[index]
        if expected is not None:
            allowed = (expected,) if isinstance(expected, int) else expected
            if value[0] not in allowed:
                raise BaselineError(
                    f"constant-pool index {index} has tag {value[0]}, expected {allowed}"
                )
        return value

    def utf(self, index: int) -> str:
        return self.entry(index, 1)[1]

    def class_name(self, index: int) -> str:
        return self.utf(self.entry(index, 7)[1])

    def string(self, index: int) -> str:
        return self.utf(self.entry(index, 8)[1])


@dataclass(frozen=True)
class Annotation:
    descriptor: str
    pairs: tuple[tuple[str, str], ...]

    def encode(self) -> str:
        encoded_pairs = [f"{encode_name(name)}={value}" for name, value in self.pairs]
        return f"annotation:{len(self.descriptor)}:{self.descriptor}:" + encode_list(encoded_pairs)


@dataclass(frozen=True)
class TypeAnnotation:
    target: str
    path: str
    annotation: Annotation

    def encode(self) -> str:
        return (
            f"type-annotation:target={self.target}:path={self.path}:"
            f"value={self.annotation.encode()}"
        )


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
    record_components: list["RecordComponent"] = field(default_factory=list)
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
    if target_type in (0x40, 0x41):
        entries = [f"{reader.u2()}:{reader.u2()}:{reader.u2()}" for _ in range(reader.u2())]
        return f"localvar:{encode_list(entries)}"
    if target_type == 0x42:
        return f"catch:{reader.u2()}"
    if target_type in (0x43, 0x44, 0x45, 0x46):
        return f"offset:{reader.u2()}"
    if 0x47 <= target_type <= 0x4B:
        return f"type-argument:{reader.u2()}:{reader.u1()}"
    raise BaselineError(f"invalid type annotation target type 0x{target_type:02X}")


def type_path(reader: Reader) -> str:
    values: list[str] = []
    for _ in range(reader.u1()):
        kind = reader.u1()
        argument = reader.u1()
        if kind > 3 or (kind != 3 and argument != 0):
            raise BaselineError("invalid type annotation path")
        values.append(f"{kind}:{argument}")
    return encode_list(values)


def annotation_value(reader: Reader, cp: ConstantPool) -> str:
    tag = chr(reader.u1())
    index: int
    if tag in "BCISZ":
        index = reader.u2()
        raw = cp.entry(index, 3)[1]
        signed = raw if raw < 0x80000000 else raw - 0x100000000
        if tag == "B":
            return f"byte:{signed}"
        if tag == "C":
            return f"char:u{raw & 0xFFFF:04X}"
        if tag == "I":
            return f"int:{signed}"
        if tag == "S":
            return f"short:{signed}"
        return "boolean:true" if raw != 0 else "boolean:false"
    if tag == "J":
        raw = cp.entry(reader.u2(), 5)[1]
        signed = raw if raw < 0x8000000000000000 else raw - 0x10000000000000000
        return f"long:{signed}"
    if tag == "F":
        return encode_float32_bits(cp.entry(reader.u2(), 4)[1])
    if tag == "D":
        return encode_float64_bits(cp.entry(reader.u2(), 6)[1])
    if tag == "s":
        return encode_string(cp.utf(reader.u2()))
    if tag == "e":
        descriptor = cp.utf(reader.u2())
        name = cp.utf(reader.u2())
        return f"enum:{len(descriptor)}:{descriptor}:{encode_name(name)}"
    if tag == "c":
        descriptor = cp.utf(reader.u2())
        return f"class:{len(descriptor)}:{descriptor}"
    if tag == "@":
        return parse_annotation(reader, cp).encode()
    if tag == "[":
        return "array:" + encode_list(annotation_value(reader, cp) for _ in range(reader.u2()))
    raise BaselineError(f"invalid annotation element-value tag {tag!r}")


def parse_annotation(reader: Reader, cp: ConstantPool) -> Annotation:
    descriptor = cp.utf(reader.u2())
    pairs = []
    for _ in range(reader.u2()):
        pairs.append((cp.utf(reader.u2()), annotation_value(reader, cp)))
    return Annotation(descriptor, tuple(sorted(pairs)))


def parse_annotations(reader: Reader, cp: ConstantPool) -> list[Annotation]:
    return [parse_annotation(reader, cp) for _ in range(reader.u2())]


def parse_parameter_annotations(reader: Reader, cp: ConstantPool) -> list[list[Annotation]]:
    result = []
    for _ in range(reader.u1()):
        result.append(parse_annotations(reader, cp))
    return result


def parse_type_annotations(reader: Reader, cp: ConstantPool) -> list[TypeAnnotation]:
    result = []
    for _ in range(reader.u2()):
        target_type = reader.u1()
        target = f"0x{target_type:02X}:{target_info(reader, target_type)}"
        path = type_path(reader)
        result.append(TypeAnnotation(target, path, parse_annotation(reader, cp)))
    return result


def parse_attributes(reader: Reader, cp: ConstantPool) -> Attributes:
    result = Attributes()
    for _ in range(reader.u2()):
        name = cp.utf(reader.u2())
        length = reader.u4()
        end = reader.pos + length
        if end > len(reader.data):
            raise BaselineError(f"malformed {name} attribute")
        if name == "Signature":
            result.signature = cp.utf(reader.u2())
        elif name == "ConstantValue":
            result.constant_index = reader.u2()
        elif name == "Exceptions":
            result.exceptions = [cp.class_name(reader.u2()) for _ in range(reader.u2())]
        elif name == "RuntimeVisibleAnnotations":
            result.visible_annotations = parse_annotations(reader, cp)
        elif name == "RuntimeInvisibleAnnotations":
            result.invisible_annotations = parse_annotations(reader, cp)
        elif name == "RuntimeVisibleParameterAnnotations":
            result.visible_parameter_annotations = parse_parameter_annotations(reader, cp)
        elif name == "RuntimeInvisibleParameterAnnotations":
            result.invisible_parameter_annotations = parse_parameter_annotations(reader, cp)
        elif name == "RuntimeVisibleTypeAnnotations":
            result.visible_type_annotations = parse_type_annotations(reader, cp)
        elif name == "RuntimeInvisibleTypeAnnotations":
            result.invisible_type_annotations = parse_type_annotations(reader, cp)
        elif name == "AnnotationDefault":
            result.annotation_default = annotation_value(reader, cp)
        elif name == "Record":
            for component_index in range(reader.u2()):
                component_name = cp.utf(reader.u2())
                descriptor = cp.utf(reader.u2())
                attributes = parse_attributes(reader, cp)
                result.record_components.append(
                    RecordComponent(component_name, descriptor, attributes, component_index)
                )
        elif name == "PermittedSubclasses":
            result.permitted_subclasses = [cp.class_name(reader.u2()) for _ in range(reader.u2())]
        elif name == "InnerClasses":
            entries = []
            for _ in range(reader.u2()):
                inner_index = reader.u2()
                outer_index = reader.u2()
                inner_name_index = reader.u2()
                access = reader.u2()
                entries.append(
                    (
                        cp.class_name(inner_index) if inner_index else None,
                        cp.class_name(outer_index) if outer_index else None,
                        cp.utf(inner_name_index) if inner_name_index else None,
                        access,
                    )
                )
            result.inner_classes = entries
        elif name == "MethodParameters":
            result.method_parameters = [
                (cp.utf(name_index) if (name_index := reader.u2()) else None, reader.u2())
                for _ in range(reader.u1())
            ]
        else:
            reader.pos = end
        if reader.pos != end:
            raise BaselineError(f"malformed {name} attribute")
    return result


def effective_class_access(name: str, class_access: int, attrs: Attributes) -> int:
    for inner_name, _outer, _simple, access in attrs.inner_classes:
        if inner_name == name:
            return access
    return class_access


def annotations_value(attributes: Attributes) -> str:
    values: list[str] = []
    values.extend("visible:" + item.encode() for item in attributes.visible_annotations)
    values.extend("invisible:" + item.encode() for item in attributes.invisible_annotations)
    values.extend("visible-type:" + item.encode() for item in attributes.visible_type_annotations)
    values.extend("invisible-type:" + item.encode() for item in attributes.invisible_type_annotations)
    return encode_list(sorted(values))


def parameter_annotations_value(attributes: Attributes) -> str:
    values = []
    for index, group in enumerate(attributes.visible_parameter_annotations):
        values.extend(f"visible:{index}:{annotation.encode()}" for annotation in group)
    for index, group in enumerate(attributes.invisible_parameter_annotations):
        values.extend(f"invisible:{index}:{annotation.encode()}" for annotation in group)
    return encode_list(sorted(values))


def class_flags(item: ClassInfo) -> str:
    values = []
    access = item.effective_access
    for flag, name in (
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
    ):
        if access & flag:
            values.append(name)
    if item.is_record:
        values.append("record")
    if item.attributes.permitted_subclasses:
        values.append("sealed")
    if item.non_sealed:
        values.append("non-sealed")
    return ",".join(values) if values else "package"


def field_flags(access: int) -> str:
    values = []
    for flag, name in (
        (ACC_PUBLIC, "public"),
        (ACC_PROTECTED, "protected"),
        (ACC_PRIVATE, "private"),
        (ACC_STATIC, "static"),
        (ACC_FINAL, "final"),
        (ACC_VOLATILE, "volatile"),
        (ACC_TRANSIENT, "transient"),
        (ACC_SYNTHETIC, "synthetic"),
        (ACC_ENUM, "enum-constant"),
    ):
        if access & flag:
            values.append(name)
    return ",".join(values)


def method_flags(access: int, owner_interface: bool) -> str:
    values = []
    for flag, name in (
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
    ):
        if access & flag:
            values.append(name)
    if owner_interface and not (access & (ACC_ABSTRACT | ACC_STATIC | ACC_PRIVATE)):
        values.append("default")
    return ",".join(values)


def encode_constant(descriptor: str, entry: Any, cp: ConstantPool) -> str:
    tag = entry[0]
    if descriptor == "Z":
        return "boolean:true" if entry[1] != 0 else "boolean:false"
    if descriptor == "B":
        value = entry[1] if entry[1] < 0x80000000 else entry[1] - 0x100000000
        return f"byte:{value}"
    if descriptor == "C":
        return f"char:u{entry[1] & 0xFFFF:04X}"
    if descriptor == "S":
        value = entry[1] if entry[1] < 0x80000000 else entry[1] - 0x100000000
        return f"short:{value}"
    if descriptor == "I":
        value = entry[1] if entry[1] < 0x80000000 else entry[1] - 0x100000000
        return f"int:{value}"
    if descriptor == "J":
        raw = entry[1]
        value = raw if raw < 0x8000000000000000 else raw - 0x10000000000000000
        return f"long:{value}"
    if descriptor == "F":
        return encode_float32_bits(entry[1])
    if descriptor == "D":
        return encode_float64_bits(entry[1])
    if descriptor == "Ljava/lang/String;" and tag == 8:
        return encode_string(cp.utf(entry[1]))
    raise BaselineError(f"invalid ConstantValue for descriptor {descriptor}")


@dataclass
class ParsedClass:
    info: ClassInfo
    cp: ConstantPool


def parse_class_with_pool(data: bytes, label: str) -> ParsedClass:
    reader = Reader(data, label)
    if reader.u4() != 0xCAFEBABE:
        raise BaselineError(f"not a class file: {label}")
    reader.take(4)
    cp = ConstantPool(reader)
    access = reader.u2()
    name = cp.class_name(reader.u2())
    super_index = reader.u2()
    super_name = cp.class_name(super_index) if super_index else None
    interfaces = [cp.class_name(reader.u2()) for _ in range(reader.u2())]
    fields = [
        FieldInfo(reader.u2(), cp.utf(reader.u2()), cp.utf(reader.u2()), parse_attributes(reader, cp), index)
        for index in range(reader.u2())
    ]
    methods = [
        MethodInfo(reader.u2(), cp.utf(reader.u2()), cp.utf(reader.u2()), parse_attributes(reader, cp))
        for _ in range(reader.u2())
    ]
    attrs = parse_attributes(reader, cp)
    if reader.pos != len(data):
        raise BaselineError(f"trailing bytes after class structure: {label}")
    effective = effective_class_access(name, access, attrs)
    info = ClassInfo(
        name,
        access,
        super_name,
        interfaces,
        attrs,
        fields,
        methods,
        effective,
        name.rpartition("/")[0],
        bool(attrs.record_components),
    )
    return ParsedClass(info, cp)


def load_parsed_classes(input_path: Path, package_prefix: str | None) -> tuple[list[ParsedClass], str, int]:
    if not input_path.exists():
        raise BaselineError(f"input is missing: {input_path}")
    entries: list[tuple[str, bytes]] = []
    if input_path.is_dir():
        entries = [
            (path.relative_to(input_path).as_posix(), path.read_bytes())
            for path in sorted(input_path.rglob("*.class"))
        ]
        digest = hashlib.sha256()
        for name, data in entries:
            digest.update(name.encode("utf-8")); digest.update(b"\0")
            digest.update(hashlib.sha256(data).digest()); digest.update(b"\n")
        artifact_sha = digest.hexdigest()
        artifact_size = sum(len(data) for _, data in entries)
    elif input_path.is_file() and input_path.suffix.lower() in (".jar", ".zip"):
        with zipfile.ZipFile(input_path) as archive:
            names = sorted(
                name for name in archive.namelist()
                if name.endswith(".class") and not name.startswith("META-INF/versions/")
            )
            entries = [(name, archive.read(name)) for name in names]
        artifact_sha = sha256_file(input_path)
        artifact_size = input_path.stat().st_size
    else:
        raise BaselineError("input must be a class directory or JAR/ZIP")
    if package_prefix:
        prefix = package_prefix.replace(".", "/").strip("/") + "/"
        entries = [(name, data) for name, data in entries if name.startswith(prefix)]
    if not entries:
        raise BaselineError("input contains no matching class files")
    parsed = [parse_class_with_pool(data, name) for name, data in entries]
    sealed_children = {
        child
        for owner in parsed
        for child in owner.info.attributes.permitted_subclasses
    }
    for item in parsed:
        if (
            item.info.name in sealed_children
            and not item.info.attributes.permitted_subclasses
            and not (item.info.effective_access & ACC_FINAL)
        ):
            item.info.non_sealed = True
    return parsed, artifact_sha, artifact_size


def check_forbidden(record: str) -> None:
    for token in FORBIDDEN_API_TOKENS:
        if token in record:
            raise BaselineError(f"public SDK API exposes forbidden type token {token}")


def canonical_records(input_path: Path, package_prefix: str | None) -> tuple[list[str], str, int]:
    parsed, artifact_sha, artifact_size = load_parsed_classes(input_path, package_prefix)
    api_classes = [item for item in parsed if item.info.effective_access & API_VISIBILITY]
    if not api_classes:
        raise BaselineError("input contains no public/protected API classes")
    packages: dict[str, list[str]] = {}
    package_annotations = {
        item.info.package_name: annotations_value(item.info.attributes)
        for item in parsed
        if item.info.name.endswith("/package-info")
    }
    for item in api_classes:
        packages.setdefault(item.info.package_name, []).append(item.info.name)
    records: list[str] = []
    for package_name in sorted(packages):
        record = (
            "package"
            f"\tname={package_name}"
            f"\tannotations={package_annotations.get(package_name, encode_list([]))}"
        )
        check_forbidden(record)
        records.append(record)
    for parsed_class in sorted(api_classes, key=lambda item: item.info.name):
        item = parsed_class.info
        class_record = (
            "class"
            f"\tname={item.name}"
            f"\tflags={class_flags(item)}"
            f"\tsuper={item.super_name or '-'}"
            f"\tinterfaces={encode_list(item.interfaces)}"
            f"\tsignature={encode_string(item.attributes.signature) if item.attributes.signature is not None else '-'}"
            f"\tpermitted={encode_list(sorted(item.attributes.permitted_subclasses))}"
            f"\tannotations={annotations_value(item.attributes)}"
        )
        check_forbidden(class_record)
        records.append(class_record)
        enum_ordinal = 0
        for field_info in item.fields:
            if not (field_info.access & API_VISIBILITY):
                continue
            constant = "none"
            if field_info.attributes.constant_index is not None:
                entry = parsed_class.cp.entry(field_info.attributes.constant_index, (3, 4, 5, 6, 8))
                constant = encode_constant(field_info.descriptor, entry, parsed_class.cp)
            enum_order = "-"
            if field_info.access & ACC_ENUM:
                enum_order = str(enum_ordinal)
                enum_ordinal += 1
            record = (
                "field"
                f"\towner={item.name}"
                f"\tname={field_info.name}"
                f"\tdescriptor={field_info.descriptor}"
                f"\tflags={field_flags(field_info.access)}"
                f"\tsignature={encode_string(field_info.attributes.signature) if field_info.attributes.signature is not None else '-'}"
                f"\tconstant={constant}"
                f"\tenum-ordinal={enum_order}"
                f"\tannotations={annotations_value(field_info.attributes)}"
            )
            check_forbidden(record)
            records.append(record)
        for component in item.attributes.record_components:
            record = (
                "record-component"
                f"\towner={item.name}"
                f"\tname={component.name}"
                f"\tdescriptor={component.descriptor}"
                f"\tsignature={encode_string(component.attributes.signature) if component.attributes.signature is not None else '-'}"
                f"\tindex={component.declaration_index}"
                f"\tannotations={annotations_value(component.attributes)}"
            )
            check_forbidden(record)
            records.append(record)
        owner_interface = bool(item.access & ACC_INTERFACE)
        for method in item.methods:
            if not (method.access & API_VISIBILITY):
                continue
            default = (
                "annotation-default:none"
                if method.attributes.annotation_default is None
                else "annotation-default:value:" + method.attributes.annotation_default
            )
            parameters = [
                f"{index}:{encode_name(name) if name is not None else '-'}:{flags}"
                for index, (name, flags) in enumerate(method.attributes.method_parameters)
            ]
            record = (
                "method"
                f"\towner={item.name}"
                f"\tname={method.name}"
                f"\tdescriptor={method.descriptor}"
                f"\tflags={method_flags(method.access, owner_interface)}"
                f"\tsignature={encode_string(method.attributes.signature) if method.attributes.signature is not None else '-'}"
                f"\tthrows={encode_list(method.attributes.exceptions)}"
                f"\tparameters={encode_list(parameters)}"
                f"\tannotations={annotations_value(method.attributes)}"
                f"\tparameter-annotations={parameter_annotations_value(method.attributes)}"
                f"\t{default}"
            )
            check_forbidden(record)
            records.append(record)
    return sorted(records), artifact_sha, artifact_size


def canonical_dump(input_path: Path, package_prefix: str | None) -> tuple[bytes, str, int]:
    records, artifact_sha, artifact_size = canonical_records(input_path, package_prefix)
    text = HEADER + "".join(record + "\n" for record in records)
    return text.encode("utf-8"), artifact_sha, artifact_size
