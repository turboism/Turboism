#!/usr/bin/env python3
"""Validate and report the Turboism Axiomatic Design functional matrix (#335).

Structural dependency gates (`checkModuleBoundaries`, `checkDependencyInventory`)
prove static compile/import boundaries only. This tool validates the separate
functional-effect matrix (`docs/architecture/design-matrix.json`) against its JSON
Schema (`docs/architecture/design-matrix.schema.json`), verifies repository path
and inventory alignment, enforces evidence/verification-task rules on nonzero and
unknown cells, and recomputes diagonal/triangular classifications and Tarjan SCCs.

Modes (exactly one):
  --check    fail closed when schema, evidence paths, inventory facts, or matrix
             classifications drift
  --report   print a human-readable summary of FR/DP pairs, classifications, SCCs,
             unknowns, and required sequencing without modifying files
"""

from __future__ import annotations

import argparse
import collections
import json
import math
import re
import sys
from pathlib import Path, PurePosixPath
from typing import Any

ROOT = Path(__file__).resolve().parents[2]
DEFAULT_MATRIX = Path("docs/architecture/design-matrix.json")
DEFAULT_SCHEMA = Path("docs/architecture/design-matrix.schema.json")
DEFAULT_DOC = Path("docs/architecture/AXIOMATIC_DESIGN.md")
DEFAULT_INVENTORY = Path("docs/architecture/dependency-inventory.json")
DEFAULT_SETTINGS = Path("settings.gradle.kts")

MATRIX_FORMAT = "turboism-axiomatic-design-matrix-v1"
INVENTORY_FORMAT = "turboism-dependency-inventory-v1"
SYMBOL_DIAGONAL = "X"
SYMBOL_EFFECT = "x"
SYMBOL_ZERO = "0"
SYMBOL_UNKNOWN = "?"
CLASSIFICATION_UNCOUPLED = "UNCOUPLED"
CLASSIFICATION_DECOUPLED = "DECOUPLED"
CLASSIFICATION_COUPLED = "COUPLED"
CLASSIFICATION_UNVERIFIED = "UNVERIFIED"
SUH_REFERENCE_ALPHA = 0.25
SUH_DIAGONAL_WEIGHT = 1.0
SUH_ZERO_WEIGHT = 0.0
PRODUCTION_CONFIGURATIONS = frozenset(
    {
        "api",
        "implementation",
        "compileOnly",
        "compileOnlyApi",
        "runtimeOnly",
        "annotationProcessor",
    }
)
SETTINGS_INCLUDE_PATTERN = re.compile(r'"([a-z0-9:-]+)"')
PLUGIN_PREFIX = ":plugins:"


def validate_json_schema(instance: Any, schema: dict[str, Any], location: str = "$") -> list[str]:
    """Validate JSON values against the deterministic Draft 2020-12 subset used by repository schemas."""
    errors: list[str] = []
    if "const" in schema and instance != schema["const"]:
        errors.append(f"{location}: expected const {schema['const']!r}, got {instance!r}")
        return errors
    if "enum" in schema and instance not in schema["enum"]:
        errors.append(f"{location}: value {instance!r} not in {schema['enum']!r}")
        return errors

    expected_type = schema.get("type")
    if expected_type == "object":
        if not isinstance(instance, dict):
            return [f"{location}: expected object, got {type(instance).__name__}"]
        min_props = schema.get("minProperties", 0)
        if len(instance) < min_props:
            errors.append(f"{location}: expected at least {min_props} properties")
        required = schema.get("required", [])
        for key in required:
            if key not in instance:
                errors.append(f"{location}: missing required property {key!r}")
        props = schema.get("properties", {})
        additional = schema.get("additionalProperties", True)
        for key, value in instance.items():
            child_loc = f"{location}.{key}"
            if key in props:
                errors.extend(validate_json_schema(value, props[key], child_loc))
            elif additional is False:
                errors.append(f"{location}: unexpected property {key!r}")
            elif isinstance(additional, dict):
                errors.extend(validate_json_schema(value, additional, child_loc))
    elif expected_type == "array":
        if not isinstance(instance, list):
            return [f"{location}: expected array, got {type(instance).__name__}"]
        min_items = schema.get("minItems", 0)
        if len(instance) < min_items:
            errors.append(f"{location}: expected at least {min_items} items, got {len(instance)}")
        item_schema = schema.get("items")
        if isinstance(item_schema, dict):
            for idx, item in enumerate(instance):
                errors.extend(validate_json_schema(item, item_schema, f"{location}[{idx}]"))
    elif expected_type == "string":
        if not isinstance(instance, str):
            return [f"{location}: expected string, got {type(instance).__name__}"]
        min_len = schema.get("minLength", 0)
        if len(instance.strip()) < min_len:
            errors.append(f"{location}: string shorter than minLength {min_len}")
        pattern = schema.get("pattern")
        if pattern and not re.fullmatch(pattern, instance):
            errors.append(f"{location}: {instance!r} does not match /{pattern}/")
    elif expected_type == "integer":
        if isinstance(instance, bool) or not isinstance(instance, int):
            return [f"{location}: expected integer, got {type(instance).__name__}"]
        minimum = schema.get("minimum")
        if minimum is not None and instance < minimum:
            errors.append(f"{location}: {instance} < minimum {minimum}")
    elif expected_type == "boolean":
        if not isinstance(instance, bool):
            return [f"{location}: expected boolean, got {type(instance).__name__}"]
    return errors


