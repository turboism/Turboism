"""Class-file and archive loading for SDK canonicalization."""
from __future__ import annotations

import hashlib
import zipfile
from pathlib import Path

from sdk_api_baseline_annotation_attributes import parse_attributes
from sdk_api_baseline_common import ACC_FINAL, API_VISIBILITY, BaselineError, sha256_file
from sdk_api_baseline_model import (
    Attributes,
    ClassInfo,
    ConstantPool,
    FieldInfo,
    MethodInfo,
    ParsedClass,
    Reader,
)


def parse_class_with_pool(data: bytes, label: str) -> ParsedClass:
    reader = Reader(data, label)
    _verify_magic(reader, label)
    reader.take(4)
    cp = ConstantPool(reader)
    info = _parse_class_info(reader, cp)
    if reader.pos != len(data):
        raise BaselineError(f"trailing bytes after class structure: {label}")
    return ParsedClass(info, cp)


def _verify_magic(reader: Reader, label: str) -> None:
    if reader.u4() != 0xCAFEBABE:
        raise BaselineError(f"not a class file: {label}")


def _parse_class_info(reader: Reader, cp: ConstantPool) -> ClassInfo:
    access = reader.u2()
    name = cp.class_name(reader.u2())
    super_index = reader.u2()
    interfaces = [cp.class_name(reader.u2()) for _ in range(reader.u2())]
    fields = _parse_fields(reader, cp)
    methods = _parse_methods(reader, cp)
    attrs = parse_attributes(reader, cp)
    return ClassInfo(
        name,
        access,
        cp.class_name(super_index) if super_index else None,
        interfaces,
        attrs,
        fields,
        methods,
        effective_class_access(name, access, attrs),
        name.rpartition("/")[0],
        bool(attrs.record_components),
    )


def _parse_fields(reader: Reader, cp: ConstantPool) -> list[FieldInfo]:
    return [_parse_field(reader, cp, index) for index in range(reader.u2())]


def _parse_field(reader: Reader, cp: ConstantPool, index: int) -> FieldInfo:
    return FieldInfo(
        reader.u2(),
        cp.utf(reader.u2()),
        cp.utf(reader.u2()),
        parse_attributes(reader, cp),
        index,
    )


def _parse_methods(reader: Reader, cp: ConstantPool) -> list[MethodInfo]:
    return [_parse_method(reader, cp) for _ in range(reader.u2())]


def _parse_method(reader: Reader, cp: ConstantPool) -> MethodInfo:
    return MethodInfo(
        reader.u2(),
        cp.utf(reader.u2()),
        cp.utf(reader.u2()),
        parse_attributes(reader, cp),
    )


def effective_class_access(name: str, class_access: int, attrs: Attributes) -> int:
    for inner_name, _outer, _simple, access in attrs.inner_classes:
        if inner_name == name:
            return access
    return class_access


def load_parsed_classes(input_path: Path, package_prefix: str | None) -> tuple[list[ParsedClass], str, int]:
    entries, artifact_sha, artifact_size = _load_class_entries(input_path)
    entries = _filter_package(entries, package_prefix)
    if not entries:
        raise BaselineError("input contains no matching class files")
    parsed = [parse_class_with_pool(data, name) for name, data in entries]
    _mark_non_sealed_children(parsed)
    return parsed, artifact_sha, artifact_size


def _load_class_entries(input_path: Path) -> tuple[list[tuple[str, bytes]], str, int]:
    if not input_path.exists():
        raise BaselineError(f"input is missing: {input_path}")
    if input_path.is_dir():
        return _directory_entries(input_path)
    if input_path.is_file() and input_path.suffix.lower() in (".jar", ".zip"):
        return _archive_entries(input_path)
    raise BaselineError("input must be a class directory or JAR/ZIP")


def _directory_entries(input_path: Path) -> tuple[list[tuple[str, bytes]], str, int]:
    entries = [
        (path.relative_to(input_path).as_posix(), path.read_bytes())
        for path in sorted(input_path.rglob("*.class"))
    ]
    digest = hashlib.sha256()
    for name, data in entries:
        digest.update(name.encode("utf-8"))
        digest.update(b"\0")
        digest.update(hashlib.sha256(data).digest())
        digest.update(b"\n")
    return entries, digest.hexdigest(), sum(len(data) for _, data in entries)


def _archive_entries(input_path: Path) -> tuple[list[tuple[str, bytes]], str, int]:
    with zipfile.ZipFile(input_path) as archive:
        names = sorted(
            name for name in archive.namelist()
            if name.endswith(".class") and not name.startswith("META-INF/versions/")
        )
        entries = [(name, archive.read(name)) for name in names]
    return entries, sha256_file(input_path), input_path.stat().st_size


def _filter_package(entries: list[tuple[str, bytes]], package_prefix: str | None) -> list[tuple[str, bytes]]:
    if not package_prefix:
        return entries
    prefix = package_prefix.replace(".", "/").strip("/") + "/"
    return [(name, data) for name, data in entries if name.startswith(prefix)]


def _mark_non_sealed_children(parsed: list[ParsedClass]) -> None:
    sealed_children = {
        child
        for parsed_class in parsed
        for child in parsed_class.info.attributes.permitted_subclasses
    }
    for parsed_class in parsed:
        info = parsed_class.info
        if _is_non_sealed_child(info, sealed_children):
            info.non_sealed = True


def _is_non_sealed_child(info: ClassInfo, sealed_children: set[str]) -> bool:
    return (
        info.name in sealed_children
        and not info.attributes.permitted_subclasses
        and not (info.effective_access & ACC_FINAL)
    )


def api_classes(parsed: list[ParsedClass]) -> list[ParsedClass]:
    return [item for item in parsed if item.info.effective_access & API_VISIBILITY]
