#!/usr/bin/env python3
"""Reproducible architecture dependency inventory for issue #70, step 1.

The inventory records three stable facts so that a change is reviewed instead of
discovered late:

  1. the module graph Gradle actually resolves (including edges that convention
     plugins add and that no `build.gradle.kts` text scan can see);
  2. which top-level `dev.turboism.*` packages `:runtime` owns;
  3. the package dependency cycles inside `:runtime`, at leaf-package and at
     top-level-package granularity.

What this is not: a type-aware dependency proof. Imports are a syntactic signal,
so the authoritative checks stay `checkModuleBoundaries`,
`checkResolvedBytecodeDependencyGraph` and the SDK ABI gates. A cycle that only
existed through fully qualified references without imports would be missed here
and is still caught by the module-boundary gate.

Modes (exactly one):
  --write    regenerate `architecture/dependency-inventory.json`
  --check    fail closed when the committed inventory drifts from the tree
  --report   print the analysis (fan-in/out, edges, cycles) without writing

`--project-graph` points at the JSON written by the `dependencyInventoryRaw`
Gradle task, which is the only part Gradle can answer authoritatively.

Deterministic by construction: every collection is sorted, paths are relative,
and no timestamp, host or version is recorded.
"""

from __future__ import annotations

import argparse
import collections
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
INVENTORY = ROOT / "architecture/dependency-inventory.json"
RUNTIME_SOURCES = ROOT / "runtime/src/main/java"
FORMAT = "turboism-dependency-inventory-v1"
PROJECT_GRAPH_FORMAT = "turboism-project-graph-v1"

PACKAGE_DECLARATION = re.compile(r"^package\s+([\w.]+)\s*;", re.MULTILINE)
IMPORT = re.compile(r"^import\s+(?:static\s+)?([\w.]+)\s*;", re.MULTILINE)


def read_project_graph(path: Path) -> dict[str, dict[str, list[str]]]:
    payload = json.loads(path.read_text(encoding="utf-8"))
    if payload.get("format") != PROJECT_GRAPH_FORMAT:
        raise ValueError(f"unexpected project graph format: {payload.get('format')!r}")
    modules = payload.get("modules")
    if not isinstance(modules, dict) or not modules:
        raise ValueError("project graph contains no modules")
    normalized: dict[str, dict[str, list[str]]] = {}
    for module in sorted(modules):
        configurations = modules[module]
        if not isinstance(configurations, dict):
            raise ValueError(f"module {module} is not an object")
        edges = {}
        for configuration in sorted(configurations):
            dependencies = configurations[configuration]
            if not isinstance(dependencies, list) or not dependencies:
                raise ValueError(f"{module} {configuration} is not a non-empty list")
            edges[configuration] = sorted(str(dependency) for dependency in dependencies)
        if edges:
            normalized[module] = edges
    return normalized


def top_package(package: str) -> str:
    parts = package.split(".")
    return ".".join(parts[:3]) if len(parts) >= 3 else package


class RuntimeGraph:
    """Leaf-package graph of `:runtime` built from package declarations and imports."""

    def __init__(self, sources: Path):
        self.packages: set[str] = set()
        self.edges: collections.Counter[tuple[str, str]] = collections.Counter()
        files = sorted(sources.rglob("*.java"))
        if not files:
            raise ValueError(f"no sources under {sources}")
        declared: dict[Path, str] = {}
        for path in files:
            text = path.read_text(encoding="utf-8", errors="replace")
            match = PACKAGE_DECLARATION.search(text)
            if match is None:
                continue
            declared[path] = match.group(1)
        self.packages = set(declared.values())
        for path, package in declared.items():
            text = path.read_text(encoding="utf-8", errors="replace")
            for imported in IMPORT.findall(text):
                target = self.resolve(imported)
                if target is None or target == package:
                    continue
                self.edges[(package, target)] += 1

    def resolve(self, imported: str) -> str | None:
        """Longest declared package that prefixes the imported type, if any."""
        parts = imported.split(".")
        for length in range(len(parts), 0, -1):
            candidate = ".".join(parts[:length])
            if candidate in self.packages:
                return candidate
        return None

    def nodes(self) -> set[str]:
        return set(self.packages)

    def adjacency(self) -> dict[str, set[str]]:
        adjacency: dict[str, set[str]] = collections.defaultdict(set)
        for (source, target) in self.edges:
            adjacency[source].add(target)
        return adjacency

    def top_edges(self) -> list[list[str]]:
        pairs = {
            (top_package(source), top_package(target))
            for (source, target) in self.edges
            if top_package(source) != top_package(target)
        }
        return [[source, target] for source, target in sorted(pairs)]

    def top_packages(self) -> list[str]:
        return sorted({top_package(package) for package in self.packages})

    def cycles(self, granularity: str) -> list[list[str]]:
        if granularity == "top":
            adjacency: dict[str, set[str]] = collections.defaultdict(set)
            for source, target in self.top_edges():
                if source != target:
                    adjacency[source].add(target)
            return strongly_connected_cycles(adjacency)
        return strongly_connected_cycles(self.adjacency())