def tarjan_sccs(nodes: list[str], adjacency: dict[str, set[str]]) -> list[list[str]]:
    """Compute multi-node strongly connected components in deterministic node order."""
    order_index = {node: idx for idx, node in enumerate(nodes)}
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
        for target in sorted(adjacency.get(node, ()), key=lambda item: order_index[item]):
            if target not in index:
                visit(target)
                low[node] = min(low[node], low[target])
            elif target in on_stack:
                low[node] = min(low[node], index[target])
        if low[node] == index[node]:
            member_list: list[str] = []
            while True:
                member = stack.pop()
                on_stack.discard(member)
                member_list.append(member)
                if member == node:
                    break
            if len(member_list) > 1:
                components.append(sorted(member_list, key=lambda item: order_index[item]))

    for node in nodes:
        if node not in index:
            visit(node)
    return sorted(components, key=lambda comp: [order_index[item] for item in comp])


def verify_repo_path(root: Path, relative: str, context: str) -> list[str]:
    """Ensure a referenced path stays inside the repository without symlinks and exists on disk."""
    parts = PurePosixPath(relative).parts
    if (
        not parts
        or relative.startswith("/")
        or "\\" in relative
        or any(part in {".", ".."} for part in parts)
    ):
        return [f"{context}: unsafe relative path {relative!r}"]
    candidate = root.joinpath(*parts)
    if candidate.is_symlink():
        return [f"{context}: symlink path forbidden {relative!r}"]
    resolved_root = root.resolve()
    try:
        candidate.resolve().relative_to(resolved_root)
    except ValueError:
        return [f"{context}: path escapes repository root {relative!r}"]
    if not candidate.exists():
        return [f"{context}: referenced path does not exist: {relative}"]
    return []


def parse_included_modules(settings_path: Path) -> set[str]:
    """Extract canonical `:module` paths declared in settings.gradle.kts."""
    text = settings_path.read_text(encoding="utf-8")
    include_start = text.find("include(")
    if include_start == -1:
        return set()
    include_end = text.find(")", include_start)
    block = text[include_start:include_end] if include_end != -1 else text[include_start:]
    return {":" + match.lstrip(":") for match in SETTINGS_INCLUDE_PATTERN.findall(block)}


