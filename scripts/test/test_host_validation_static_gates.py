#!/usr/bin/env python3
"""Focused tests for prepare-time static gates (L0) and cluster annotation (L1)."""

import hashlib
import json
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

PREVIEW = Path(__file__).resolve().parents[1] / "preview"
sys.path.insert(0, str(PREVIEW))

import host_validation as scheduler
import host_validation_static_gates as gates


class StaticGateTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.manifest = scheduler.load_manifest(
            PREVIEW / "host-validation-tasks.json", {})

    def request(self, spec: str):
        return scheduler.parse_request(spec, "test-run", self.manifest)

    def evaluate(self, spec: str, environment=None):
        return gates.evaluate(self.request(spec), scheduler.Request,
                              self.manifest, environment or {})

    def test_admission_refusal_without_verified_static_write_evidence(self):
        with self.assertRaises(gates.StaticGateRefusal) as caught:
            self.evaluate("parameter-batch-transfer:5002@assisted")
        self.assertEqual("SKIP_STATIC", caught.exception.skip)
        self.assertEqual("admission", caught.exception.reason)
        self.assertIn("cubism.editor-model.write", caught.exception.detail)

    def test_admission_boundary_matches_records(self):
        for version in ("5002", "5103"):
            with self.subTest(version=version), self.assertRaises(gates.StaticGateRefusal):
                self.evaluate(f"parameter-batch-transfer:{version}@assisted")
        for version in ("5201", "5304"):
            request, _ = self.evaluate(f"parameter-batch-transfer:{version}@assisted")
            self.assertIn("assisted", request.variant)

    def test_free_license_refusal(self):
        environment = self._fixture_environment()
        with self.assertRaises(gates.StaticGateRefusal) as caught:
            self.evaluate("parameter-batch-transfer:5100@assisted", environment)
        self.assertEqual("SKIP_LICENSE", caught.exception.skip)
        self.assertEqual("license", caught.exception.reason)

    def test_unknown_license_falls_through_to_other_gates(self):
        # 5101 is not in the observed-FREE table, so the license gate stays
        # silent; the admission wall still refuses the write path.
        with self.assertRaises(gates.StaticGateRefusal) as caught:
            self.evaluate("parameter-batch-transfer:5101@assisted")
        self.assertEqual("admission", caught.exception.reason)

    def test_fixture_newer_than_target_routes_to_assisted(self):
        environment = self._fixture_environment()
        for spec, assisted in (
            ("clipmask-viewer:5000", "assisted"),
            ("psd-clip-mask:5000@matrix", "matrix-assisted"),
            ("edit:5000", "assisted"),
        ):
            with self.subTest(spec=spec):
                request, outcome = self.evaluate(spec, environment)
                self.assertEqual(assisted, request.variant)
                self.assertTrue(outcome.routed)
                self.assertTrue(any("fixture-version-routing" in n for n in outcome.notes))

    def test_fixture_at_or_below_target_keeps_variant(self):
        environment = self._fixture_environment()
        request, outcome = self.evaluate("clipmask-viewer:5304", environment)
        self.assertEqual("standard", request.variant)
        self.assertFalse(outcome.routed)

    def test_newer_fixture_without_assisted_variant_only_notes(self):
        environment = self._fixture_environment()
        request, outcome = self.evaluate("boundingbox-warp-mirror:5000@analyse", environment)
        self.assertEqual("analyse", request.variant)
        self.assertFalse(outcome.routed)
        self.assertTrue(any("no assisted variant" in n for n in outcome.notes))

    def _fixture_environment(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        path = Path(directory.name) / "fixture.cmo3"
        path.write_bytes(b"newer-document")
        digest = hashlib.sha256(path.read_bytes()).hexdigest()
        environment = {"TURBOISM_HOST_VALIDATION_FIXTURE_5303": str(path)}
        table = dict(gates.FIXTURE_DOC_VERSION)
        table[digest] = "5.2.03"
        patcher = mock.patch.dict(gates.FIXTURE_DOC_VERSION, table, clear=True)
        patcher.start()
        self.addCleanup(patcher.stop)
        free = set(gates.FREE_LIMIT_EXCEEDED_FIXTURES)
        free.add(digest)
        patcher2 = mock.patch.object(gates, "FREE_LIMIT_EXCEEDED_FIXTURES", free)
        patcher2.start()
        self.addCleanup(patcher2.stop)
        return environment


class ClusterEquivalenceTest(unittest.TestCase):
    FAMILIES = {
        "core-model-read": {
            "strict": [
                {"rep": "5.0.00", "members": ["5.0.00", "5.0.01", "5.1.00"], "missingRoots": {}},
                {"rep": "5.3.00", "members": ["5.3.00", "5.3.04"], "missingRoots": {}},
            ],
            "shallow": [{"rep": "5.0.00", "members": ["5.0.00"], "missingRoots": {}}],
        }
    }

    def _ledger(self, entries):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        path = Path(directory.name) / "campaign-jobs.json"
        path.write_text(json.dumps({"schemaVersion": 1, "jobs": entries}))
        return path

    def test_covered_by_strict_cluster_rep_pass(self):
        ledger = self._ledger([
            {"task": "core-acquisition", "version": "5.3.00", "status": "FINAL",
             "result": {"validationStatus": "PASS"}},
        ])
        self.assertEqual(
            "5.3.00",
            gates.covered_by_equivalent("core-acquisition", "5304", self.FAMILIES, ledger))

    def test_no_equivalence_without_member_pass(self):
        ledger = self._ledger([
            {"task": "core-acquisition", "version": "5.3.00", "status": "FINAL",
             "result": {"validationStatus": "FAIL"}},
        ])
        self.assertIsNone(
            gates.covered_by_equivalent("core-acquisition", "5304", self.FAMILIES, ledger))

    def test_target_member_with_pass_does_not_self_annotate(self):
        ledger = self._ledger([
            {"task": "core-acquisition", "version": "5.0.00", "status": "FINAL",
             "result": {"validationStatus": "PASS"}},
        ])
        self.assertIsNone(
            gates.covered_by_equivalent("core-acquisition", "5000", self.FAMILIES, ledger))

    def test_unmapped_task_and_missing_table_are_noops(self):
        ledger = self._ledger([])
        self.assertIsNone(
            gates.covered_by_equivalent("mcp", "5304", self.FAMILIES, ledger))
        self.assertIsNone(
            gates.covered_by_equivalent("core-acquisition", "5304", {}, ledger))

    def test_missing_cluster_file_loads_empty(self):
        self.assertEqual({}, gates.load_cluster_table(Path("/nonexistent/clusters.json")))

    def test_real_cluster_table_shape_when_committed(self):
        table_path = gates.CLUSTERS_PATH
        if not table_path.is_file():
            self.skipTest("cluster artifact not generated in this checkout")
        families = gates.load_cluster_table()
        for name, entry in families.items():
            self.assertIn("strict", entry)
            self.assertIn("shallow", entry)
            for cluster in entry["strict"] + entry["shallow"]:
                self.assertIn("rep", cluster)
                self.assertIn("members", cluster)
                self.assertIn("missingRoots", cluster)


if __name__ == "__main__":
    unittest.main()
