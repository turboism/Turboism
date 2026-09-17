#!/usr/bin/env python3
"""Fail-closed fixtures for generated verification source equivalence."""
from __future__ import annotations

import hashlib
import importlib.util
import json
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "scripts/generate_verification_sources.py"
SPEC = importlib.util.spec_from_file_location("generate_verification_sources", SCRIPT)
GEN = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(GEN)

MANIFEST_RELPATH = "dev/turboism/mapping/verification/FixtureVerificationManifest.java"
RECORD_NAME = "cubism-9.9.09-fixture.json"
ALIASES = ("cubism.fixture.alpha", "cubism.fixture.beta")


def record() -> dict:
    return {
        "format": "turboism.static-verification-record",
        "verificationId": "cubism-9.9.09.fixture.static",
        "cubismVersion": "9.9.09",
        "profileId": "cubism-9.9.09",
        "adapterSliceId": "adapter.fixture",
        "capabilityIds": ["cubism.fixture.read"],
        "artifact": {"name": "Live2D_Cubism.jar", "size": 1, "sha256": "ab" * 32},
        "selectors": [
            {"mappingId": "m.%d" % index, "alias": alias, "kind": "method",
             "status": "VERIFIED_STATIC"}
            for index, alias in enumerate(ALIASES)
        ],
    }


def record_sha(document: dict) -> str:
    return hashlib.sha256(json.dumps(document).encode()).hexdigest()


def manifest_source(document: dict) -> str:
    return f'''package dev.turboism.mapping.verification;

import java.util.Set;

/** Fixture trust root. */
public final class FixtureVerificationManifest {{
    public static final String VERIFICATION_ID_99 =
        "{document["verificationId"]}";
    public static final String RECORD_SHA256_99 = "{record_sha(document)}";
    public static final Set<String> REQUIRED_ALIASES = Set.of(
        "{ALIASES[0]}",
        "{ALIASES[1]}"
    );

    private FixtureVerificationManifest() {{
    }}
}}
'''


def manifest_template(document: dict) -> str:
    return manifest_source(document).replace(
        f'"{document["verificationId"]}"',
        f'"${{record:{RECORD_NAME}:verificationId}}"',
    ).replace(
        f'"{record_sha(document)}"',
        f'"${{record:{RECORD_NAME}:sha256}}"',
    )


def write_tree(
    root: Path,
    document: dict,
    template: str | None,
    source: str | None,
    *,
    family_files: list[str] | None = None,
    coverage: bool = False,
    extra_families: dict[str, dict] | None = None,
) -> None:
    verification = root / GEN.VERIFICATION_DIR
    verification.mkdir(parents=True)
    (verification / RECORD_NAME).write_text(json.dumps(document))
    families = root / GEN.FAMILY_DIR
    families.mkdir(parents=True)
    spec = {
        "format": GEN.FAMILY_FORMAT,
        "family": "fixture",
        "records": [RECORD_NAME],
        "files": family_files if family_files is not None else [MANIFEST_RELPATH],
        "coverage": coverage,
    }
    (families / "fixture.json").write_text(json.dumps(spec))
    for name, extra in (extra_families or {}).items():
        (families / name).write_text(json.dumps(extra))
    if template is not None:
        target = root / GEN.TEMPLATE_ROOT / MANIFEST_RELPATH
        target.parent.mkdir(parents=True)
        target.write_text(template)
    if source is not None:
        checked = root / GEN.SOURCE_ROOT / MANIFEST_RELPATH
        checked.parent.mkdir(parents=True)
        checked.write_text(source)