def classify_square_grid(grid: list[list[str]], labels: list[str]) -> tuple[str, str, list[list[str]], list[list[str]], dict[str, int]]:
    """Compute observed and overall AD classifications plus confirmed and unknown-inclusive SCCs."""
    size = len(labels)
    counts = {
        "diagonal": 0,
        "lower_effect": 0,
        "upper_effect": 0,
        "lower_unknown": 0,
        "upper_unknown": 0,
    }
    observed_adj: dict[str, set[str]] = collections.defaultdict(set)
    unknown_adj: dict[str, set[str]] = collections.defaultdict(set)
    for row_idx in range(size):
        for col_idx in range(size):
            cell = grid[row_idx][col_idx]
            if row_idx == col_idx:
                if cell == SYMBOL_DIAGONAL:
                    counts["diagonal"] += 1
                continue
            if cell == SYMBOL_EFFECT:
                observed_adj[labels[col_idx]].add(labels[row_idx])
                unknown_adj[labels[col_idx]].add(labels[row_idx])
                if row_idx > col_idx:
                    counts["lower_effect"] += 1
                else:
                    counts["upper_effect"] += 1
            elif cell == SYMBOL_UNKNOWN:
                unknown_adj[labels[col_idx]].add(labels[row_idx])
                if row_idx > col_idx:
                    counts["lower_unknown"] += 1
                else:
                    counts["upper_unknown"] += 1

    observed_sccs = tarjan_sccs(labels, observed_adj)
    potential_sccs = tarjan_sccs(labels, unknown_adj)
    if observed_sccs or counts["upper_effect"] > 0:
        observed_class = CLASSIFICATION_COUPLED
    elif counts["lower_effect"] == 0:
        observed_class = CLASSIFICATION_UNCOUPLED
    else:
        observed_class = CLASSIFICATION_DECOUPLED

    total_unknowns = counts["lower_unknown"] + counts["upper_unknown"]
    if observed_class == CLASSIFICATION_COUPLED:
        overall_class = CLASSIFICATION_COUPLED
    elif total_unknowns > 0:
        overall_class = CLASSIFICATION_UNVERIFIED
    else:
        overall_class = observed_class

    return observed_class, overall_class, observed_sccs, potential_sccs, counts


def compute_suh_metrics(
    square_grid: list[list[str]],
    alpha: float = SUH_REFERENCE_ALPHA,
    include_unknowns: bool = False,
) -> dict[str, float]:
    """Compute off-diagonal density, Reangularity (R), and Semangularity (S) at reference sensitivity alpha."""
    size = len(square_grid)
    if size < 2:
        return {"density": 0.0, "reangularity": 1.0, "semangularity": 1.0}

    numeric: list[list[float]] = []
    off_diagonal_nonzero = 0
    for row_idx in range(size):
        row_values: list[float] = []
        for col_idx in range(size):
            cell = square_grid[row_idx][col_idx]
            if row_idx == col_idx:
                row_values.append(SUH_DIAGONAL_WEIGHT if cell == SYMBOL_DIAGONAL else SUH_ZERO_WEIGHT)
            elif cell == SYMBOL_EFFECT or (include_unknowns and cell == SYMBOL_UNKNOWN):
                row_values.append(alpha)
                off_diagonal_nonzero += 1
            else:
                row_values.append(SUH_ZERO_WEIGHT)
        numeric.append(row_values)

    density = off_diagonal_nonzero / (size * (size - 1))
    col_norms_sq = [
        sum(numeric[row_idx][col_idx] ** 2 for row_idx in range(size))
        for col_idx in range(size)
    ]

    semangularity = 1.0
    for col_idx in range(size):
        norm = math.sqrt(col_norms_sq[col_idx])
        semangularity *= abs(numeric[col_idx][col_idx]) / norm if norm > 0.0 else 0.0

    reangularity = 1.0
    for col_a in range(size - 1):
        for col_b in range(col_a + 1, size):
            denom = col_norms_sq[col_a] * col_norms_sq[col_b]
            if denom <= 0.0:
                reangularity = 0.0
                continue
            dot = sum(numeric[row_idx][col_a] * numeric[row_idx][col_b] for row_idx in range(size))
            cos_sq = min(1.0, (dot * dot) / denom)
            reangularity *= math.sqrt(max(0.0, 1.0 - cos_sq))

    return {
        "density": density,
        "reangularity": reangularity,
        "semangularity": semangularity,
    }