def strongly_connected_cycles(adjacency: dict[str, set[str]]) -> list[list[str]]:
    """Tarjan SCC; returns only components with more than one node, sorted."""
    nodes = sorted({node for node in adjacency} | {target for targets in adjacency.values() for target in targets})
    index: dict[str, int] = {}
    low: dict[str, int] = {}
    on_stack: set[str] = set()
    stack: list[str] = []
    components: list[list[str]] = []
    counter = [0]

    def visit(node: str) -> None:
        index[node] = low[node] = counter[0]
        counter[0] += 1
        stack.append(node)
        on_stack.add(node)
        for target in sorted(adjacency.get(node, ())):
            if target not in index:
                visit(target)
                low[node] = min(low[node], low[target])
            elif target in on_stack:
                low[node] = min(low[node], index[target])
        if low[node] == index[node]:
            component = []
            while True:
                member = stack.pop()
                on_stack.discard(member)
                component.append(member)
                if member == node:
                    break
            components.append(sorted(component))

    for node in nodes:
        if node not in index:
            visit(node)
    return sorted((component for component in components if len(component) > 1), key=lambda item: (-len(item), item))


def build(project_graph: dict[str, dict[str, list[str]]], graph: RuntimeGraph) -> dict:
    return {
        "format": FORMAT,
        "modules": {module: project_graph[module] for module in sorted(project_graph)},
        "runtime": {
            "topPackages": graph.top_packages(),
            "topPackageEdges": graph.top_edges(),
            "topPackageCycles": graph.cycles("top"),
            "packageCycles": graph.cycles("leaf"),
        },
    }


def report(project_graph: dict[str, dict[str, list[str]]], graph: RuntimeGraph) -> None:
    print("== module graph (production configurations only) ==")
    production = {"api", "implementation", "compileOnly", "compileOnlyApi", "runtimeOnly", "annotationProcessor"}
    for module in sorted(project_graph):
        edges = [
            f"{configuration} -> {', '.join(dependencies)}"
            for configuration, dependencies in sorted(project_graph[module].items())
            if configuration in production
        ]
        print(f"{module}: " + ("; ".join(edges) if edges else "(no project dependencies)"))

    print("\n== cross-module test-only edges ==")
    for module in sorted(project_graph):
        edges = [
            f"{configuration} -> {', '.join(dependencies)}"
            for configuration, dependencies in sorted(project_graph[module].items())
            if configuration not in production
        ]
        if edges:
            print(f"{module}: " + "; ".join(edges))

    print("\n== :runtime top-level package edges ==")
    for source, target in graph.top_edges():
        print(f"{source} -> {target}")

    top_fan_in = collections.Counter()
    leaf_fan_in = collections.Counter()
    leaf_fan_out = collections.Counter()
    for (source, target), count in graph.edges.items():
        top_fan_in[top_package(target)] += count
        leaf_fan_in[target] += count
        leaf_fan_out[source] += count
    print("\n== :runtime fan-in (top packages) ==")
    for package, count in sorted(top_fan_in.items(), key=lambda item: (-item[1], item[0])):
        print(f"{count:6d}  {package}")
    print("\n== :runtime top fan-out (leaf packages) ==")
    for package, count in sorted(leaf_fan_out.items(), key=lambda item: (-item[1], item[0]))[:15]:
        print(f"{count:6d}  {package}")

    def dump(title: str, cycles: list[list[str]]) -> None:
        print(f"\n== {title}: {len(cycles)} ==")
        for cycle in cycles:
            print(f"[{len(cycle)}] " + ", ".join(cycle))

    dump(":runtime leaf-package cycles", graph.cycles("leaf"))
    dump(":runtime top-level package cycles", graph.cycles("top"))


