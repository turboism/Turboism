"""Synthetic-jar unit tests for scripts/dev/cubism_symbol_map.py.

Covers the three contract cases of resolve(): rename mapping (class and
member), ambiguity rejection (two structurally identical candidates stay
unresolved), and missing counterpart returning None. Synthetic jars are
emitted by a minimal class-file writer; no Cubism artifacts are needed.
"""
from __future__ import annotations

import importlib.util
import os
from pathlib import Path
import struct
import sys
import tempfile
import unittest
from unittest.mock import patch
import zipfile

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location(
    "cubism_symbol_map", ROOT / "scripts/dev/cubism_symbol_map.py"
)
SM = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
sys.modules["cubism_symbol_map"] = SM
SPEC.loader.exec_module(SM)


# ---------------------------------------------------------------------------
# Minimal class-file emitter (just enough for parse_class in the tool).
# ---------------------------------------------------------------------------

_ACC_PUBLIC = 0x0001
_ACC_PRIVATE = 0x0002
_ACC_STATIC = 0x0008


class _Cp:
    def __init__(self):
        self.entries = [None]
        self.index = {}

    def utf8(self, s: str) -> int:
        key = ("u", s)
        if key not in self.index:
            self.entries.append((1, s.encode("utf-8")))
            self.index[key] = len(self.entries) - 1
        return self.index[key]

    def cls(self, name: str) -> int:
        key = ("c", name)
        if key not in self.index:
            self.entries.append((7, self.utf8(name)))
            self.index[key] = len(self.entries) - 1
        return self.index[key]

    def string(self, s: str) -> int:
        key = ("s", s)
        if key not in self.index:
            self.entries.append((8, self.utf8(s)))
            self.index[key] = len(self.entries) - 1
        return self.index[key]

    def nat(self, name: str, desc: str) -> int:
        key = ("n", name, desc)
        if key not in self.index:
            self.entries.append((12, self.utf8(name), self.utf8(desc)))
            self.index[key] = len(self.entries) - 1
        return self.index[key]

    def methodref(self, owner: str, name: str, desc: str) -> int:
        key = ("m", owner, name, desc)
        if key not in self.index:
            self.entries.append((10, self.cls(owner), self.nat(name, desc)))
            self.index[key] = len(self.entries) - 1
        return self.index[key]

    def dump(self) -> bytes:
        out = struct.pack(">H", len(self.entries))
        for e in self.entries[1:]:
            tag = e[0]
            out += bytes([tag])
            if tag == 1:
                out += struct.pack(">H", len(e[1])) + e[1]
            elif tag == 7:
                out += struct.pack(">H", e[1])
            elif tag == 8:
                out += struct.pack(">H", e[1])
            elif tag in (10, 12):
                out += struct.pack(">HH", e[1], e[2])
            else:
                raise ValueError(tag)
        return out