def check_structural_baseline(
    root: Path, structural: dict[str, Any], included_modules: set[str]
) -> list[str]:
    """Reconcile structuralBaseline numbers against dependency-inventory.json and settings.gradle.kts."""
    errors: list[str] = []
    inv_rel = structural["inventoryPath"]
    errors.extend(verify_repo_path(root, inv_rel, "structuralBaseline.inventoryPath"))
    if errors:
        return errors
    inventory = json.loads((root / inv_rel).read_text(encoding="utf-8"))
    if inventory.get("format") != INVENTORY_FORMAT:
        return [f"structuralBaseline: unexpected inventory format {inventory.get('format')!r}"]

    modules = inventory["modules"]
    graph_nodes = set(modules.keys()) | {
        dep for cfgs in modules.values() for deps in cfgs.values() for dep in deps
    }
    if included_modules and graph_nodes != included_modules:
        errors.append(
            f"structuralBaseline: inventory nodes {len(graph_nodes)} disagree with settings.gradle.kts {len(included_modules)}"
        )
    plugins = [node for node in graph_nodes if node.startswith(PLUGIN_PREFIX)]
    non_test_edges = [
        (src, cfg, dst)
        for src, cfgs in modules.items()
        for cfg, dsts in cfgs.items()
        if cfg in PRODUCTION_CONFIGURATIONS
        for dst in dsts
    ]
    test_edges = [
        (src, cfg, dst)
        for src, cfgs in modules.items()
        for cfg, dsts in cfgs.items()
        if cfg not in PRODUCTION_CONFIGURATIONS
        for dst in dsts
    ]
    prod_adj: dict[str, set[str]] = collections.defaultdict(set)
    for src, _, dst in non_test_edges:
        if src != dst:
            prod_adj[src].add(dst)
    prod_cycles = tarjan_sccs(sorted(graph_nodes), prod_adj)
    plugin_prod_edges = [
        edge for edge in non_test_edges if edge[0].startswith(PLUGIN_PREFIX) and edge[2].startswith(PLUGIN_PREFIX)
    ]
    runtime = inventory["runtime"]

    expected = {
        "gradleGraphNodes": len(graph_nodes),
        "pluginModules": len(plugins),
        "nonTestEdges": len(non_test_edges),
        "testOnlyEdges": len(test_edges),
        "nonTestModuleCycles": len(prod_cycles),
        "directProductionPluginEdges": len(plugin_prod_edges),
        "runtimeTopPackages": len(runtime["topPackages"]),
        "runtimeTopPackageEdges": len(runtime["topPackageEdges"]),
        "runtimeTopPackageCycles": len(runtime["topPackageCycles"]),
        "runtimeLeafPackageCycles": len(runtime["packageCycles"]),
    }
    for key, actual_val in expected.items():
        if structural.get(key) != actual_val:
            errors.append(
                f"structuralBaseline.{key}: recorded {structural.get(key)}, inventory has {actual_val}"
            )
    return errors


