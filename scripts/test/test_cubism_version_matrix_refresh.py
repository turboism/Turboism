"""Regression coverage for repeatable additive version-matrix wiring."""
from __future__ import annotations

import importlib.util
import json
import os
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
DEV_DIR = ROOT / "scripts/dev"
if str(DEV_DIR) not in sys.path:
    sys.path.insert(0, str(DEV_DIR))
SCRIPTS_DIR = ROOT / "scripts"
if str(SCRIPTS_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPTS_DIR))

SPEC = importlib.util.spec_from_file_location(
    "cubism_version_matrix", ROOT / "scripts/dev/cubism_version_matrix.py"
)
MATRIX = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
with tempfile.TemporaryDirectory() as reference_directory:
    # Wiring uses committed records only; no proprietary host JAR is needed.
    with patch.dict(os.environ, {"TURBOISM_CUBISM_REF": reference_directory}):
        SPEC.loader.exec_module(MATRIX)


class VersionTableTest(unittest.TestCase):
    def test_version_facts_derive_from_the_canonical_release_table(self):
        import cubism_matrix.constants as constants_module

        releases = json.loads(constants_module.REVIEWED_RELEASES.read_text(encoding="utf-8"))["releases"]
        admitted = tuple(release["version"] for release in releases)
        self.assertEqual(admitted, constants_module.ADMITTED_VERSIONS)
        self.assertEqual(admitted, constants_module.ALL_VERSIONS)
        self.assertEqual(
            {release["version"]: release["build"] for release in releases},
            constants_module.DECLARED_BUILDS,
        )
        self.assertEqual(
            tuple(v for v in admitted if v not in constants_module.REVIEWED),
            constants_module.TARGET_VERSIONS,
        )
        self.assertEqual(
            set(admitted),
            set(constants_module.TARGET_VERSIONS) | set(constants_module.REVIEWED),
        )

    def test_a_new_release_enters_the_derived_views(self):
        import cubism_matrix.constants as constants_module

        grown = json.loads(constants_module.REVIEWED_RELEASES.read_text(encoding="utf-8"))
        grown["releases"].append(dict(grown["releases"][-1], version="9.9.99", build=999990001))
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / constants_module.REVIEWED_RELEASES.name
            path.write_text(json.dumps(grown), encoding="utf-8")
            with patch.object(constants_module, "REVIEWED_RELEASES", path):
                releases = constants_module._reviewed_releases()
        self.assertEqual("9.9.99", releases[-1]["version"])
        self.assertEqual(999990001, releases[-1]["build"])


class EditorModelRefreshTest(unittest.TestCase):
    def test_no_alpha_dependencies_apply_only_to_explicit_legacy_versions(self):
        capability = "inspector.write"
        constants = {
            "EditorInspectorDrawableWriteNoAlphaCompositionSelectorContract": {
                "CAPABILITY_ID": capability, "SUPPORTED_CUBISM_VERSIONS": {"5.2.02", "5.2.03"},
            },
            "EditorInspectorDrawableWriteSelectorContract": {"ALPHA_COMPOSITION_ALIASES": {"alpha"}},
            "EditorTextureSelectorContract": {"WRITE_CAPABILITY_ID": "texture.write"},
            "EditorModelInstanceReadSelectorContract": {"CAPABILITY_ID": "instance.read"},
            "EditorEditSessionSelectorContract": {},
            "EditorRawImagePsdSelectorContract": {
                "CAPABILITY_ID": "cubism.editor-model.psd.raw-image.export",
                "TRANSPARENCY_SHAPES_ABSENT_VERSIONS": set(),
                "TRANSPARENCY_SHAPES_ALIASES": set(),
            },
        }
        requirements = {capability: {"color", "alpha"}}
        for version in ("5.2.02", "5.2.03", "5.3.04", "5.4.00"):
            required = MATRIX.required_aliases(constants, requirements, [], version, capability, [])
            self.assertIn("color", required)
            self.assertTrue(MATRIX.MODEL_BINDING <= required)
            self.assertEqual(version not in {"5.2.02", "5.2.03"}, "alpha" in required)

    def test_repeated_manifest_wiring_does_not_accumulate_blank_lines(self):
        import cubism_matrix.constants as constants_module
        wire_module = sys.modules["cubism_matrix.wire"]
        original = wire_module.TEMPLATE_DIR / "EditorModelVerificationManifest.java"
        with tempfile.TemporaryDirectory() as directory:
            target = Path(directory) / original.name
            target.write_bytes(original.read_bytes())
            with patch.object(wire_module, "TEMPLATE_DIR", Path(directory)), patch.object(constants_module, "TEMPLATE_DIR", Path(directory)), patch.object(MATRIX, "TEMPLATE_DIR", Path(directory)):
                MATRIX.wire_editor_model(list(MATRIX.TARGET_VERSIONS))
                first = target.read_bytes()
                MATRIX.wire_editor_model(list(MATRIX.TARGET_VERSIONS))
                self.assertEqual(first, target.read_bytes())


if __name__ == "__main__":
    unittest.main()
