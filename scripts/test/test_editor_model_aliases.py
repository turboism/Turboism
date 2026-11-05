#!/usr/bin/env python3
"""Regression tests for the fail-closed host alias vocabulary check.

The suite keeps the Editor-model exact-admission cases the script has always enforced and adds the
family-wide matrix: a typo in any reviewed family, an alias deleted from a record, the no-record
ratchet in both directions, and the data-shape failures that keep the declaration side honest.
"""
from __future__ import annotations

import importlib.util
import json
import sys
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "scripts/test/check_editor_model_aliases.py"
SPEC = importlib.util.spec_from_file_location("check_editor_model_aliases", SCRIPT)
CHECKER = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
sys.modules[SPEC.name] = CHECKER
SPEC.loader.exec_module(CHECKER)

CLIPMASK_RECORD = "cubism-5.0.00-clipmask.json"
CLIPMASK_ALIAS = "cubism.clipmask.app-controller.instance"
CLIPMASK_CLASS_ALIAS = "cubism.clipmask.doc-model.class"
CLIPMASK_CAPABILITY = "cubism.clipmask.read"
SCENE_ALIAS = "cubism.scene-palette.controller.table"
LOCAL_LITERAL = "cubism.clipmask.write"
EDITOR_MODEL_ALIAS = "cubism.editor-model.parameter.value"
EDITOR_MODEL_CLASS_ALIAS = "cubism.editor-model.parameter.class"
ADAPTER = "runtime/src/main/java/dev/turboism/adapter/ClipMaskAdapter.java"
SCENE_ADAPTER = "runtime/src/main/java/dev/turboism/ui/ScenePaletteAdapter.java"
MANIFEST = "runtime/src/main/java/dev/turboism/mapping/verification/ClipMaskVerificationManifest.java"


def selector(alias: str, kind: str = "method") -> dict:
    """Return one minimal reviewed selector row."""
    return {
        "alias": alias,
        "ownerInternalName": "com/live2d/cubism/doc/modeling/CModel",
        "memberName": alias.rsplit(".", 1)[-1].replace("-", "_"),
        "descriptor": "()V",
        "kind": kind,
        "status": "VERIFIED_STATIC",
    }


def family_document(name: str, records: list[str], strict: bool = False) -> dict:
    """Return one family authoring document with a vocabulary declaration."""
    return {
        "format": "turboism.verification-source-family/v1",
        "family": name,
        "records": records,
        "files": [],
        "coverage": False,
        "packs": [],
        "extraLiterals": [],
        "coverageSources": [],
        "vocabulary": {"admissionEqualsUse": strict},
    }


def write(root: Path, relative: str, text: str) -> Path:
    """Write one fixture file, creating parent directories."""
    path = root / relative
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8")
    return path


def write_json(root: Path, relative: str, document: dict) -> Path:
    """Write one fixture JSON document."""
    return write(root, relative, json.dumps(document, indent=1) + "\n")


def record_path(name: str) -> str:
    """Return the repository-relative path of a materialized record."""
    return f"{CHECKER.RECORD_ROOT}/{name}"


def adapter_body(extra: str = "") -> str:
    """Return a production source that invokes reviewed and pinned aliases."""
    additive = "\n".join(
        f'        resolver.invokeStatic("{alias}");' for alias in sorted(CHECKER.ADDITIVE_ALIASES))
    lines = [
        "final class ClipMaskAdapter {",
        "    private final VerifiedMemberResolver resolver;",
        "",
        "    ClipMaskAdapter(final VerifiedMemberResolver resolver) {",
        "        this.resolver = resolver;",
        "    }",
        "",
        "    void bind() {",
        f'        resolver.invoke("{CLIPMASK_ALIAS}", null);',
        f'        resolver.verifiedSelector("{EDITOR_MODEL_ALIAS}");',
        f'        permissions.require(WRITE_PERMISSION, "{LOCAL_LITERAL}");',
        additive,
        extra,
        "    }",
        "}",
        "",
    ]
    return "\n".join(line for line in lines if line)


