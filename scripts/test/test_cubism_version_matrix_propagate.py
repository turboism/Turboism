"""Synthetic-jar coverage for cross-version record propagation."""
from __future__ import annotations

import importlib.util
import json
import os
import struct
import tempfile
import unittest
import zipfile
from pathlib import Path
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location(
    "cubism_version_matrix", ROOT / "scripts/dev/cubism_version_matrix.py"
)
MATRIX = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
with tempfile.TemporaryDirectory() as reference_directory:
    # Propagation tests run on synthetic jars; no proprietary host jar needed.
    with patch.dict(os.environ, {"TURBOISM_CUBISM_REF": reference_directory}):
        SPEC.loader.exec_module(MATRIX)

FAMILY = "ui-test"


def class_bytes(
    name,
    super_name="java/lang/Object",
    access=0x0021,
    interfaces=(),
    fields=(),
    methods=(),
):
    """Minimal .class image covering what JarIndex/parse_class reads."""
    entries = []

    def utf8(text):
        data = text.encode("utf-8")
        entries.append(b"\x01" + struct.pack(">H", len(data)) + data)
        return len(entries)

    def cls(class_name):
        entries.append(b"\x07" + struct.pack(">H", utf8(class_name)))
        return len(entries)

    this_i = cls(name)
    super_i = cls(super_name)
    iface_i = [cls(i) for i in interfaces]
    field_i = [(a, utf8(n), utf8(d)) for a, n, d in fields]
    method_i = [(a, utf8(n), utf8(d)) for a, n, d in methods]
    out = [b"\xca\xfe\xba\xbe" + struct.pack(">HHH", 0, 52, len(entries) + 1)]
    out += entries
    out.append(struct.pack(">HHH", access, this_i, super_i))
    out.append(struct.pack(">H", len(iface_i)))
    out += [struct.pack(">H", i) for i in iface_i]
    for pool in (field_i, method_i):
        out.append(struct.pack(">H", len(pool)))
        for access_flags, name_i, desc_i in pool:
            out.append(struct.pack(">HHHH", access_flags, name_i, desc_i, 0))
    return b"".join(out)


def write_jar(version_dir: Path, classes: dict[str, bytes], jar="Live2D_Cubism.jar"):
    version_dir.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(version_dir / jar, "w") as archive:
        for name, data in classes.items():
            archive.writestr(name + ".class", data)


def make_selector(alias, owner, kind="class", member=None, desc=None, req=1, forb=0, mapping_version="9.0.00"):
    tag = mapping_version.replace(".", "_")
    leaf = alias.rsplit(".", 1)[-1].replace("-", "_")
    return {
        "mappingId": f"cubism.mapping.v{tag}.ui_test.{kind}.{leaf}",
        "alias": alias,
        "kind": kind,
        "ownerInternalName": owner,
        "memberName": member,
        "descriptor": desc,
        "requiredAccessFlags": req,
        "forbiddenAccessFlags": forb,
        "status": "VERIFIED_STATIC",
    }


def make_record(version, selectors, capabilities=("cubism.ui-test.cap",)):
    return {
        "format": "turboism.verification.record/v1",
        "schemaVersion": 1,
        "verificationId": f"cubism-{version}.{FAMILY}.static",
        "adapterSliceId": "adapter.ui-test",
        "capabilityIds": list(capabilities),
        "cubismVersion": version,
        "profileId": f"cubism-{version}",
        "artifact": {"name": "Live2D_Cubism.jar", "size": 0, "sha256": "0" * 64},
        "evidenceType": "STATIC_CLASSFILE",
        "evidencePath": f"compatibility/cubism/verification/cubism-{version}-{FAMILY}.json",
        "owner": "test-owner",
        "status": "VERIFIED_STATIC",
        "verifiedBy": "test",
        "verifiedAt": "2026-01-01T00:00:00Z",
        "safeMode": "fail closed",
        "selectors": selectors,
        "capabilityConditions": {c: ["declaredGeneration"] for c in capabilities},
    }


def make_pack(version, selectors):
    return {
        "format": "turboism.mapping.pack",
        "schemaVersion": 1,
        "status": "DRAFT",
        "source": "derived-from-verified-record",
        "cubismVersion": version,
        "entries": [
            {
                "semanticName": s["mappingId"],
                "name": s["alias"],
                "kind": s["kind"],
                "runtime": s["ownerInternalName"]
                if s["kind"] == "class"
                else s["memberName"],
                "profile": f"cubism-{version}",
                "status": "DRAFT",
                "confidence": "high",
                **({"descriptor": s["descriptor"]} if s["kind"] != "class" else {}),
                "x.verification": {
                    "ownerInternalName": s["ownerInternalName"],
                    "requiredAccessFlags": s["requiredAccessFlags"],
                    "forbiddenAccessFlags": s["forbiddenAccessFlags"],
                },
            }
            for s in selectors
        ],
    }


