"""Trust-root, record-index, range-consumer and host-task coverage regressions."""
from __future__ import annotations

import copy
import json
from pathlib import Path
import tempfile
import unittest
from generate_reviewed_releases import ENV, HOST, TABLE, TEMPLATE, check, render
from preview.reviewed_host_artifacts import SOURCE_RELATIVE, compact, load, load_releases
from scripts.test.reviewed_releases_fixture import table
from unittest.mock import patch
from scripts import check_reviewed_releases as gate
from compat_matrix.extract import load_commands, load_contract_gates
from turboism_dev.versions import reviewed_range_versions
from scripts import generate_compatibility_matrix as matrix_generator

ROOT = Path(__file__).resolve().parents[2]


class ReviewedReleasesTest(unittest.TestCase):
    def test_malformed_trust_roots_fail_closed(self):
        original = json.loads(table(['5.3.03', '5.3.04']))
        mutations = [
            lambda d: d['releases'].reverse(),
            lambda d: d['releases'].append(copy.deepcopy(d['releases'][0])),
            lambda d: d['releases'][0].update(build=True),
            lambda d: d['releases'][0].update(fullRuntime='true'),
            lambda d: d['releases'][0]['artifact'].update(size=0),
            lambda d: d['releases'][0]['artifact'].update(sha256='unknown'),
            lambda d: d['releases'][0]['core'].update(patch=-1),
            lambda d: d['releases'][0].update(hostLabel='../host'),
            lambda d: d['releases'][0].update(unreviewed=True),
        ]
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / 'reviewed-releases.json'
            for mutate in mutations:
                data = copy.deepcopy(original)
                mutate(data)
                path.write_text(json.dumps(data))
                with self.subTest(mutation=mutate), self.assertRaises(ValueError):
                    load_releases(path)
            path.write_text('{"format": 1, "format": 2}')
            with self.assertRaisesRegex(ValueError, 'duplicate JSON key'):
                load_releases(path)

    def test_new_version_and_explicit_full_runtime_remain_separate(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            path = root / SOURCE_RELATIVE
            path.parent.mkdir(parents=True)
            data = json.loads(table(['5.3.04', '5.3.05']))
            data['releases'][1]['fullRuntime'] = False
            path.write_text(json.dumps(data))
            for relative in (TEMPLATE, ENV):
                dest = root / relative
                dest.parent.mkdir(parents=True, exist_ok=True)
                dest.write_bytes((ROOT / relative).read_bytes())
            outputs = render(root)
            self.assertIn('CUBISM_5_3_05_VERSION = "5.3.05"', outputs[HOST])
            self.assertIn('new Release("5.3.05", 2, 5, 0, 0, false)', outputs[TABLE])
            self.assertEqual(['5304', '5305'], [compact(v) for v in load(path)])
            self.assertIn('FIXTURE_5305=', outputs[ENV])
            for relative, text in outputs.items():
                (root / relative).parent.mkdir(parents=True, exist_ok=True)
                (root / relative).write_text(text, encoding="utf-8", newline="\n")
            self.assertEqual([], check(root))
            (root / HOST).write_text(outputs[HOST].replace('5.3.05', '5.3.99'))
            self.assertTrue(any('ReviewedHostArtifacts' in problem for problem in check(root)))
            path.unlink()
            with self.assertRaises(ValueError):
                load(path)


MANIFEST = """
final class FixtureVerificationManifest {
    static final String PIN = "cubism-9.9.09.fixture.static";
}
"""


RECORD_NAMES = (
    "cubism-9.9.09-fixture.json",
    "cubism-9.9.09-extra.json",
)


def record(version_id: str) -> dict:
    return {
        "format": "turboism.static-verification-record",
        "verificationId": version_id,
        "selectors": [],
        "capabilityIds": [],
    }


def write_tree(root: Path, records: dict[str, dict], listed: list[str]) -> None:
    verification = root / gate.index_VERIFICATION_DIR
    verification.mkdir(parents=True)
    for name, document in records.items():
        (verification / name).write_text(json.dumps(document) + "\n")
    # Expected expansion is independent from physical files in the fixture.
    (root / "expected.txt").write_text("\n".join(listed))
    manifest = root / gate.index_MANIFEST_ROOT
    manifest.mkdir(parents=True)
    (manifest / "FixtureVerificationManifest.java").write_text(MANIFEST)


def base_records() -> dict[str, dict]:
    return {
        "cubism-9.9.09-fixture.json": record("cubism-9.9.09.fixture.static"),
        "cubism-9.9.09-extra.json": record("cubism-9.9.09.extra.static"),
    }


class VerificationRecordIndexTest(unittest.TestCase):
    def setUp(self):
        original = gate.render_records
        def render(root):
            if root == ROOT:
                return original(root)
            names = (root / "expected.txt").read_text().splitlines()
            return {name: (root / gate.index_VERIFICATION_DIR / name).read_bytes()
                    if (root / gate.index_VERIFICATION_DIR / name).exists() else b"{}" for name in names}
        self.patch = patch.object(gate, "render_records", side_effect=render)
        self.patch.start()
        self.addCleanup(self.patch.stop)

    def test_live_tree_is_consistent(self):
        self.assertEqual([], gate.index_check(ROOT))

    def test_consistent_fixture_passes(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            write_tree(root, base_records(), list(RECORD_NAMES))
            self.assertEqual([], gate.index_check(root))

    def test_extra_materialized_record_fails(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            write_tree(root, base_records(), [RECORD_NAMES[0]])
            found = gate.index_check(root)
            self.assertTrue(any("file set differs" in v for v in found), found)

    def test_missing_materialized_record_fails(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            write_tree(root, base_records(),
                       [*RECORD_NAMES, "cubism-9.9.09-ghost.json"])
            found = gate.index_check(root)
            self.assertTrue(any("file set differs" in v for v in found), found)

    def test_modified_materialized_bytes_fail(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            write_tree(root, base_records(), list(RECORD_NAMES))
            with patch.object(gate, "render_records", return_value={name: b"{}" for name in RECORD_NAMES}):
                self.assertTrue(any("bytes differ" in v for v in gate.index_check(root)))

    def test_manifest_pin_without_record_fails(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            records = base_records()
            records["cubism-9.9.09-fixture.json"] = record("cubism-9.9.09.renamed.static")
            write_tree(root, records, list(RECORD_NAMES))
            found = gate.index_check(root)
            self.assertTrue(any("no\nmatching record" in v or "no matching record" in v
                                for v in found), found)

    def test_duplicate_verification_id_fails(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            records = base_records()
            records["cubism-9.9.09-extra.json"] = record("cubism-9.9.09.fixture.static")
            write_tree(root, records, list(RECORD_NAMES))
            with self.assertRaises(gate.index_IndexError_):
                gate.index_check(root)

    def test_filename_version_mismatch_fails(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            records = base_records()
            records["cubism-9.9.09-extra.json"] = record("cubism-9.8.07.extra.static")
            write_tree(root, records, list(RECORD_NAMES))
            with self.assertRaises(gate.index_IndexError_):
                gate.index_check(root)

    def test_render_lists_directory(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            write_tree(root, base_records(), list(RECORD_NAMES))
            self.assertEqual(sorted(RECORD_NAMES), gate.index_on_disk_records(root))

    def test_live_manifest_pins_are_non_vacuous(self):
        ids = gate.index_manifest_verification_ids(ROOT)
        self.assertGreaterEqual(len(ids), 30)
        self.assertIn("cubism-5.3.03.editor-model.static", ids)


class RangeConsumerTest(unittest.TestCase):
    def test_literal_and_range_consumers_have_identical_coverage(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            releases = root / "compatibility/cubism/reviewed-releases.json"
            releases.parent.mkdir(parents=True)
            versions = ["5.2.03", "5.3.02", "5.3.03", "5.3.04"]
            releases.write_text(table(versions))
            command = root / "sdk/src/main/java/dev/turboism/sdk/cubism/command/EditorCommand.java"
            command.parent.mkdir(parents=True)
            contract = root / "runtime/src/main/java/Contract.java"
            contract.parent.mkdir(parents=True)
            declarations = []
            for selection in ('Set.of("5.3.02", "5.3.03", "5.3.04")',
                              'ReviewedVersions.since("5.3.02")'):
                command.write_text("enum EditorCommand {\n    OPEN(" + selection + ");\n}")
                contract.write_text('class Contract { static final String CAPABILITY_ID = "cubism.test"; '
                                    'static final Set<String> SUPPORTED_VERSIONS = ' + selection + '; }')
                declarations.append((load_commands(root, versions), load_contract_gates(root)))
            self.assertEqual(declarations[0], declarations[1])
            self.assertEqual(versions[1:], declarations[1][0][0]["versions"])

    def test_unknown_or_reversed_boundaries_are_rejected(self):
        for expression in ('ReviewedVersions.since("5.3.05")',
                           'ReviewedVersions.between("5.3.04", "5.3.02")'):
            with self.subTest(expression=expression), self.assertRaises(ValueError):
                reviewed_range_versions(expression, {"5.3.02", "5.3.04"})

    def test_selection_never_invents_unreviewed_patches(self):
        self.assertEqual({"5.3.02", "5.3.04"}, reviewed_range_versions(
            'ReviewedVersions.between("5.3.02", "5.3.04")', {"5.3.02", "5.3.04"}))

    def test_family_range_requires_record_even_across_minor_lines(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            releases = root / "compatibility/cubism/reviewed-releases.json"
            releases.parent.mkdir(parents=True)
            releases.write_text(table(["5.3.04", "5.4.00"]))
            family = root / "scripts/verification-sources/families/test.json"
            family.parent.mkdir(parents=True)
            family.write_text(json.dumps({"family": "test", "files": ["Test.java"],
                                          "records": ["old.json", "new.json"]}))
            source = root / "scripts/verification-sources/templates/Test.java"
            source.parent.mkdir(parents=True)
            source.write_text('ReviewedVersions.since("5.3.04")')
            records = {"old.json": json.dumps({"cubismVersion": "5.3.04", "status": "VERIFIED_STATIC"})}
            with patch.object(gate, "render_records", return_value=records):
                self.assertIn("5.4.00", gate.r1_family_range_coverage(root)[0])
                records["new.json"] = json.dumps({"cubismVersion": "5.4.00", "status": "DRAFT"})
                self.assertTrue(gate.r1_family_range_coverage(root))
                records["new.json"] = json.dumps({"cubismVersion": "5.4.00", "status": "VERIFIED_STATIC"})
                self.assertEqual([], gate.r1_family_range_coverage(root))


class CheckHostValidationVersionsTest(unittest.TestCase):
    def setUp(self):
        original = gate.render_records
        def records(root):
            if root == gate.r4_ROOT:
                return original(root)
            return {f"cubism-{version}-workspace.json": json.dumps({
                "status": "VERIFIED_STATIC", "cubismVersion": version, "capabilityIds": ["cubism.workspace.control"]}).encode()
                for version in ("5.2.03", "5.3.02")}
        self.patch = patch.object(gate, "render_records", side_effect=records)
        self.patch.start()
        self.addCleanup(self.patch.stop)

    def test_draft_records_do_not_admit_task_versions(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self._write_fixture(root)
            with patch.object(gate, "render_records", return_value={"draft.json": json.dumps({
                    "status": "DRAFT", "cubismVersion": "5.3.02",
                    "capabilityIds": ["cubism.workspace.control"]}).encode()}):
                self.assertTrue(gate.r4_check(root))

    def test_live_repository_passes(self) -> None:
        records = gate.r4_ROOT / "build/generated/verification"
        if not records.is_dir():
            self.skipTest(
                "the live matrix is a build product; materialize the verification "
                "records before running this suite"
            )
        with tempfile.TemporaryDirectory() as directory:
            # The gate reads the generated matrix, so prove the live tree agrees
            # with a fresh projection of its own records and reviewed releases.
            matrix = Path(directory) / "compatibility-matrix.json"
            matrix.write_bytes(
                json.dumps(
                    matrix_generator.build_matrix(gate.r4_ROOT),
                    ensure_ascii=False,
                    indent=1,
                ).encode("utf-8")
            )
            with patch.object(gate, "r4_MATRIX_PATH", matrix):
                self.assertEqual([], gate.r4_check(gate.r4_ROOT))

    def test_manifest_version_drift_is_rejected_until_registered(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self._write_fixture(root)
            self.assertEqual([], gate.r4_check(root))

            manifest_file = root / gate.r4_MANIFEST_PATH
            manifest = json.loads(manifest_file.read_text(encoding="utf-8"))
            manifest["tasks"]["workspace"]["versions"] = ["5302"]
            manifest_file.write_text(json.dumps(manifest), encoding="utf-8")

            failures = gate.r4_check(root)
            self.assertTrue(
                any("task 'workspace' versions" in f for f in failures),
                failures,
            )

            self.assertEqual(0, gate.r4_update(root))
            self.assertEqual([], gate.r4_check(root))

    def test_unregistered_wrapper_version_literal_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self._write_fixture(root)
            wrapper = root / "scripts/preview/run-workspace-host-validation.sh"
            wrapper.write_text(
                '#!/usr/bin/env bash\ncase "$version" in 5203|5302) ;; esac\n',
                encoding="utf-8",
            )
            failures = gate.r4_check(root)
            self.assertTrue(
                any("unregistered wrapper version enumeration" in f for f in failures),
                failures,
            )

            reg_file = root / "scripts/reviewed-release-literal-exceptions.json"
            reg = json.loads(reg_file.read_text(encoding="utf-8"))
            reg["sites"].append(
                {
                    "kind": "file",
                    "path": "scripts/preview/run-workspace-host-validation.sh",
                    "versions": ["5.2.03", "5.3.02"],
                    "basis": "evidence",
                    "evidence": "documented bytecode boundary",
                }
            )
            reg_file.write_text(json.dumps(reg), encoding="utf-8")
            self.assertEqual([], gate.r4_check(root))

    @staticmethod
    def _write_fixture(root: Path) -> None:
        artifacts = root / "compatibility/cubism/reviewed-releases.json"
        artifacts.parent.mkdir(parents=True, exist_ok=True)
        artifacts.write_text(table(['5.2.03', '5.3.02']), encoding='utf-8')
        matrix = root / gate.r4_MATRIX_PATH
        matrix.parent.mkdir(parents=True, exist_ok=True)
        matrix.write_text(
            json.dumps(
                {
                    "reviewedVersions": ["5.2.03", "5.3.02"],
                    "capabilities": [
                        {
                            "id": "cubism.workspace.control",
                            "admitted": ["5.2.03", "5.3.02"],
                        }
                    ],
                    "capabilityGroups": [
                        {
                            "id": "workspace",
                            "capabilities": ["cubism.workspace.control"],
                        }
                    ],
                    "plugins": [],
                    "classPins": [],
                    "taskPluginAliases": {},
                    "taskGroupAliases": {"workspace": "workspace"},
                }
            ),
            encoding="utf-8",
        )
        manifest = root / gate.r4_MANIFEST_PATH
        manifest.parent.mkdir(parents=True, exist_ok=True)
        manifest.write_text(
            json.dumps(
                {
                    "format": "turboism.host-validation.tasks",
                    "schemaVersion": 1,
                    "tasks": {
                        "workspace": {
                            "command": "scripts/preview/run-workspace-host-validation.sh",
                            "versions": ["5203", "5302"],
                        }
                    },
                }
            ),
            encoding="utf-8",
        )
        wrapper = root / "scripts/preview/run-workspace-host-validation.sh"
        wrapper.write_text(
            '#!/usr/bin/env bash\nturboism_require_task_version workspace "$1"\n',
            encoding="utf-8",
        )
        reg = root / "scripts/reviewed-release-literal-exceptions.json"
        reg.parent.mkdir(parents=True, exist_ok=True)
        reg.write_text(
            json.dumps(
                {
                    "format": "turboism.reviewed-release-literal-exceptions/v1",
                    "sites": [],
                }
            ),
            encoding="utf-8",
        )


if __name__ == "__main__":
    unittest.main()
