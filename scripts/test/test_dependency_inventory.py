#!/usr/bin/env python3
"""Stdlib runnable suite for scripts/dev/dependency_inventory.py (#70 step 1).

Run:  python3 scripts/test/test_dependency_inventory.py

The inventory is a ratchet: a new module edge or a new `:runtime` package cycle
must fail the gate so that it is reviewed instead of discovered later. These
fixtures pin the failure modes, the import-resolution rule (longest declared
package, not the first one) and byte-for-byte determinism.
"""

import json
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

SCRIPTS_DIR = Path(__file__).resolve().parents[1]
REPO_ROOT = SCRIPTS_DIR.parent
INVENTORY_SCRIPT = SCRIPTS_DIR / "dev" / "dependency_inventory.py"

GRAPH = {
    "format": "turboism-project-graph-v1",
    "modules": {
        ":runtime": {"implementation": [":core-contract", ":sdk"]},
        ":plugins:demo": {"annotationProcessor": [":event-processor"], "compileOnly": [":sdk"]},
    },
}

SOURCES = {
    "dev/turboism/core/Api.java": "package dev.turboism.core;\n\nclass Api {}\n",
    "dev/turboism/core/sub/Impl.java": (
        "package dev.turboism.core.sub;\n\nimport dev.turboism.ui.Panel;\n\nclass Impl {}\n"
    ),
    "dev/turboism/ui/Panel.java": (
        "package dev.turboism.ui;\n\nimport dev.turboism.core.sub.Impl;\n\nclass Panel {}\n"
    ),
}


class InventoryTree:
    """Throwaway repository tree with a deliberately cyclic `:runtime`."""

    def __init__(self, graph=None, sources=None):
        self.root = Path(tempfile.mkdtemp(prefix="dependency-inventory-"))
        (self.root / "scripts" / "dev").mkdir(parents=True)
        shutil.copy2(INVENTORY_SCRIPT, self.root / "scripts" / "dev" / INVENTORY_SCRIPT.name)
        self.script = self.root / "scripts" / "dev" / INVENTORY_SCRIPT.name
        self.sources = self.root / "runtime" / "src" / "main" / "java"
        self.sources.mkdir(parents=True)
        for relative, content in (sources or SOURCES).items():
            target = self.sources / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(content, encoding="utf-8")
        self.graph = self.root / "build" / "project-graph.json"
        self.graph.parent.mkdir(parents=True, exist_ok=True)
        self.write_graph(graph or GRAPH)
        self.inventory = self.root / "architecture" / "dependency-inventory.json"

    def write_graph(self, payload):
        self.graph.write_text(json.dumps(payload), encoding="utf-8")

    def run(self, mode):
        return subprocess.run(
            [sys.executable, str(self.script), mode, "--project-graph", str(self.graph)],
            capture_output=True,
            text=True,
            check=False,
        )

    def write_baseline(self):
        result = self.run("--write")
        assert result.returncode == 0, result.stderr
        return json.loads(self.inventory.read_text(encoding="utf-8"))

    def cleanup(self):
        shutil.rmtree(self.root, ignore_errors=True)