APP_CLASS = class_bytes("com/acme/App")
WIDGET = class_bytes(
    "com/acme/Widget",
    fields=[(0x0001, "count", "I")],
    methods=[(0x0001, "render", "()V")],
)
WIDGET_BOTH = class_bytes(
    "com/acme/Widget",
    fields=[(0x0001, "count", "I")],
    methods=[(0x0001, "render", "()V"), (0x0001, "renderV2", "()V")],
)
WIDGET_NO_RENDER = class_bytes(
    "com/acme/Widget",
    fields=[(0x0001, "count", "I")],
    methods=[(0x0001, "renderV2", "()V")],
)

SOURCE_SELECTORS = [
    make_selector("cubism.ui-test.app.class", "com/acme/App"),
    make_selector(
        "cubism.ui-test.widget.render",
        "com/acme/Widget",
        kind="method",
        member="render",
        desc="()V",
    ),
    make_selector(
        "cubism.ui-test.widget.count",
        "com/acme/Widget",
        kind="field",
        member="count",
        desc="I",
    ),
]


class PropagationTest(unittest.TestCase):
    def setUp(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.root = Path(tmp.name)
        self.ref = self.root / "cubism-ref"
        self.verification = self.root / "verification"
        self.packs = self.root / "packs"
        self.verification.mkdir()
        self.packs.mkdir()
        patcher = patch.multiple(
            MATRIX,
            REF_DIR=self.ref,
            VERIFICATION_DIR=self.verification,
            PACK_DIR=self.packs,
        )
        patcher.start()
        self.addCleanup(patcher.stop)
        write_jar(
            self.ref / "9.0.00",
            {"com/acme/App": APP_CLASS, "com/acme/Widget": WIDGET},
        )
        self.write_record("9.0.00", make_record("9.0.00", SOURCE_SELECTORS))
        self.write_pack("9.0.00", make_pack("9.0.00", SOURCE_SELECTORS))

    def write_record(self, version, record):
        path = self.verification / f"cubism-{version}-{FAMILY}.json"
        path.write_text(json.dumps(record, indent=2) + "\n")
        return path

    def write_pack(self, version, pack):
        path = self.packs / f"cubism-{version}-{FAMILY}.json"
        path.write_text(json.dumps(pack, indent=2) + "\n")
        return path

    def propagate(self, targets, write=True, resolver=None, from_version="9.0.00"):
        return MATRIX.propagate_family(FAMILY, from_version, targets, write, resolver)

    def test_full_verification_writes_record_and_pack(self):
        write_jar(
            self.ref / "9.0.01",
            {"com/acme/App": APP_CLASS, "com/acme/Widget": WIDGET},
        )
        report = self.propagate(["9.0.01"])
        result = report["results"][0]
        self.assertEqual("written", result["outcome"])
        record_path = self.verification / f"cubism-9.0.01-{FAMILY}.json"
        pack_path = self.packs / f"cubism-9.0.01-{FAMILY}.json"
        self.assertTrue(record_path.is_file())
        self.assertTrue(pack_path.is_file())
        record = json.loads(record_path.read_text())
        self.assertEqual("9.0.01", record["cubismVersion"])
        self.assertEqual("cubism-9.0.01.ui-test.static", record["verificationId"])
        self.assertEqual(["cubism.ui-test.cap"], record["capabilityIds"])
        self.assertEqual("cubism-9.0.01", record["profileId"])
        self.assertEqual(
            {"cubism.ui-test.cap": ["declaredGeneration"]},
            record["capabilityConditions"],
        )
        for selector in record["selectors"]:
            self.assertIn("v9_0_01", selector["mappingId"])
            self.assertEqual("VERIFIED_STATIC", selector["status"])
        facts = MATRIX.artifact_facts("9.0.01")
        self.assertEqual(facts, record["artifact"])
        pack = json.loads(pack_path.read_text())
        self.assertEqual(
            [s["alias"] for s in record["selectors"]],
            [e["name"] for e in pack["entries"]],
        )
        self.assertTrue(record_path.read_text().endswith("}\n"))

    def test_dry_run_writes_nothing(self):
        write_jar(
            self.ref / "9.0.02",
            {"com/acme/App": APP_CLASS, "com/acme/Widget": WIDGET},
        )
        report = self.propagate(["9.0.02"], write=False)
        self.assertEqual("written", report["results"][0]["outcome"])
        self.assertFalse(
            (self.verification / "cubism-9.0.02-ui-test.json").exists()
        )
        self.assertEqual([], report["filesWritten"])

    def test_partial_failure_writes_nothing(self):
        write_jar(
            self.ref / "9.0.02",
            {"com/acme/App": APP_CLASS, "com/acme/Widget": WIDGET_NO_RENDER},
        )
        report = self.propagate(["9.0.02"])
        result = report["results"][0]
        self.assertEqual("blocked", result["outcome"])
        self.assertFalse((self.verification / "cubism-9.0.02-ui-test.json").exists())
        self.assertFalse((self.packs / "cubism-9.0.02-ui-test.json").exists())
        failures = {f["alias"]: f["status"] for f in result["failures"]}
        self.assertEqual(
            {"cubism.ui-test.widget.render": "MEMBER_MISSING"}, failures
        )
        pending = {(p["version"], p["alias"]): p["status"] for p in report["pending"]}
        self.assertEqual(
            {("9.0.02", "cubism.ui-test.widget.render"): "MEMBER_MISSING"}, pending
        )

    def test_conflicting_existing_selector_is_kept_and_reported(self):
        write_jar(
            self.ref / "9.0.03",
            {"com/acme/App": APP_CLASS, "com/acme/Widget": WIDGET_BOTH},
        )
        divergent = make_selector(
            "cubism.ui-test.widget.render",
            "com/acme/Widget",
            kind="method",
            member="renderV2",
            desc="()V",
            mapping_version="9.0.03",
        )
        existing = make_record(
            "9.0.03",
            [SOURCE_SELECTORS[0], divergent],
        )
        existing["artifact"] = MATRIX.artifact_facts("9.0.03")
        self.write_record("9.0.03", existing)
        self.write_pack("9.0.03", make_pack("9.0.03", existing["selectors"]))
        report = self.propagate(["9.0.03"])
        result = report["results"][0]
        self.assertEqual("merged", result["outcome"])
        self.assertEqual(["cubism.ui-test.widget.count"], result["selectorsAdded"])
        self.assertEqual(1, len(result["conflicts"]))
        conflict = result["conflicts"][0]
        self.assertEqual("selector-definition", conflict["kind"])
        self.assertEqual("cubism.ui-test.widget.render", conflict["alias"])
        merged = json.loads(
            (self.verification / "cubism-9.0.03-ui-test.json").read_text()
        )
        kept = {
            s["alias"]: s["memberName"] for s in merged["selectors"]
        }
        self.assertEqual("renderV2", kept["cubism.ui-test.widget.render"])
        self.assertEqual(3, len(merged["selectors"]))
        pack = json.loads((self.packs / "cubism-9.0.03-ui-test.json").read_text())
        self.assertEqual(
            [s["alias"] for s in merged["selectors"]],
            [e["name"] for e in pack["entries"]],
        )

    def test_missing_hook_falls_back_to_unresolved(self):
        self.assertIsNone(
            MATRIX.load_symbol_resolver(self.root / "no_such_module.py")
        )
        write_jar(
            self.ref / "9.0.04",
            {"com/acme/App": APP_CLASS, "com/acme/Widget": WIDGET_NO_RENDER},
        )
        report = self.propagate(["9.0.04"], resolver=None)
        result = report["results"][0]
        self.assertEqual("blocked", result["outcome"])
        self.assertEqual(
            "unresolved", result["failures"][0]["via"]
        )

    def test_symbol_map_candidate_rescues_obfuscated_member(self):
        module = self.root / "cubism_symbol_map.py"
        module.write_text(
            "def resolve(selector, from_version, to_version):\n"
            "    if selector['memberName'] == 'render':\n"
            "        return {'memberName': 'renderV2'}\n"
            "    return None\n"
        )
        resolver = MATRIX.load_symbol_resolver(module)
        self.assertIsNotNone(resolver)
        write_jar(
            self.ref / "9.0.05",
            {"com/acme/App": APP_CLASS, "com/acme/Widget": WIDGET_NO_RENDER},
        )
        report = self.propagate(["9.0.05"], resolver=resolver)
        self.assertEqual("written", report["results"][0]["outcome"])
        record = json.loads(
            (self.verification / "cubism-9.0.05-ui-test.json").read_text()
        )
        member = {
            s["alias"]: s["memberName"] for s in record["selectors"]
        }
        self.assertEqual("renderV2", member["cubism.ui-test.widget.render"])
        self.assertTrue(report["symbolMapHook"])

    def test_retag_mapping_id(self):
        self.assertEqual(
            "cubism.mapping.v9_0_01.ui_test.method.render",
            MATRIX.retag_mapping_id(
                "cubism.mapping.v9_0_00.ui_test.method.render", "9.0.00", "9.0.01"
            ),
        )
        self.assertEqual(
            "cubism.mapping.v9_0_01.ui_test.method.render",
            MATRIX.retag_mapping_id(
                "cubism.mapping.v9000.ui_test.method.render", "9.0.00", "9.0.01"
            ),
        )
        # A foreign generation tag is provenance, not retagged.
        self.assertEqual(
            "cubism.mapping.v9_0_02.ui_test.method.render",
            MATRIX.retag_mapping_id(
                "cubism.mapping.v9_0_02.ui_test.method.render", "9.0.00", "9.0.01"
            ),
        )

    def test_unchanged_rewrite_is_idempotent(self):
        write_jar(
            self.ref / "9.0.06",
            {"com/acme/App": APP_CLASS, "com/acme/Widget": WIDGET},
        )
        first = self.propagate(["9.0.06"])
        self.assertEqual("written", first["results"][0]["outcome"])
        second = self.propagate(["9.0.06"])
        self.assertEqual("unchanged", second["results"][0]["outcome"])
        self.assertEqual([], second["filesWritten"])


if __name__ == "__main__":
    unittest.main()
