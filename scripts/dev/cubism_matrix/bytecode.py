"""Classfile and JAR parsing and structural selector verification."""
from __future__ import annotations

from pathlib import Path
import struct
import zipfile

def decode_mutf8(data: bytes) -> str:
    """Decode Java modified UTF-8 the way DataInputStream.readUTF does."""
    chars = []
    i = 0
    while i < len(data):
        b = data[i]
        if b < 0x80:
            chars.append(chr(b))
            i += 1
        elif b & 0xE0 == 0xC0:
            chars.append(chr(((b & 0x1F) << 6) | (data[i + 1] & 0x3F)))
            i += 2
        else:
            chars.append(
                chr(
                    ((b & 0x0F) << 12)
                    | ((data[i + 1] & 0x3F) << 6)
                    | (data[i + 2] & 0x3F)
                )
            )
            i += 3
    text = "".join(chars)
    # Supplementary characters are encoded as surrogate pairs; recombine them so
    # name comparisons match readUTF behaviour.
    out = []
    i = 0
    while i < len(text):
        c = text[i]
        if (
            i + 1 < len(text)
            and 0xD800 <= ord(c) <= 0xDBFF
            and 0xDC00 <= ord(text[i + 1]) <= 0xDFFF
        ):
            out.append(
                chr(0x10000 + ((ord(c) - 0xD800) << 10) + (ord(text[i + 1]) - 0xDC00))
            )
            i += 2
        else:
            out.append(c)
            i += 1
    return "".join(out)


def parse_class(data: bytes):
    """Return (access, this_name, super_name, interfaces, fields, methods)."""
    if len(data) < 10 or data[:4] != b"\xca\xfe\xba\xbe":
        raise ValueError("bad magic")
    pos = 8
    (cp_count,) = struct.unpack_from(">H", data, pos)
    pos += 2
    utf8 = [None] * cp_count
    cls = [0] * cp_count
    i = 1
    while i < cp_count:
        tag = data[pos]
        pos += 1
        if tag == 1:
            (ln,) = struct.unpack_from(">H", data, pos)
            pos += 2
            utf8[i] = decode_mutf8(data[pos : pos + ln])
            pos += ln
        elif tag in (3, 4):
            pos += 4
        elif tag in (5, 6):
            pos += 8
            i += 1
        elif tag == 7:
            (cls[i],) = struct.unpack_from(">H", data, pos)
            pos += 2
        elif tag in (8, 16, 19, 20):
            pos += 2
        elif tag in (9, 10, 11, 12, 17, 18):
            pos += 4
        elif tag == 15:
            pos += 3
        else:
            raise ValueError(f"bad constant-pool tag {tag}")
        i += 1

    def cname(idx):
        if idx <= 0 or idx >= cp_count:
            return None
        ni = cls[idx]
        return utf8[ni] if ni else None

    access, this_i, super_i = struct.unpack_from(">HHH", data, pos)
    pos += 6
    (iface_count,) = struct.unpack_from(">H", data, pos)
    pos += 2
    interfaces = []
    for _ in range(iface_count):
        (ci,) = struct.unpack_from(">H", data, pos)
        pos += 2
        interfaces.append(cname(ci))

    def members():
        nonlocal pos
        (count,) = struct.unpack_from(">H", data, pos)
        pos += 2
        out = []
        for _ in range(count):
            a, n, d = struct.unpack_from(">HHH", data, pos)
            pos += 6
            (ac,) = struct.unpack_from(">H", data, pos)
            pos += 2
            for _ in range(ac):
                pos += 2
                (ln,) = struct.unpack_from(">I", data, pos)
                pos += 4 + ln
            out.append((utf8[n], utf8[d], a))
        return out

    fields = members()
    methods = members()
    return access, cname(this_i), cname(super_i), interfaces, fields, methods


class JarIndex:
    """Class-metadata index over one jar, faithful to StaticSelectorVerifier."""

    def __init__(self, path: Path):
        self.path = path
        self.zf = zipfile.ZipFile(path)
        self.names = set(self.zf.namelist())
        self._cache: dict[str, object] = {}

    def metadata(self, owner: str):
        if owner in self._cache:
            return self._cache[owner]
        entry = owner + ".class"
        if entry not in self.names:
            self._cache[owner] = None
            return None
        try:
            meta = parse_class(self.zf.read(entry))
        except Exception:
            meta = "invalid"
        if meta != "invalid" and meta[1] != owner:
            meta = "invalid"
        self._cache[owner] = meta
        return meta


def verify_selector(jar: JarIndex, sel: dict) -> str:
    owner = sel["ownerInternalName"]
    kind = sel["kind"]
    meta = jar.metadata(owner)
    if meta is None:
        return "CLASS_MISSING"
    if meta == "invalid":
        return "INVALID_CLASS_FILE"
    access, _this, super_name, interfaces, fields, methods = meta
    req = sel.get("requiredAccessFlags", 0)
    forb = sel.get("forbiddenAccessFlags", 0)
    if kind == "class":
        return (
            "VERIFIED_STATIC"
            if (access & req) == req and (access & forb) == 0
            else "ACCESS_MISMATCH"
        )
    if kind == "inherits":
        ancestor = sel["memberName"]
        return (
            "VERIFIED_STATIC"
            if ancestor == super_name or ancestor in interfaces
            else "SUPERTYPE_MISMATCH"
        )
    pool = fields if kind == "field" else methods
    same_name = [m for m in pool if m[0] == sel["memberName"]]
    if not same_name:
        return "MEMBER_MISSING"
    same_desc = [m for m in same_name if m[1] == sel["descriptor"]]
    if not same_desc:
        return "DESCRIPTOR_MISMATCH"
    for _n, _d, a in same_desc:
        if (a & req) == req and (a & forb) == 0:
            return "VERIFIED_STATIC"
    return "ACCESS_MISMATCH"


def verified_aliases(jar: JarIndex, record: dict) -> set[str]:
    return {
        s["alias"]
        for s in record["selectors"]
        if verify_selector(jar, s) == "VERIFIED_STATIC"
    }