def emit_class(
    name: str,
    super_name: str = "java/lang/Object",
    interfaces=(),
    fields=(),      # (name, desc, access)
    methods=(),     # (name, desc, access, strings, invokes)
    outer: str | None = None,
    access: int = _ACC_PUBLIC | 0x0020,
) -> bytes:
    cp = _Cp()
    this_i = cp.cls(name)
    super_i = cp.cls(super_name)
    iface_i = [cp.cls(x) for x in interfaces]

    field_blobs = []
    for fn, fd, facc in fields:
        field_blobs.append(
            struct.pack(">HHHH", facc, cp.utf8(fn), cp.utf8(fd), 0)
        )

    method_blobs = []
    for mn, md, macc, strings, invokes in methods:
        code = bytearray()
        for s in strings:
            idx = cp.string(s)
            if idx < 256:
                code += b"\x12" + bytes([idx])  # ldc
            else:
                code += b"\x13" + struct.pack(">H", idx)  # ldc_w
            code += b"\x57"  # pop
        for owner, iname, idesc in invokes:
            code += b"\xb6" + struct.pack(">H", cp.methodref(owner, iname, idesc))
        code += b"\x01\xb0" if md.endswith(";") and not md.endswith(")V") else b"\xb1"
        code_attr = (
            struct.pack(">HHI", 2, 1, len(code))
            + bytes(code)
            + struct.pack(">HH", 0, 0)
        )
        blob = struct.pack(">HHHH", macc, cp.utf8(mn), cp.utf8(md), 1)
        blob += cp.utf8("Code").to_bytes(2, "big")
        blob += struct.pack(">I", len(code_attr)) + code_attr
        method_blobs.append(blob)

    class_attrs = b""
    n_attrs = 0
    if outer is not None:
        inner_attr = struct.pack(
            ">HHHH", cp.cls(name), cp.cls(outer), cp.utf8(_leaf(name)), 0
        )
        body = struct.pack(">H", 1) + inner_attr
        class_attrs = cp.utf8("InnerClasses").to_bytes(2, "big")
        class_attrs += struct.pack(">I", len(body)) + body
        n_attrs = 1

    out = b"\xca\xfe\xba\xbe" + struct.pack(">HH", 0, 52) + cp.dump()
    out += struct.pack(">HHH", access, this_i, super_i)
    out += struct.pack(">H", len(iface_i)) + b"".join(
        struct.pack(">H", x) for x in iface_i
    )
    out += struct.pack(">H", len(field_blobs)) + b"".join(field_blobs)
    out += struct.pack(">H", len(method_blobs)) + b"".join(method_blobs)
    out += struct.pack(">H", n_attrs) + class_attrs
    return out


def _leaf(internal: str) -> str:
    return internal.rsplit("/", 1)[-1].rsplit("$", 1)[-1]