def describe_drift(committed: dict, observed: dict) -> list[str]:
    messages = []
    committed_modules = set(committed.get("modules", {}))
    observed_modules = set(observed.get("modules", {}))
    for module in sorted(observed_modules - committed_modules):
        messages.append(f"module added without inventory review: {module}")
    for module in sorted(committed_modules - observed_modules):
        messages.append(f"module removed: {module}")
    for module in sorted(committed_modules & observed_modules):
        old, new = committed["modules"][module], observed["modules"][module]
        for configuration in sorted(set(old) | set(new)):
            if old.get(configuration) != new.get(configuration):
                messages.append(
                    f"{module} {configuration}: committed {old.get(configuration)}, observed {new.get(configuration)}"
                )
    old_runtime = committed.get("runtime", {})
    new_runtime = observed.get("runtime", {})
    for key in ("topPackages", "topPackageEdges", "topPackageCycles", "packageCycles"):
        old, new = old_runtime.get(key), new_runtime.get(key)
        if old == new:
            continue
        if key.endswith("Cycles"):
            old_cycles = {tuple(cycle) for cycle in old or []}
            new_cycles = {tuple(cycle) for cycle in new or []}
            for cycle in sorted(new_cycles - old_cycles):
                messages.append("new :runtime package cycle: " + ", ".join(cycle))
            for cycle in sorted(old_cycles - new_cycles):
                messages.append("resolved :runtime package cycle (regenerate the baseline): " + ", ".join(cycle))
        else:
            old_set = {tuple(item) if isinstance(item, list) else item for item in old or []}
            new_set = {tuple(item) if isinstance(item, list) else item for item in new or []}
            for item in sorted(new_set - old_set):
                messages.append(f"new :runtime {key} entry: {item}")
            for item in sorted(old_set - new_set):
                messages.append(f"removed :runtime {key} entry (regenerate the baseline): {item}")
    return messages


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--project-graph", required=True, help="JSON written by dependencyInventoryRaw")
    modes = parser.add_mutually_exclusive_group(required=True)
    modes.add_argument("--write", action="store_true", help="regenerate the committed inventory")
    modes.add_argument("--check", action="store_true", help="fail closed on drift")
    modes.add_argument("--report", action="store_true", help="print the analysis")
    args = parser.parse_args()

    try:
        project_graph = read_project_graph(Path(args.project_graph))
        graph = RuntimeGraph(RUNTIME_SOURCES)
    except (OSError, ValueError, KeyError, TypeError) as error:
        print(f"dependency inventory: {error}", file=sys.stderr)
        return 2
    inventory = build(project_graph, graph)

    if args.report:
        report(project_graph, graph)
        return 0

    if args.write:
        INVENTORY.parent.mkdir(parents=True, exist_ok=True)
        INVENTORY.write_text(
            json.dumps(inventory, indent=2, sort_keys=True) + "\n", encoding="utf-8"
        )
        print(
            "dependency inventory written: "
            f"{len(inventory['modules'])} modules, "
            f"{len(inventory['runtime']['topPackages'])} runtime top packages, "
            f"{len(inventory['runtime']['packageCycles'])} runtime package cycles"
        )
        return 0

    if not INVENTORY.is_file():
        print(f"dependency inventory missing: {INVENTORY}", file=sys.stderr)
        return 1
    committed = json.loads(INVENTORY.read_text(encoding="utf-8"))
    if committed.get("format") != FORMAT:
        print(f"unexpected inventory format: {committed.get('format')!r}", file=sys.stderr)
        return 1
    drift = describe_drift(committed, inventory)
    if drift:
        print("dependency inventory drift (review and re-run with --write):", file=sys.stderr)
        for message in drift:
            print(f"  {message}", file=sys.stderr)
        return 1
    print(
        "dependency inventory: up to date "
        f"({len(committed['modules'])} modules, "
        f"{len(committed['runtime']['packageCycles'])} runtime package cycles)"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