class DependencyInventoryTest(unittest.TestCase):
    def setUp(self):
        self.tree = InventoryTree()
        self.addCleanup(self.tree.cleanup)

    def test_write_records_modules_packages_and_cycles(self):
        inventory = self.tree.write_baseline()
        self.assertEqual(inventory["format"], "turboism-dependency-inventory-v1")
        self.assertEqual(
            inventory["modules"][":plugins:demo"],
            {"annotationProcessor": [":event-processor"], "compileOnly": [":sdk"]},
        )
        self.assertEqual(
            inventory["runtime"]["topPackages"],
            ["dev.turboism.core", "dev.turboism.ui"],
        )
        self.assertEqual(
            inventory["runtime"]["packageCycles"],
            [["dev.turboism.core.sub", "dev.turboism.ui"]],
        )

    def test_write_is_byte_for_byte_deterministic(self):
        self.tree.write_baseline()
        first = self.tree.inventory.read_bytes()
        self.tree.write_baseline()
        self.assertEqual(first, self.tree.inventory.read_bytes())

    def test_intra_package_imports_do_not_create_cycles(self):
        self.tree.write_baseline()
        target = self.tree.sources / "dev/turboism/core/sub/Other.java"
        target.write_text(
            "package dev.turboism.core.sub;\n\nimport dev.turboism.core.sub.Impl;\n\nclass Other {}\n",
            encoding="utf-8",
        )
        result = self.tree.run("--check")
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_imports_resolve_to_the_longest_declared_package(self):
        inventory = self.tree.write_baseline()
        # `dev.turboism.core` is declared too, but the imported type lives in
        # `dev.turboism.core.sub`; a first-prefix match would produce that broader cycle.
        self.assertEqual(
            inventory["runtime"]["packageCycles"],
            [["dev.turboism.core.sub", "dev.turboism.ui"]],
        )

    def test_top_package_edges_skip_intra_package_imports(self):
        inventory = self.tree.write_baseline()
        # Both directions exist (that is the cycle); the self edge core -> core must not.
        self.assertEqual(
            inventory["runtime"]["topPackageEdges"],
            [["dev.turboism.core", "dev.turboism.ui"], ["dev.turboism.ui", "dev.turboism.core"]],
        )

    def test_new_cycle_fails_the_gate(self):
        self.tree.write_baseline()
        for relative, content in (
            ("dev/turboism/alpha/A.java", "package dev.turboism.alpha;\n\nimport dev.turboism.beta.B;\n\nclass A {}\n"),
            ("dev/turboism/beta/B.java", "package dev.turboism.beta;\n\nimport dev.turboism.alpha.A;\n\nclass B {}\n"),
        ):
            target = self.tree.sources / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(content, encoding="utf-8")
        result = self.tree.run("--check")
        self.assertEqual(result.returncode, 1, result.stdout + result.stderr)
        self.assertIn("dependency inventory drift", result.stderr)
        self.assertIn("new :runtime package cycle: dev.turboism.alpha, dev.turboism.beta", result.stderr)

    def test_resolved_cycle_fails_the_gate(self):
        self.tree.write_baseline()
        (self.tree.sources / "dev/turboism/ui/Panel.java").write_text(
            "package dev.turboism.ui;\n\nclass Panel {}\n", encoding="utf-8"
        )
        result = self.tree.run("--check")
        self.assertEqual(result.returncode, 1, result.stdout + result.stderr)
        self.assertIn("resolved :runtime package cycle", result.stderr)

    def test_module_edge_change_fails_the_gate(self):
        self.tree.write_baseline()
        self.tree.write_graph(
            {
                "format": "turboism-project-graph-v1",
                "modules": {
                    ":runtime": {"implementation": [":core-contract", ":sdk"]},
                    ":plugins:demo": {"compileOnly": [":sdk", ":runtime"]},
                },
            }
        )
        result = self.tree.run("--check")
        self.assertEqual(result.returncode, 1, result.stdout + result.stderr)
        self.assertIn(":plugins:demo", result.stderr)
        self.assertIn(":runtime", result.stderr)

    def test_new_module_fails_the_gate(self):
        self.tree.write_baseline()
        payload = json.loads(json.dumps(GRAPH))
        payload["modules"][":plugins:new"] = {"compileOnly": [":sdk"]}
        self.tree.write_graph(payload)
        result = self.tree.run("--check")
        self.assertEqual(result.returncode, 1, result.stdout + result.stderr)
        self.assertIn("module added without inventory review", result.stderr)

    def test_missing_baseline_fails_closed(self):
        result = self.tree.run("--check")
        self.assertEqual(result.returncode, 1, result.stdout + result.stderr)
        self.assertIn("dependency inventory missing", result.stderr)

    def test_unexpected_project_graph_format_fails_closed(self):
        self.tree.write_baseline()
        self.tree.write_graph({"format": "something-else", "modules": {":runtime": {}}})
        result = self.tree.run("--check")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("unexpected project graph format", result.stderr)

    def test_missing_runtime_sources_fail_closed(self):
        self.tree.write_baseline()
        shutil.rmtree(self.tree.sources)
        self.tree.sources.mkdir(parents=True)
        result = self.tree.run("--check")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("no sources", result.stderr)

    def test_report_mode_does_not_write_and_reports_cycles(self):
        self.tree.write_baseline()
        before = self.tree.inventory.read_bytes()
        result = self.tree.run("--report")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("leaf-package cycles: 1", result.stdout)
        self.assertEqual(before, self.tree.inventory.read_bytes())


if __name__ == "__main__":
    unittest.main(verbosity=2)