def emit_jar(path: Path, classes: dict[str, bytes]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(path, "w") as zf:
        for name, data in classes.items():
            zf.writestr(name + ".class", data)


PUB = _ACC_PUBLIC
STATIC = _ACC_STATIC
PRIV = _ACC_PRIVATE


def jar_a() -> dict[str, bytes]:
    return {
        "com/live2d/app/Main": emit_class(
            "com/live2d/app/Main",
            methods=[
                ("splash", "()Lcom/live2d/win/X;", PRIV, ["splash-factory"], []),
                ("start", "([Ljava/lang/String;)V", PUB | STATIC, [], []),
            ],
            fields=[("instance", "Lcom/live2d/app/Main;", PUB | STATIC)],
        ),
        # Renamed in B to com/live2d/win/q; readable member names anchor it.
        "com/live2d/win/X": emit_class(
            "com/live2d/win/X",
            super_name="java/awt/Window",
            methods=[
                ("setVisible", "(Z)V", PUB, ["win-x-title"], []),
                ("repaint", "()V", PUB, [], []),
            ],
        ),
        # Renamed in B to com/live2d/util/w; unique strings anchor it.
        "com/live2d/util/Helper": emit_class(
            "com/live2d/util/Helper",
            methods=[
                ("h", "()V", PUB, ["helper-unique-alpha", "helper-unique-beta"], []),
                ("g", "(I)I", PRIV, [], []),
            ],
            fields=[("count", "I", PRIV)],
        ),
        # Class only present in version A.
        "com/live2d/only/AOnly": emit_class(
            "com/live2d/only/AOnly",
            methods=[("x", "()V", PUB, ["gone-in-b"], [])],
        ),
        # Two structurally identical twins exist in B -> ambiguous.
        "com/live2d/tw/Twin": emit_class(
            "com/live2d/tw/Twin",
            methods=[("run", "()V", PUB, ["twin-shared-string"], [])],
        ),
    }


def jar_b() -> dict[str, bytes]:
    twin_members = [("run", "()V", PUB, ["twin-shared-string"], [])]
    return {
        "com/live2d/app/Main": emit_class(
            "com/live2d/app/Main",
            methods=[
                ("zz", "()Lcom/live2d/win/q;", PRIV, ["splash-factory"], []),
                ("start", "([Ljava/lang/String;)V", PUB | STATIC, [], []),
            ],
            fields=[("instance", "Lcom/live2d/app/Main;", PUB | STATIC)],
        ),
        "com/live2d/win/q": emit_class(
            "com/live2d/win/q",
            super_name="java/awt/Window",
            methods=[
                ("setVisible", "(Z)V", PUB, ["win-x-title"], []),
                ("repaint", "()V", PUB, [], []),
            ],
        ),
        "com/live2d/util/w": emit_class(
            "com/live2d/util/w",
            methods=[
                ("a", "()V", PUB, ["helper-unique-alpha", "helper-unique-beta"], []),
                ("b", "(I)I", PRIV, [], []),
            ],
            fields=[("c", "I", PRIV)],
        ),
        "com/live2d/tw/Alpha": emit_class(
            "com/live2d/tw/Alpha", methods=twin_members
        ),
        "com/live2d/tw/Beta": emit_class(
            "com/live2d/tw/Beta", methods=twin_members
        ),
    }


class SymbolMapTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory()
        ref = Path(cls.tmp.name)
        emit_jar(ref / "9.9.01" / "Live2D_Test.jar", jar_a())
        emit_jar(ref / "9.9.02" / "Live2D_Test.jar", jar_b())
        cls.env = patch.dict(os.environ, {"TURBOISM_CUBISM_REF": str(ref)})
        cls.env.start()
        cls.A = "9.9.01"
        cls.B = "9.9.02"

    @classmethod
    def tearDownClass(cls):
        cls.env.stop()
        cls.tmp.cleanup()

    def sel(self, kind, owner, member=None, desc=None):
        s = {"kind": kind, "ownerInternalName": owner, "status": "VERIFIED_STATIC"}
        if member is not None:
            s["memberName"] = member
        if desc is not None:
            s["descriptor"] = desc
        return s

    def test_class_rename_resolves(self):
        out = SM.resolve(
            self.sel("class", "com/live2d/util/Helper"), self.A, self.B
        )
        self.assertIsNotNone(out)
        self.assertEqual(out["ownerInternalName"], "com/live2d/util/w")
        self.assertNotIn("status", out)

    def test_class_rename_via_readable_members(self):
        out = SM.resolve(self.sel("class", "com/live2d/win/X"), self.A, self.B)
        self.assertIsNotNone(out)
        self.assertEqual(out["ownerInternalName"], "com/live2d/win/q")

    def test_method_rename_in_stable_class(self):
        out = SM.resolve(
            self.sel(
                "method",
                "com/live2d/app/Main",
                "splash",
                "()Lcom/live2d/win/X;",
            ),
            self.A,
            self.B,
        )
        self.assertIsNotNone(out)
        self.assertEqual(out["memberName"], "zz")
        # The descriptor must be rewritten into target-version symbols.
        self.assertEqual(out["descriptor"], "()Lcom/live2d/win/q;")

    def test_field_rename(self):
        out = SM.resolve(
            self.sel("field", "com/live2d/util/Helper", "count", "I"),
            self.A,
            self.B,
        )
        self.assertIsNotNone(out)
        self.assertEqual(out["memberName"], "c")

    def test_ambiguous_candidates_stay_unresolved(self):
        out = SM.resolve(self.sel("class", "com/live2d/tw/Twin"), self.A, self.B)
        self.assertIsNone(out)

    def test_missing_counterpart_returns_none(self):
        out = SM.resolve(self.sel("class", "com/live2d/only/AOnly"), self.A, self.B)
        self.assertIsNone(out)

    def test_missing_member_returns_none(self):
        out = SM.resolve(
            self.sel(
                "method", "com/live2d/app/Main", "nonexistent", "()V"
            ),
            self.A,
            self.B,
        )
        self.assertIsNone(out)


if __name__ == "__main__":
    unittest.main()
