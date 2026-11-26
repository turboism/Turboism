#!/usr/bin/env python3
"""Unit tests for scripts/dev/axiomatic_design_matrix.py (#335)."""

from __future__ import annotations

import copy
import json
import shutil
import tempfile
import unittest
from pathlib import Path

from scripts.dev import axiomatic_design_matrix as adm

REPO_ROOT = Path(__file__).resolve().parents[2]
SCHEMA_SOURCE = REPO_ROOT / "docs/architecture/design-matrix.schema.json"


def build_minimal_fixture(root: Path) -> dict:
    """Create a self-contained two-FR fixture tree on disk and return its valid matrix JSON."""
    (root / "docs/architecture").mkdir(parents=True, exist_ok=True)
    (root / "src/a").mkdir(parents=True, exist_ok=True)
    (root / "src/b").mkdir(parents=True, exist_ok=True)
    (root / "src/a/Lock.java").write_text("class Lock {}\n", encoding="utf-8")
    (root / "src/b/Sync.java").write_text("class Sync {}\n", encoding="utf-8")
    shutil.copy2(SCHEMA_SOURCE, root / "docs/architecture/design-matrix.schema.json")
    (root / "settings.gradle.kts").write_text(
        'rootProject.name = "fixture"\ninclude("sdk", "plugins:lock", "plugins:sync")\n',
        encoding="utf-8",
    )
    inventory = {
        "format": "turboism-dependency-inventory-v1",
        "modules": {
            ":plugins:lock": {"compileOnly": [":sdk"]},
            ":plugins:sync": {"compileOnly": [":sdk"]},
        },
        "runtime": {
            "topPackages": ["dev.turboism.core"],
            "topPackageEdges": [],
            "topPackageCycles": [],
            "packageCycles": [],
        },
    }
    (root / "docs/architecture/dependency-inventory.json").write_text(
        json.dumps(inventory, indent=2) + "\n", encoding="utf-8"
    )
    (root / "docs/architecture/AXIOMATIC_DESIGN.md").write_text(
        "# Baseline\ndesign-matrix.json C1 FR1 FR2 DP1 DP2 EXC-AD-001\n",
        encoding="utf-8",
    )
    matrix = {
        "format": "turboism-axiomatic-design-matrix-v1",
        "baselineSnapshot": "d13148bc107f",
        "structuralBaseline": {
            "inventoryPath": "docs/architecture/dependency-inventory.json",
            "provesFunctionalIndependence": False,
            "gradleGraphNodes": 3,
            "pluginModules": 2,
            "nonTestEdges": 2,
            "testOnlyEdges": 0,
            "nonTestModuleCycles": 0,
            "directProductionPluginEdges": 0,
            "runtimeTopPackages": 1,
            "runtimeTopPackageEdges": 0,
            "runtimeTopPackageCycles": 0,
            "runtimeLeafPackageCycles": 0,
            "note": "Static edges never prove functional independence.",
        },
        "constraints": [
            {
                "id": "C1",
                "title": "SDK isolation",
                "statement": "Plugins depend only on :sdk.",
                "enforcementGates": ["checkPluginBoundaries"],
                "codePaths": ["settings.gradle.kts"],
            }
        ],
        "domains": [{"id": "D1", "name": "Parameters", "requirementIds": ["FR1", "FR2"]}],
        "requirements": [
            {
                "id": "FR1",
                "domainId": "D1",
                "statement": "Retain locked parameter values during writes.",
                "acceptanceCriterion": "Locked parameter keeps prior float value.",
                "verificationMethod": "LockTest",
            },
            {
                "id": "FR2",
                "domainId": "D1",
                "statement": "Propagate parameter edits across synchronized targets.",
                "acceptanceCriterion": "Unlocked targets follow normalized driver progress.",
                "verificationMethod": "SyncTest",
            },
        ],
        "designParameters": [
            {
                "id": "DP1",
                "satisfiesFrId": "FR1",
                "summary": "Before-hook parameter lock interceptor.",
                "modules": [":plugins:lock"],
                "codePaths": ["src/a/Lock.java"],
            },
            {
                "id": "DP2",
                "satisfiesFrId": "FR2",
                "summary": "On-event parameter synchronization engine.",
                "modules": [":plugins:sync"],
                "codePaths": ["src/b/Sync.java"],
            },
        ],
        "ordering": [
            {"index": 1, "frId": "FR1", "dpId": "DP1"},
            {"index": 2, "frId": "FR2", "dpId": "DP2"},
        ],
        "grid": {
            "FR1": ["X", "0"],
            "FR2": ["x", "X"],
        },
        "entries": [
            {
                "frId": "FR1",
                "dpId": "DP1",
                "symbol": "X",
                "relation": "PRIMARY",
                "evidenceStatus": "OBSERVED",
                "effectSummary": "Primary lock enforcement.",
                "evidencePaths": ["src/a/Lock.java"],
            },
            {
                "frId": "FR2",
                "dpId": "DP1",
                "symbol": "x",
                "relation": "AFFECTS",
                "evidenceStatus": "OBSERVED",
                "effectSummary": "Locked targets reject incoming sync writes cleanly.",
                "evidencePaths": ["src/a/Lock.java", "src/b/Sync.java"],
            },
            {
                "frId": "FR2",
                "dpId": "DP2",
                "symbol": "X",
                "relation": "PRIMARY",
                "evidenceStatus": "OBSERVED",
                "effectSummary": "Primary parameter sync propagation.",
                "evidencePaths": ["src/b/Sync.java"],
            },
        ],
        "submatrices": [
            {
                "id": "lock-sync",
                "title": "Lock vs Sync",
                "classification": "DECOUPLED",
                "axes": ["FR1:DP1", "FR2:DP2"],
                "grid": [["X", "0"], ["x", "X"]],
                "sequencingRule": "Configure locks before sync rules.",
                "evidencePaths": ["src/a/Lock.java", "src/b/Sync.java"],
            }
        ],
        "analysis": {
            "observedClassification": "DECOUPLED",
            "overallClassification": "DECOUPLED",
            "diagonalCount": 2,
            "lowerTriangularObservedCount": 1,
            "lowerTriangularHypothesisCount": 0,
            "upperTriangularObservedCount": 0,
            "upperTriangularHypothesisCount": 0,
            "lowerTriangularUnknownCount": 0,
            "upperTriangularUnknownCount": 0,
            "observedFunctionalSccs": [],
            "potentialFunctionalSccsWithUnknowns": [],
            "triangularSequencing": [
                {"step": 1, "scope": "DP1 -> DP2", "rule": "Set parameter locks before sync."}
            ],
        },
        "exceptions": [
            {
                "id": "EXC-AD-001",
                "title": "Sample tracked quirk",
                "owner": "sole-maintainer",
                "status": "TRACKED_INTERACTION_QUIRK",
                "codePaths": ["src/b/Sync.java"],
                "summary": "Suppression window stays active for 500ms after rejected write.",
                "evaluatedAlternative": "Clear guard when post-write readback equals prior value.",
                "followUp": "Track in parameter-sync cleanup issue.",
            }
        ],
    }
    write_matrix(root, matrix)
    return matrix