def fixture(root: Path) -> Path:
    """Write a minimal repository tree the checker can admit without a violation."""
    editor_model_base = set(CHECKER.REVIEWED_UNCONSUMED_ALIASES) | {EDITOR_MODEL_ALIAS}
    write_json(root, "scripts/verification-sources/families/clipmask.json",
               family_document("clipmask", [CLIPMASK_RECORD]))
    write_json(root, "scripts/verification-sources/families/editor-model.json",
               family_document(
                   "editor-model",
                   [Path(name).name for name in CHECKER.BASE_RECORDS],
                   strict=True))
    write_json(root, "scripts/verification-sources/no-record-aliases.json", {
        "format": CHECKER.NO_RECORD_FORMAT,
        "namespaces": ["cubism.scene-palette."],
        "aliases": [{"alias": SCENE_ALIAS, "file": SCENE_ADAPTER, "reason": "self-verified profile"}],
        "localVocabulary": [{"literal": LOCAL_LITERAL, "file": ADAPTER, "reason": "capability id"}],
    })
    write_json(root, record_path(CLIPMASK_RECORD), {
        "capabilityIds": [CLIPMASK_CAPABILITY],
        "selectors": [
            selector(CLIPMASK_ALIAS),
            selector(CLIPMASK_CLASS_ALIAS, kind="class"),
        ],
    })
    for name in CHECKER.BASE_RECORDS:
        write_json(root, record_path(Path(name).name), {
            "capabilityIds": [],
            "selectors": [selector(alias) for alias in sorted(editor_model_base)]
                                        + [selector(EDITOR_MODEL_CLASS_ALIAS, kind="class")],
        })
    write_json(root, record_path(Path(CHECKER.ADDITIVE_RECORD).name), {
        "capabilityIds": [],
        "selectors": [selector(alias) for alias in sorted(CHECKER.ADDITIVE_ALIASES)],
    })
    write(root, ADAPTER, adapter_body())
    write(root, SCENE_ADAPTER, "\n".join([
        "final class ScenePaletteAdapter {",
        "    void bind(final VerifiedMemberResolver resolver) {",
        f'        resolver.verifiedSelector("{SCENE_ALIAS}");',
        "    }",
        "}",
        "",
    ]))
    write(root, MANIFEST, "\n".join([
        "public final class ClipMaskVerificationManifest {",
        '    public static final String UNUSED_ALIAS = "cubism.clipmask.declared-only.member";',
        "}",
        "",
    ]))
    return root


def failures(root: Path) -> str:
    """Return the checker's failure report for one tree."""
    messages, _ = CHECKER.check(root)
    return "\n".join(messages)