class GenerateVerificationSourcesTest(unittest.TestCase):
    def assert_clean(self, root: Path, equivalent: int | None = None):
        violations, count = GEN.check(root)
        self.assertEqual([], violations)
        if equivalent is not None:
            self.assertEqual(equivalent, count)

    def test_live_tree_is_fully_generated(self):
        violations, equivalent = GEN.check(ROOT, expect="generated")
        self.assertEqual([], violations)
        self.assertEqual(0, equivalent)
        rendered = GEN.render_all(ROOT)
        self.assertEqual(65, len(rendered))

    def test_consistent_fixture_passes(self):
        document = record()
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            write_tree(
                root, document,
                manifest_template(document), manifest_source(document),
            )
            self.assert_clean(root, equivalent=1)

    def test_tampered_checked_in_source_fails(self):
        document = record()
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            write_tree(
                root, document,
                manifest_template(document),
                manifest_source(document).replace("alpha", "renamed"),
            )
            found, _ = GEN.check(root)
            self.assertTrue(any("differs from the checked-in" in v for v in found),
                            found)

    def test_tampered_record_bytes_fail(self):
        document = record()
        tampered = record()
        tampered["capabilityIds"] = ["cubism.fixture.read", "cubism.fixture.write"]
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            write_tree(
                root, tampered,
                manifest_template(document), manifest_source(document),
            )
            found, _ = GEN.check(root)
            self.assertTrue(any("differs from the checked-in" in v for v in found),
                            found)

    def test_alias_outside_record_universe_fails(self):
        document = record()
        source = manifest_source(document).replace(
            '"cubism.fixture.alpha"', '"cubism.fixture.rogue"'
        )
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            write_tree(root, document, source, source)
            found, _ = GEN.check(root)
            self.assertTrue(any("claim evidence" in v for v in found), found)

    def test_coverage_gap_fails(self):
        document = record()
        source = manifest_source(document).replace(
            f'        "{ALIASES[1]}"\n', ""
        )
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            write_tree(root, document, source, source, coverage=True)
            found, _ = GEN.check(root)
            self.assertTrue(any("absent from generated sources" in v for v in found),
                            found)

    def test_placeholder_for_undeclared_record_fails(self):
        document = record()
        template = manifest_template(document).replace(
            RECORD_NAME, "cubism-9.9.09-other.json"
        )
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            write_tree(root, document, template, manifest_source(document))
            found, _ = GEN.check(root)
            self.assertTrue(any("not a declared family record" in v for v in found),
                            found)

    def test_unknown_placeholder_field_fails(self):
        document = record()
        template = manifest_template(document).replace(
            "verificationId}", "verificationStatus}"
        )
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            write_tree(root, document, template, manifest_source(document))
            found, _ = GEN.check(root)
            self.assertTrue(any("unsupported record field" in v for v in found),
                            found)

    def test_family_declaring_non_target_fails(self):
        document = record()
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            write_tree(
                root, document, None, None,
                family_files=["dev/turboism/mapping/verification/StaticSelector.java"],
            )
            found, _ = GEN.check(root)
            self.assertTrue(any("not a generated target" in v for v in found), found)

    def test_duplicate_file_claim_fails(self):
        document = record()
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            write_tree(
                root, document, manifest_template(document),
                manifest_source(document),
                extra_families={
                    "duplicate.json": {
                        "format": GEN.FAMILY_FORMAT,
                        "family": "duplicate",
                        "files": [MANIFEST_RELPATH],
                    }
                },
            )
            found, _ = GEN.check(root)
            self.assertTrue(any("claimed by both" in v for v in found), found)

    def test_missing_template_fails(self):
        document = record()
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            write_tree(root, document, None, manifest_source(document))
            found, _ = GEN.check(root)
            self.assertTrue(any("no authoring template" in v for v in found), found)

    def test_unauthored_checked_in_target_fails(self):
        document = record()
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            write_tree(root, document, manifest_template(document), None)
            stray = (
                root / GEN.SOURCE_ROOT
                / "dev/turboism/mapping/verification/StrayVerificationManifest.java"
            )
            stray.parent.mkdir(parents=True)
            stray.write_text("final class StrayVerificationManifest {}\n")
            found, _ = GEN.check(root)
            self.assertTrue(any("no authoring family" in v for v in found), found)

    def test_mixed_migration_state_fails(self):
        document = record()
        second = "dev/turboism/mapping/verification/selector/FixtureSelectorContract.java"
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            write_tree(
                root, document, manifest_template(document),
                manifest_source(document),
                family_files=[MANIFEST_RELPATH, second],
            )
            template = root / GEN.TEMPLATE_ROOT / second
            template.parent.mkdir(parents=True)
            template.write_text(
                "package dev.turboism.mapping.verification.selector;\n"
            )
            found, _ = GEN.check(root)
            self.assertTrue(any("mixed migration state" in v for v in found), found)

    def test_expect_generated_rejects_reappearance(self):
        document = record()
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            write_tree(
                root, document, manifest_template(document),
                manifest_source(document),
            )
            found, _ = GEN.check(root, expect="generated")
            self.assertTrue(any("reappeared" in v for v in found), found)
            found, _ = GEN.check(root, expect="checked-in")
            self.assertEqual([], found)

    def test_expect_checked_in_rejects_missing_source(self):
        document = record()
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            write_tree(root, document, manifest_template(document), None)
            found, _ = GEN.check(root, expect="checked-in")
            self.assertTrue(any("not checked in" in v for v in found), found)
            found, _ = GEN.check(root, expect="generated")
            self.assertEqual([], found)

    def test_render_writes_and_sweeps(self):
        document = record()
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            write_tree(
                root, document, manifest_template(document),
                manifest_source(document),
            )
            rendered = GEN.render_all(root)
            self.assertEqual(
                manifest_source(document), rendered[MANIFEST_RELPATH]
            )

    def test_extract_round_trip(self):
        document = record()
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            write_tree(
                root, document, None, manifest_source(document),
            )
            self.assertEqual(0, GEN.extract(root))
            template = (
                root / GEN.TEMPLATE_ROOT / MANIFEST_RELPATH
            ).read_text()
            self.assertIn("${record:%s:sha256}" % RECORD_NAME, template)
            self.assert_clean(root, equivalent=1)


if __name__ == "__main__":
    unittest.main(verbosity=2)