def write_matrix(root: Path, matrix: dict) -> None:
    (root / "docs/architecture/design-matrix.json").write_text(
        json.dumps(matrix, indent=2) + "\n", encoding="utf-8"
    )


class AxiomaticDesignMatrixTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp_dir = Path(tempfile.mkdtemp(prefix="ad-matrix-test-"))
        self.addCleanup(lambda: shutil.rmtree(self.temp_dir, ignore_errors=True))
        self.matrix = build_minimal_fixture(self.temp_dir)

    def test_minimal_triangular_fixture_passes_check_and_report(self) -> None:
        self.assertEqual(adm.main(["--root", str(self.temp_dir), "--check"]), 0)
        self.assertEqual(adm.main(["--root", str(self.temp_dir), "--report"]), 0)

    def test_uncoupled_diagonal_classification_detected(self) -> None:
        updated = copy.deepcopy(self.matrix)
        updated["grid"]["FR2"] = ["0", "X"]
        updated["entries"] = [e for e in updated["entries"] if not (e["frId"] == "FR2" and e["dpId"] == "DP1")]
        updated["analysis"]["observedClassification"] = "UNCOUPLED"
        updated["analysis"]["overallClassification"] = "UNCOUPLED"
        updated["analysis"]["lowerTriangularObservedCount"] = 0
        write_matrix(self.temp_dir, updated)
        _, errors = adm.validate_repository_matrix(self.temp_dir)
        self.assertEqual(errors, [])

    def test_coupled_feedback_cycle_computes_scc_and_rejects_decoupled_claim(self) -> None:
        updated = copy.deepcopy(self.matrix)
        updated["grid"]["FR1"] = ["X", "x"]
        updated["entries"].insert(
            1,
            {
                "frId": "FR1",
                "dpId": "DP2",
                "symbol": "x",
                "relation": "AFFECTS",
                "evidenceStatus": "OBSERVED",
                "effectSummary": "Reverse coupling breaks lock.",
                "evidencePaths": ["src/b/Sync.java"],
            },
        )
        write_matrix(self.temp_dir, updated)
        _, errors = adm.validate_repository_matrix(self.temp_dir)
        self.assertTrue(any("observedClassification" in err and "COUPLED" in err for err in errors), errors)
        self.assertTrue(any("observedFunctionalSccs" in err for err in errors), errors)

    def test_unknown_entry_requires_verification_task_and_marks_overall_unverified(self) -> None:
        updated = copy.deepcopy(self.matrix)
        updated["grid"]["FR1"] = ["X", "?"]
        updated["entries"].insert(
            1,
            {
                "frId": "FR1",
                "dpId": "DP2",
                "symbol": "?",
                "relation": "UNKNOWN",
                "evidenceStatus": "UNVERIFIED",
                "effectSummary": "Unverified reverse timing.",
                "evidencePaths": ["src/b/Sync.java"],
            },
        )
        write_matrix(self.temp_dir, updated)
        _, errors = adm.validate_repository_matrix(self.temp_dir)
        self.assertTrue(any("requires verificationTask" in err for err in errors), errors)

        updated["entries"][1]["verificationTask"] = "Run concurrency probe on FR1/DP2."
        updated["analysis"]["overallClassification"] = "UNVERIFIED"
        updated["analysis"]["upperTriangularUnknownCount"] = 1
        updated["analysis"]["potentialFunctionalSccsWithUnknowns"] = [["FR1/DP1", "FR2/DP2"]]
        write_matrix(self.temp_dir, updated)
        _, errors = adm.validate_repository_matrix(self.temp_dir)
        self.assertEqual(errors, [])

    def test_proves_functional_independence_true_is_rejected_by_schema(self) -> None:
        updated = copy.deepcopy(self.matrix)
        updated["structuralBaseline"]["provesFunctionalIndependence"] = True
        write_matrix(self.temp_dir, updated)
        _, errors = adm.validate_repository_matrix(self.temp_dir)
        self.assertTrue(any("provesFunctionalIndependence" in err for err in errors), errors)

    def test_missing_evidence_file_fails_closed(self) -> None:
        updated = copy.deepcopy(self.matrix)
        updated["entries"][0]["evidencePaths"] = ["src/a/DoesNotExist.java"]
        write_matrix(self.temp_dir, updated)
        _, errors = adm.validate_repository_matrix(self.temp_dir)
        self.assertTrue(any("does not exist" in err for err in errors), errors)


if __name__ == "__main__":
    unittest.main(verbosity=2)
