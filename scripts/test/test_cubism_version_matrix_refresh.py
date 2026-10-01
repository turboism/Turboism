"""Regression coverage for repeatable additive version-matrix wiring."""
from __future__ import annotations

import importlib.util
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location(
    "cubism_version_matrix", ROOT / "scripts/dev/cubism_version_matrix.py"
)
MATRIX = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
with tempfile.TemporaryDirectory() as reference_directory:
    # Wiring uses committed records only; no proprietary host JAR is needed.
    with patch.dict(os.environ, {"TURBOISM_CUBISM_REF": reference_directory}):
        SPEC.loader.exec_module(MATRIX)


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
        }
        requirements = {capability: {"color", "alpha"}}
        for version in ("5.2.02", "5.2.03", "5.3.04", "5.4.00"):
            required = MATRIX.required_aliases(constants, requirements, [], version, capability, [])
            self.assertIn("color", required)
            self.assertTrue(MATRIX.MODEL_BINDING <= required)
            self.assertEqual(version not in {"5.2.02", "5.2.03"}, "alpha" in required)

    def test_repeated_manifest_wiring_does_not_accumulate_blank_lines(self):
        original = MATRIX.TEMPLATE_DIR / "EditorModelVerificationManifest.java"
        with tempfile.TemporaryDirectory() as directory:
            target = Path(directory) / original.name
            target.write_bytes(original.read_bytes())
            with patch.object(MATRIX, "TEMPLATE_DIR", Path(directory)):
                MATRIX.wire_editor_model(list(MATRIX.TARGET_VERSIONS))
                first = target.read_bytes()
                MATRIX.wire_editor_model(list(MATRIX.TARGET_VERSIONS))
                self.assertEqual(first, target.read_bytes())


if __name__ == "__main__":
    unittest.main()