def validate_repository_matrix(
    root: Path,
    matrix_rel: Path = DEFAULT_MATRIX,
    schema_rel: Path = DEFAULT_SCHEMA,
    doc_rel: Path = DEFAULT_DOC,
    settings_rel: Path = DEFAULT_SETTINGS,
) -> tuple[dict[str, Any] | None, list[str]]:
    """Run full schema, referential, path, inventory, and algebraic validation."""
    errors: list[str] = []
    for rel, label in ((schema_rel, "schema"), (matrix_rel, "matrix"), (doc_rel, "doc"), (settings_rel, "settings")):
        if not (root / rel).is_file():
            errors.append(f"missing required {label} file: {rel.as_posix()}")
    if errors:
        return None, errors

    try:
        schema = json.loads((root / schema_rel).read_text(encoding="utf-8"))
        matrix = json.loads((root / matrix_rel).read_text(encoding="utf-8"))
    except json.JSONDecodeError as exc:
        return None, [f"invalid JSON: {exc}"]

    schema_errors = validate_json_schema(matrix, schema)
    if schema_errors:
        return matrix, schema_errors

    included_modules = parse_included_modules(root / settings_rel)
    errors.extend(check_structural_baseline(root, matrix["structuralBaseline"], included_modules))

    constraint_ids: set[str] = set()
    for constraint in matrix["constraints"]:
        cid = constraint["id"]
        if cid in constraint_ids:
            errors.append(f"duplicate constraint id: {cid}")
        constraint_ids.add(cid)
        for path in constraint["codePaths"]:
            errors.extend(verify_repo_path(root, path, f"constraint {cid}"))

    domains = {domain["id"]: domain for domain in matrix["domains"]}
    if len(domains) != len(matrix["domains"]):
        errors.append("duplicate domain id in domains")

    requirements = {req["id"]: req for req in matrix["requirements"]}
    if len(requirements) != len(matrix["requirements"]):
        errors.append("duplicate requirement id in requirements")

    domain_fr_refs: list[str] = []
    for did, domain in domains.items():
        for fr_id in domain["requirementIds"]:
            domain_fr_refs.append(fr_id)
            if fr_id not in requirements:
                errors.append(f"domain {did} references unknown {fr_id}")
            elif requirements[fr_id]["domainId"] != did:
                errors.append(f"{fr_id} declares domainId {requirements[fr_id]['domainId']}, listed under {did}")
    if sorted(domain_fr_refs) != sorted(requirements.keys()):
        errors.append("domains.requirementIds must partition requirements exactly once")

    dps = {dp["id"]: dp for dp in matrix["designParameters"]}
    if len(dps) != len(matrix["designParameters"]):
        errors.append("duplicate designParameter id in designParameters")
    if len(dps) != len(requirements):
        errors.append(f"Independence Axiom baseline requires equal FR and DP counts ({len(requirements)} vs {len(dps)})")

    satisfied_frs: set[str] = set()
    for dp_id, dp in dps.items():
        fr_id = dp["satisfiesFrId"]
        if fr_id not in requirements:
            errors.append(f"{dp_id} satisfies unknown {fr_id}")
        if fr_id in satisfied_frs:
            errors.append(f"multiple DPs claim primary satisfaction of {fr_id}")
        satisfied_frs.add(fr_id)
        for mod in dp["modules"]:
            if included_modules and mod not in included_modules:
                errors.append(f"{dp_id} references unknown Gradle module {mod}")
        for path in dp["codePaths"]:
            errors.extend(verify_repo_path(root, path, f"designParameter {dp_id}"))

    ordering = matrix["ordering"]
    size = len(requirements)
    if len(ordering) != size:
        errors.append(f"ordering length {len(ordering)} must equal FR count {size}")
    expected_indices = list(range(1, size + 1))
    if [item["index"] for item in ordering] != expected_indices:
        errors.append(f"ordering indices must be contiguous 1..{size}")

    ordered_frs = [item["frId"] for item in ordering]
    ordered_dps = [item["dpId"] for item in ordering]
    if set(ordered_frs) != set(requirements.keys()):
        errors.append("ordering.frId must cover every declared FR once")
    if set(ordered_dps) != set(dps.keys()):
        errors.append("ordering.dpId must cover every declared DP once")
    for item in ordering:
        dp = dps.get(item["dpId"])
        if dp and dp["satisfiesFrId"] != item["frId"]:
            errors.append(f"ordering index {item['index']} pairs {item['frId']} with {item['dpId']} (satisfies {dp['satisfiesFrId']})")

    if errors:
        return matrix, errors

    grid_obj = matrix["grid"]
    if set(grid_obj.keys()) != set(ordered_frs):
        errors.append("grid keys must match ordering.frId exactly")
        return matrix, errors

    square_grid: list[list[str]] = []
    for row_idx, fr_id in enumerate(ordered_frs):
        row = grid_obj[fr_id]
        if len(row) != size:
            errors.append(f"grid[{fr_id}] has length {len(row)}, expected {size}")
            return matrix, errors
        for col_idx, cell in enumerate(row):
            if row_idx == col_idx and cell != SYMBOL_DIAGONAL:
                errors.append(f"diagonal cell ({fr_id}, {ordered_dps[col_idx]}) must be 'X', got {cell!r}")
            elif row_idx != col_idx and cell == SYMBOL_DIAGONAL:
                errors.append(f"off-diagonal cell ({fr_id}, {ordered_dps[col_idx]}) must not use 'X'")
        square_grid.append(row)

    fr_pos = {fr_id: idx for idx, fr_id in enumerate(ordered_frs)}
    dp_pos = {dp_id: idx for idx, dp_id in enumerate(ordered_dps)}
    entry_map: dict[tuple[str, str], dict[str, Any]] = {}
    previous_sort_key = (-1, -1)
    for entry in matrix["entries"]:
        key = (entry["frId"], entry["dpId"])
        if key[0] not in fr_pos or key[1] not in dp_pos:
            errors.append(f"entry {key} references unknown FR or DP")
            continue
        sort_key = (fr_pos[key[0]], dp_pos[key[1]])
        if sort_key <= previous_sort_key:
            errors.append(f"entries must be strictly sorted by ordering index; out-of-order at {key}")
        previous_sort_key = sort_key
        if key in entry_map:
            errors.append(f"duplicate matrix entry for {key}")
            continue
        entry_map[key] = entry
        for path in entry["evidencePaths"]:
            errors.extend(verify_repo_path(root, path, f"entry {key[0]}x{key[1]}"))

        symbol = entry["symbol"]
        relation = entry["relation"]
        status = entry["evidenceStatus"]
        has_task = bool(entry.get("verificationTask"))
        if symbol == SYMBOL_DIAGONAL:
            if relation != "PRIMARY" or status != "OBSERVED":
                errors.append(f"diagonal entry {key} must have relation=PRIMARY and evidenceStatus=OBSERVED")
        elif symbol == SYMBOL_EFFECT:
            if relation != "AFFECTS" or status not in {"OBSERVED", "HYPOTHESIS"}:
                errors.append(f"effect entry {key} must have relation=AFFECTS and status OBSERVED|HYPOTHESIS")
            if status != "OBSERVED" and not has_task:
                errors.append(f"hypothesized effect entry {key} requires verificationTask")
        elif symbol == SYMBOL_UNKNOWN:
            if relation != "UNKNOWN" or status not in {"HYPOTHESIS", "UNVERIFIED"}:
                errors.append(f"unknown entry {key} must have relation=UNKNOWN and status HYPOTHESIS|UNVERIFIED")
            if not has_task:
                errors.append(f"unknown entry {key} requires verificationTask")

    lower_obs = lower_hyp = upper_obs = upper_hyp = 0
    for row_idx, fr_id in enumerate(ordered_frs):
        for col_idx, dp_id in enumerate(ordered_dps):
            cell = square_grid[row_idx][col_idx]
            key = (fr_id, dp_id)
            if cell == SYMBOL_ZERO:
                if key in entry_map:
                    errors.append(f"grid cell {key} is '0' but has an entry record")
            else:
                if key not in entry_map:
                    errors.append(f"grid cell {key} is {cell!r} but has no evidence entry")
                elif entry_map[key]["symbol"] != cell:
                    errors.append(f"grid cell {key} is {cell!r} but entry symbol is {entry_map[key]['symbol']!r}")
                elif cell == SYMBOL_EFFECT:
                    is_obs = entry_map[key]["evidenceStatus"] == "OBSERVED"
                    if row_idx > col_idx:
                        if is_obs:
                            lower_obs += 1
                        else:
                            lower_hyp += 1
                    else:
                        if is_obs:
                            upper_obs += 1
                        else:
                            upper_hyp += 1

    pair_labels = [f"{fr}/{dp}" for fr, dp in zip(ordered_frs, ordered_dps)]
    obs_class, overall_class, obs_sccs, pot_sccs, counts = classify_square_grid(square_grid, pair_labels)
    analysis = matrix["analysis"]
    expected_analysis = {
        "observedClassification": obs_class,
        "overallClassification": overall_class,
        "diagonalCount": counts["diagonal"],
        "lowerTriangularObservedCount": lower_obs,
        "lowerTriangularHypothesisCount": lower_hyp,
        "upperTriangularObservedCount": upper_obs,
        "upperTriangularHypothesisCount": upper_hyp,
        "lowerTriangularUnknownCount": counts["lower_unknown"],
        "upperTriangularUnknownCount": counts["upper_unknown"],
        "observedFunctionalSccs": obs_sccs,
        "potentialFunctionalSccsWithUnknowns": pot_sccs,
    }
    for field, computed in expected_analysis.items():
        if analysis.get(field) != computed:
            errors.append(f"analysis.{field}: recorded {analysis.get(field)!r}, computed {computed!r}")

    seq_steps = [step["step"] for step in analysis["triangularSequencing"]]
    if seq_steps != list(range(1, len(seq_steps) + 1)):
        errors.append("analysis.triangularSequencing steps must be contiguous starting at 1")

    sub_ids: set[str] = set()
    for sub in matrix["submatrices"]:
        sid = sub["id"]
        if sid in sub_ids:
            errors.append(f"duplicate submatrix id: {sid}")
        sub_ids.add(sid)
        axes = sub["axes"]
        sub_grid = sub["grid"]
        if len(sub_grid) != len(axes) or any(len(r) != len(axes) for r in sub_grid):
            errors.append(f"submatrix {sid} grid must be {len(axes)}x{len(axes)}")
            continue
        for idx in range(len(axes)):
            if sub_grid[idx][idx] != SYMBOL_DIAGONAL:
                errors.append(f"submatrix {sid} diagonal [{idx}][{idx}] must be 'X'")
        _, sub_overall, _, _, _ = classify_square_grid(sub_grid, axes)
        if sub["classification"] != sub_overall:
            errors.append(
                f"submatrix {sid} classification: recorded {sub['classification']!r}, computed {sub_overall!r}"
            )
        for path in sub["evidencePaths"]:
            errors.extend(verify_repo_path(root, path, f"submatrix {sid}"))

    pvs: dict[str, dict[str, Any]] = {}
    pv_pos: dict[str, int] = {}
    for idx, pv in enumerate(matrix["processVariables"]):
        pvid = pv["id"]
        if pvid in pvs:
            errors.append(f"duplicate processVariable id: {pvid}")
        pvs[pvid] = pv
        pv_pos[pvid] = idx
        for path in pv["codePaths"]:
            errors.extend(verify_repo_path(root, path, f"processVariable {pvid}"))

    seen_process_pairs: set[tuple[str, str]] = set()
    covered_dps: set[str] = set()
    covered_pvs: set[str] = set()
    previous_process_key = (-1, -1)
    for relation in matrix["processMatrix"]:
        dp_id = relation["dpId"]
        pv_id = relation["pvId"]
        if dp_id not in dp_pos:
            errors.append(f"processMatrix references unknown DP {dp_id}")
            continue
        if pv_id not in pv_pos:
            errors.append(f"processMatrix references unknown PV {pv_id}")
            continue
        pair = (dp_id, pv_id)
        if pair in seen_process_pairs:
            errors.append(f"duplicate processMatrix entry for {pair}")
            continue
        seen_process_pairs.add(pair)
        sort_key = (dp_pos[dp_id], pv_pos[pv_id])
        if sort_key <= previous_process_key:
            errors.append(f"processMatrix must be strictly sorted by (DP, PV) order; out-of-order at {pair}")
        previous_process_key = sort_key
        covered_dps.add(dp_id)
        covered_pvs.add(pv_id)

    missing_dps = [dp_id for dp_id in ordered_dps if dp_id not in covered_dps]
    if missing_dps:
        errors.append(f"processMatrix omits declared DPs: {', '.join(missing_dps)}")
    missing_pvs = [pv_id for pv_id in pvs if pv_id not in covered_pvs]
    if missing_pvs:
        errors.append(f"processMatrix omits declared PVs: {', '.join(missing_pvs)}")

    exc_ids: set[str] = set()
    for exc in matrix["exceptions"]:
        eid = exc["id"]
        if eid in exc_ids:
            errors.append(f"duplicate exception id: {eid}")
        exc_ids.add(eid)
        for path in exc["codePaths"]:
            errors.extend(verify_repo_path(root, path, f"exception {eid}"))

    doc_text = (root / doc_rel).read_text(encoding="utf-8")
    required_doc_tokens = [
        matrix_rel.name,
        *constraint_ids,
        *ordered_frs,
        *ordered_dps,
        *pvs.keys(),
        *exc_ids,
    ]
    for token in sorted(required_doc_tokens):
        if token not in doc_text:
            errors.append(f"{doc_rel.as_posix()} omits required identifier {token!r}")

    return matrix, errors