class EditorModelAliasesTest(unittest.TestCase):
    """The Editor-model admission cases this check has always owned."""

    def test_public_5303_record_carries_exact_additive_subset(self):
        aliases = CHECKER.aliases_in_record(ROOT, CHECKER.ADDITIVE_RECORD)
        self.assertTrue(CHECKER.ADDITIVE_ALIASES <= aliases)
        union, per_record = CHECKER.record_aliases(ROOT)
        self.assertTrue(CHECKER.ADDITIVE_ALIASES <= union)
        self.assertEqual(
            set(CHECKER.ADDITIVE_ALIASES),
            per_record[Path(CHECKER.ADDITIVE_RECORD).name + " (admitted subset)"],
        )

    def test_broader_5303_inventory_does_not_widen_runtime_admission(self):
        # The merged 5.2.03/5.3.02 records now cover every reviewed 5.3.03
        # editor-model alias, so the additive subset no longer extends the union.
        # Guard the property that keeps that safe: a 5.3.03-only selector that is
        # not in the pinned additive subset must never enter runtime admission.
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for relative in (*CHECKER.BASE_RECORDS, CHECKER.ADDITIVE_RECORD):
                source = ROOT / relative
                target = root / relative
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(source.read_bytes())
            path = root / CHECKER.ADDITIVE_RECORD
            document = json.loads(path.read_text(encoding="utf-8"))
            document["selectors"].append({
                "alias": "cubism.editor-model.synthetic.inventory-only",
                "ownerInternalName": "synthetic/Owner",
                "memberName": "synthetic",
                "descriptor": "()V",
                "kind": "method",
                "status": "VERIFIED_STATIC",
            })
            path.write_text(json.dumps(document), encoding="utf-8")

            union, _ = CHECKER.record_aliases(root)
            self.assertNotIn("cubism.editor-model.synthetic.inventory-only", union)

    def test_contract_alias_constant_reference_is_followed(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            contract = root / "runtime/src/main/java/dev/turboism/mapping/verification/selector"
            contract.mkdir(parents=True)
            (contract / "SampleContract.java").write_text(
                "public final class SampleContract {\n"
                "    public static final String SAMPLE_ALIAS =\n"
                "        \"cubism.editor-model.sample.member\";\n"
                "    private SampleContract() { }\n"
                "}\n", encoding="utf-8")
            production = root / "runtime/src/main/java/dev/turboism/adapter"
            production.mkdir(parents=True)
            (production / "Sample.java").write_text(
                "final class Sample {\n"
                "    void run(dev.turboism.mapping.VerifiedMemberResolver resolver, Object target) {\n"
                "        resolver.invoke(dev.turboism.mapping.verification.selector.SampleContract.SAMPLE_ALIAS, target);\n"
                "    }\n"
                "}\n", encoding="utf-8")
            self.assertEqual(
                set(CHECKER.implementation_aliases(root)),
                {"cubism.editor-model.sample.member"})

    def test_unresolvable_or_non_alias_constant_reference_is_ignored(self):
        # A class-kind alias stored in a contract constant is a real host alias: the
        # `.class` member segment no longer hides it from the implementation side, but
        # an unrelated constant is still not followed and a missing one is ignored.
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            contract = root / "runtime/src/main/java/dev/turboism/mapping/verification/selector"
            contract.mkdir(parents=True)
            (contract / "SampleContract.java").write_text(
                "public final class SampleContract {\n"
                "    public static final String CLASS_ALIAS = \"cubism.editor-model.sample.class\";\n"
                "    public static final String UNRELATED = \"cubism.editor-model.other\";\n"
                "}\n", encoding="utf-8")
            production = root / "runtime/src/main/java/dev/turboism/adapter"
            production.mkdir(parents=True)
            (production / "Sample.java").write_text(
                "final class Sample {\n"
                "    void run(dev.turboism.mapping.VerifiedMemberResolver resolver, Object target) {\n"
                "        resolver.invoke(dev.turboism.mapping.verification.selector.SampleContract.CLASS_ALIAS, target);\n"
                "        resolver.invoke(dev.turboism.mapping.verification.selector.SampleContract.MISSING_ALIAS, target);\n"
                "        resolver.invoke(dev.turboism.mapping.verification.selector.SampleContract.UNRELATED, target);\n"
                "    }\n"
                "}\n", encoding="utf-8")
            self.assertEqual(
                set(CHECKER.implementation_aliases(root)),
                {"cubism.editor-model.sample.class"})

    def test_missing_additive_selector_fails_closed(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for relative in (*CHECKER.BASE_RECORDS, CHECKER.ADDITIVE_RECORD):
                source = ROOT / relative
                target = root / relative
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(source.read_bytes())
            missing = next(iter(CHECKER.ADDITIVE_ALIASES))
            path = root / CHECKER.ADDITIVE_RECORD
            document = json.loads(path.read_text(encoding="utf-8"))
            document["selectors"] = [
                selector for selector in document["selectors"]
                if selector.get("alias") != missing
            ]
            path.write_text(json.dumps(document), encoding="utf-8")

            with self.assertRaisesRegex(ValueError, "lacks required additive aliases"):
                CHECKER.record_aliases(root)


class HostAliasVocabularyTest(unittest.TestCase):
    """The family-wide existence invariant, the no-record ratchet and the declaration data."""

    def setUp(self) -> None:
        self._directory = tempfile.TemporaryDirectory()
        self.root = fixture(Path(self._directory.name))

    def tearDown(self) -> None:
        self._directory.cleanup()

    def test_fixture_passes(self):
        self.assertEqual(failures(self.root), "")

    def test_repository_vocabulary_passes(self):
        self.assertEqual(failures(ROOT), "")
        families = CHECKER.family_vocabulary(ROOT)
        self.assertIn(CHECKER.ADMISSION_FAMILY, families)
        self.assertNotIn("compatibility-catalog", families)
        self.assertNotIn("validation-lanes", families)
        self.assertGreater(len(CHECKER.no_record_vocabulary(ROOT).aliases), 0)

    def test_typo_in_non_editor_model_family_fails_closed(self):
        write(self.root, ADAPTER, adapter_body(
            '        resolver.invoke("cubism.clipmask.app-controller.instnace", null);'))
        report = failures(self.root)
        self.assertIn("cubism.clipmask.app-controller.instnace", report)
        self.assertIn("lack reviewed evidence", report)

    def test_alias_removed_from_record_fails_closed(self):
        path = self.root / record_path(CLIPMASK_RECORD)
        document = json.loads(path.read_text(encoding="utf-8"))
        document["selectors"] = [
            selector for selector in document["selectors"]
            if selector["alias"] != CLIPMASK_ALIAS]
        path.write_text(json.dumps(document), encoding="utf-8")
        report = failures(self.root)
        self.assertIn(CLIPMASK_ALIAS, report)
        self.assertIn("lack reviewed evidence", report)

    def test_class_kind_alias_is_admitted_from_any_namespace(self):
        # The class alias lives in the editor-model records but in the clipmask namespace,
        # so a per-namespace reading of the union would drop it; the family union must not.
        write(self.root, ADAPTER, adapter_body(
            f'        resolver.verifiedSelector("{CLIPMASK_CLASS_ALIAS}");'))
        self.assertEqual(failures(self.root), "")

    def test_new_unrecorded_alias_fails_the_ratchet(self):
        write(self.root, SCENE_ADAPTER, "\n".join([
            "final class ScenePaletteAdapter {",
            "    void bind(final VerifiedMemberResolver resolver) {",
            f'        resolver.verifiedSelector("{SCENE_ALIAS}");',
            '        resolver.verifiedSelector("cubism.scene-palette.controller.unpinned");',
            "    }",
            "}",
            "",
        ]))
        report = failures(self.root)
        self.assertIn("cubism.scene-palette.controller.unpinned", report)
        self.assertIn("not pinned", report)

    def test_stale_pinned_alias_fails_the_ratchet(self):
        write(self.root, SCENE_ADAPTER, "final class ScenePaletteAdapter {\n}\n")
        report = failures(self.root)
        self.assertIn(SCENE_ALIAS, report)
        self.assertIn("no longer used", report)

    def test_pinned_alias_file_drift_fails(self):
        path = self.root / "scripts/verification-sources/no-record-aliases.json"
        document = json.loads(path.read_text(encoding="utf-8"))
        document["aliases"][0]["file"] = ADAPTER
        path.write_text(json.dumps(document), encoding="utf-8")
        report = failures(self.root)
        self.assertIn(SCENE_ALIAS, report)
        self.assertIn("moved away from their pinned file", report)

    def test_pinned_alias_that_became_recorded_must_be_unpinned(self):
        path = self.root / record_path(CLIPMASK_RECORD)
        document = json.loads(path.read_text(encoding="utf-8"))
        document["selectors"].append(selector(SCENE_ALIAS))
        path.write_text(json.dumps(document), encoding="utf-8")
        report = failures(self.root)
        self.assertIn(SCENE_ALIAS, report)
        self.assertIn("now declared by a reviewed record", report)

    def test_stale_local_vocabulary_fails(self):
        write(self.root, ADAPTER, adapter_body().replace(
            f'        permissions.require(WRITE_PERMISSION, "{LOCAL_LITERAL}");\n', ""))
        report = failures(self.root)
        self.assertIn(LOCAL_LITERAL, report)
        self.assertIn("local-vocabulary literals are no longer used", report)

    def test_alias_in_resolver_position_outside_namespaces_fails(self):
        # A mistyped namespace segment leaves every reviewed namespace, so only the
        # resolver alias position can still catch it.
        write(self.root, ADAPTER, adapter_body(
            '        resolver.invokeStatic("cubism.clipmaskk.app-controller.instance");'))
        report = failures(self.root)
        self.assertIn("cubism.clipmaskk.app-controller.instance", report)

    def test_prefix_template_is_not_an_alias_literal(self):
        self.assertFalse(CHECKER.is_alias_literal("cubism.editor-model.parameter.", {
            "cubism.editor-model."}))
        self.assertFalse(CHECKER.is_alias_literal("cubism.clipmask.other.member", {
            "cubism.editor-model."}))
        self.assertTrue(CHECKER.is_alias_literal(CLIPMASK_ALIAS, {"cubism.clipmask."}))

    def test_manifest_evidence_does_not_count_as_implementation(self):
        # A contract that merely restates a record alias must never satisfy the
        # admission direction on its own.
        write(self.root, SCENE_ADAPTER, "final class ScenePaletteAdapter {\n}\n")
        self.assertNotIn(
            "cubism.clipmask.declared-only.member",
            set(CHECKER.implementation_aliases(self.root)))

    def test_vocabulary_family_with_missing_record_fails_closed(self):
        write_json(self.root, "scripts/verification-sources/families/clipmask.json",
                   family_document("clipmask", ["cubism-9.9.99-clipmask.json"]))
        with self.assertRaisesRegex(ValueError, "binds missing record"):
            CHECKER.family_vocabulary(self.root)

    def test_admission_equals_use_is_implemented_only_for_editor_model(self):
        write_json(self.root, "scripts/verification-sources/families/clipmask.json",
                   family_document("clipmask", [CLIPMASK_RECORD], strict=True))
        with self.assertRaisesRegex(ValueError, "admissionEqualsUse is only implemented"):
            CHECKER.family_vocabulary(self.root)

    def test_editor_model_must_keep_its_admission_policy(self):
        write_json(self.root, "scripts/verification-sources/families/editor-model.json",
                   family_document(
                       "editor-model",
                       [Path(name).name for name in CHECKER.BASE_RECORDS]))
        with self.assertRaisesRegex(ValueError, "must declare vocabulary.admissionEqualsUse"):
            CHECKER.family_vocabulary(self.root)

    def test_emptying_the_pin_list_is_not_a_bypass(self):
        path = self.root / "scripts/verification-sources/no-record-aliases.json"
        document = json.loads(path.read_text(encoding="utf-8"))
        document["aliases"] = []
        path.write_text(json.dumps(document), encoding="utf-8")
        report = failures(self.root)
        self.assertIn(SCENE_ALIAS, report)
        self.assertIn("lack reviewed evidence", report)

    def test_pin_without_a_reason_fails_closed(self):
        path = self.root / "scripts/verification-sources/no-record-aliases.json"
        document = json.loads(path.read_text(encoding="utf-8"))
        del document["aliases"][0]["reason"]
        path.write_text(json.dumps(document), encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "needs a non-empty 'reason'"):
            CHECKER.no_record_vocabulary(self.root)

    def test_unpinned_unrecorded_alias_cannot_hide_in_a_family_namespace(self):
        # A literal in a reviewed namespace that no record and no pin covers fails even
        # when the family itself is healthy, so a new typo never needs a new namespace.
        write(self.root, ADAPTER, adapter_body(
            f'        resolver.invoke("{CLIPMASK_CAPABILITY}.extra", null);'))
        report = failures(self.root)
        self.assertIn(f"{CLIPMASK_CAPABILITY}.extra", report)


if __name__ == "__main__":
    unittest.main(verbosity=2)
