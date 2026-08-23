"""Shared primitives for deterministic SDK API canonicalization."""
from __future__ import annotations

import hashlib
import struct
from pathlib import Path
from typing import Iterable

SCHEMA_VERSION = 1
GENERATOR_VERSION = 1
HEADER = f"sdk-api-schema\t{SCHEMA_VERSION}\n" f"sdk-api-generator\t{GENERATOR_VERSION}\n"
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
    "com/live2d/", "dev/turboism/core/", "dev/turboism/hook/", "dev/turboism/mapping/",
    "dev/turboism/adapter/", "dev/turboism/internal/", "org/objectweb/asm/",
    "net/bytebuddy/", "com/fasterxml/jackson/", "org/slf4j/", "org/junit/",
    "com/google/common/", "lombok/", "javax/swing/", "java/awt/",
)

# Package-scoped exception to FORBIDDEN_API_TOKENS, authorized by
# specs/window-icon-port-v1.md section 4a (formal review path A) and the
# window-icon-port-v1 baseline v5 Oracle review record: only records owned by
# the packages in EXCEPTED_API_TOKEN_PACKAGES may expose the tokens in
# EXCEPTED_API_TOKENS (the plugin-owned JDK window factory Preview API is the
# sole documented consumer). The exception is strictly limited to this
# package; no other package or token may be added here without a new review.
EXCEPTED_API_TOKEN_PACKAGES = ("dev/turboism/sdk/ui/window/",)
EXCEPTED_API_TOKENS = ("java/awt/", "javax/swing/")


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
    return f"string:{len(units)}:" + "-".join(f"{unit:04X}" for unit in units)


def encode_name(value: str) -> str:
    return encode_string(value)


def encode_list(values: Iterable[str]) -> str:
    items = list(values)
    return f"list:{len(items)}:[" + ",".join(f"{len(item)}:{item}" for item in items) + "]"


def encode_float32_bits(bits: int) -> str:
    return f"float:0x{bits & 0xFFFFFFFF:08X}"


def encode_float64_bits(bits: int) -> str:
    return f"double:0x{bits & 0xFFFFFFFFFFFFFFFF:016X}"