def print_report(matrix: dict[str, Any]) -> None:
    """Print human-readable functional independence, Suh metrics, and Process Domain analysis."""
    analysis = matrix["analysis"]
    ordered_frs = [item["frId"] for item in matrix["ordering"]]
    square_grid = [matrix["grid"][fr_id] for fr_id in ordered_frs]
    observed_metrics = compute_suh_metrics(square_grid, alpha=SUH_REFERENCE_ALPHA, include_unknowns=False)
    inclusive_metrics = compute_suh_metrics(square_grid, alpha=SUH_REFERENCE_ALPHA, include_unknowns=True)

    print(f"== Axiomatic Design Matrix ({matrix['format']}) ==")
    print(f"Baseline snapshot:        {matrix['baselineSnapshot']}")
    print(f"Observed classification:  {analysis['observedClassification']}")
    print(f"Overall classification:   {analysis['overallClassification']}")
    print(
        f"Diagonal: {analysis['diagonalCount']} | "
        f"Lower (observed/hypothesis/unknown): "
        f"{analysis['lowerTriangularObservedCount']}/"
        f"{analysis['lowerTriangularHypothesisCount']}/"
        f"{analysis['lowerTriangularUnknownCount']} | "
        f"Upper (observed/hypothesis/unknown): "
        f"{analysis['upperTriangularObservedCount']}/"
        f"{analysis['upperTriangularHypothesisCount']}/"
        f"{analysis['upperTriangularUnknownCount']}"
    )
    print(
        f"Suh metrics (alpha={SUH_REFERENCE_ALPHA:.2f}, observed): "
        f"density={observed_metrics['density']:.4f} | "
        f"R={observed_metrics['reangularity']:.4f} | "
        f"S={observed_metrics['semangularity']:.4f}"
    )
    print(
        f"Suh metrics (alpha={SUH_REFERENCE_ALPHA:.2f}, with '?'): "
        f"density={inclusive_metrics['density']:.4f} | "
        f"R={inclusive_metrics['reangularity']:.4f} | "
        f"S={inclusive_metrics['semangularity']:.4f}"
    )
    print(f"Observed functional SCCs: {len(analysis['observedFunctionalSccs'])}")
    print(f"Potential SCCs with '?':  {len(analysis['potentialFunctionalSccsWithUnknowns'])}")
    for scc in analysis["potentialFunctionalSccsWithUnknowns"]:
        print("  - " + " <-> ".join(scc))
    print("\n== Submatrices ==")
    for sub in matrix["submatrices"]:
        print(f"  {sub['id']}: {sub['classification']} ({', '.join(sub['axes'])})")
    print("\n== Process Domain ==")
    pv_relations: dict[str, list[str]] = collections.defaultdict(list)
    for rel in matrix["processMatrix"]:
        pv_relations[rel["pvId"]].append(f"{rel['dpId']}[{rel['symbol']}]")
    for pv in matrix["processVariables"]:
        mapped = pv_relations.get(pv["id"], [])
        print(f"  {pv['id']} ({pv['title']}): {len(mapped)} DPs -> {', '.join(mapped)}")
    print("\n== Unknown / Hypothesis Verification Tasks ==")
    for entry in matrix["entries"]:
        if "verificationTask" in entry:
            print(
                f"  ({entry['frId']}, {entry['dpId']}) [{entry['symbol']}/{entry['evidenceStatus']}]: "
                f"{entry['verificationTask']}"
            )


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--root", type=Path, default=ROOT, help="repository root directory")
    modes = parser.add_mutually_exclusive_group(required=True)
    modes.add_argument("--check", action="store_true", help="validate schema, paths, and AD classifications")
    modes.add_argument("--report", action="store_true", help="print summary report after validating")
    args = parser.parse_args(argv)

    matrix, errors = validate_repository_matrix(args.root.resolve())
    if errors:
        print("checkAxiomaticDesignMatrix FAILED:", file=sys.stderr)
        for err in errors:
            print(f"  - {err}", file=sys.stderr)
        return 1

    assert matrix is not None
    if args.report:
        print_report(matrix)
        return 0

    analysis = matrix["analysis"]
    unknown_total = analysis["lowerTriangularUnknownCount"] + analysis["upperTriangularUnknownCount"]
    print(
        "checkAxiomaticDesignMatrix: OK "
        f"({analysis['diagonalCount']} FR/DP pairs, "
        f"observed={analysis['observedClassification']}, "
        f"overall={analysis['overallClassification']}, "
        f"unknowns={unknown_total})"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
