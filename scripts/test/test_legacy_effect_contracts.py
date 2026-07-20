#!/usr/bin/env python3
from __future__ import annotations

import csv
import re
import sys
from collections import Counter, defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
LEDGER = ROOT / "docs/migration/legacy-user-effects.tsv"
CLUSTERS = ROOT / "docs/migration/effect-clusters.tsv"
INDEX = ROOT / "docs/migration/effect-contracts/index.tsv"
SCENARIOS = ROOT / "docs/migration/effect-contracts/scenarios.tsv"
CATALOG = ROOT / "docs/migration/capabilities/capability-catalog.tsv"

LEDGER_HEADERS = [
    "candidateId", "recordKind", "userGoal", "entrypoints", "observableOutcome",
    "productDisposition", "boundaryDisposition", "implementationStrategy",
    "effectClusterId", "targetPluginId", "legacyEvidence", "oracleEvidence",
    "oracleStatus", "effectContract", "requiredCapabilities", "riskTags",
    "existingLedgerRelation", "censusFinding",
]
INDEX_HEADERS = [
    "effectId", "contractPath", "contractVersion", "contractStatus",
    "effectClusterId", "targetPluginId", "oracleStatus", "exactHostBaseline",
    "modelMutation", "undoPolicy", "requiredOperationIds",
    "b1AssetDisposition", "intentionalDifferenceSummary",
]
SCENARIO_HEADERS = [
    "effectId", "scenarioId", "scenarioKind", "given", "when",
    "thenResultCode", "thenVisibleResult", "stateInvariant", "undoInvariant",
    "oracleBasis",
]
CLUSTER_HEADERS = [
    "effectClusterId", "clusterKind", "targetPluginId", "memberEffectIds",
    "clusterDisposition", "deliveryGate", "sharedUserWorkflow",
    "permissionRiskEnvelope", "hostDependencyEnvelope", "forcedSplitRationale",
    "scaffoldPlan", "stability",
]
CATALOG_HEADERS = [
    "capabilityId", "category", "sdkSurface", "runtimeOwner", "adapterOwner",
    "permissions", "requiresTransaction", "requiresHook", "requiresMapping",
    "threadingBudget", "fakeHostFixture", "diagnostics", "legacyRows", "status",
]
REQUIRED_MARKERS = [
    "EFFECT_CONTRACT_VERSION: 1",
    "STATUS: READY_FOR_CAPABILITY_DESIGN",
    "IMPLEMENTATION_STRATEGY: BEHAVIOR_EQUIVALENT_REIMPLEMENTATION",
    "## User goal",
    "## Entry and preconditions",
    "## Inputs and visible outputs",
    "## Normal flow",
    "## Edge flow",
    "## Failure semantics",
    "## Model write and Undo",
    "## Required host-semantic operations",
    "## Executable acceptance scenarios",
    "## Legacy Oracle and evidence",
    "## B1/scaffold asset review",
    "## Intentional differences",
    "## Compliance and implementation boundary",
]
ALLOWED_SCENARIO_KINDS = {"NORMAL", "EDGE", "FAILURE", "UNDO", "LIFECYCLE"}
OPERATION_ID = re.compile(r"^[a-z][a-z0-9]*(?:[.-][a-z0-9]+)+$")


def fail(message: str) -> None:
    print(f"FAIL: {message}", file=sys.stderr)
    raise SystemExit(1)


def read_tsv(path: Path, headers: list[str]) -> list[dict[str, str]]:
    if not path.is_file():
        fail(f"missing {path.relative_to(ROOT)}")
    with path.open(encoding="utf-8", newline="") as handle:
        reader = csv.DictReader(handle, delimiter="\t")
        actual = list(reader.fieldnames or [])
        rows = list(reader)
    if actual != headers:
        fail(f"{path.relative_to(ROOT)} headers differ: {actual}")
    return rows


def unique(rows: list[dict[str, str]], key: str, label: str) -> dict[str, dict[str, str]]:
    result: dict[str, dict[str, str]] = {}
    for row in rows:
        value = row[key]
        if not value:
            fail(f"{label}: blank {key}")
        if value in result:
            fail(f"{label}: duplicate {key} {value}")
        result[value] = row
    return result


def split_ids(value: str) -> list[str]:
    return [item for item in value.split(";") if item]


def main() -> None:
    ledger_rows = read_tsv(LEDGER, LEDGER_HEADERS)
    cluster_rows = read_tsv(CLUSTERS, CLUSTER_HEADERS)
    index_rows = read_tsv(INDEX, INDEX_HEADERS)
    scenario_rows = read_tsv(SCENARIOS, SCENARIO_HEADERS)
    catalog_rows = read_tsv(CATALOG, CATALOG_HEADERS)

    ledger = unique(ledger_rows, "candidateId", "ledger")
    clusters = unique(cluster_rows, "effectClusterId", "clusters")
    index = unique(index_rows, "effectId", "contract index")
    scenarios = unique(scenario_rows, "scenarioId", "scenarios")
    catalog_ids = {row["capabilityId"] for row in catalog_rows}

    expected = {
        row["candidateId"] for row in ledger_rows
        if row["recordKind"] == "USER_EFFECT" and row["productDisposition"] == "MIGRATE"
    }
    if len(expected) != 20 or set(index) != expected:
        fail(f"contract index must cover exact 20 MIGRATE User Effects: missing={sorted(expected-set(index))} extra={sorted(set(index)-expected)}")

    scenarios_by_effect: dict[str, list[dict[str, str]]] = defaultdict(list)
    for scenario_id, row in scenarios.items():
        effect_id = row["effectId"]
        if effect_id not in expected:
            fail(f"{scenario_id}: scenario references non-contract effect {effect_id}")
        if not scenario_id.startswith(effect_id + "."):
            fail(f"{scenario_id}: scenarioId must be namespaced by effectId")
        if row["scenarioKind"] not in ALLOWED_SCENARIO_KINDS:
            fail(f"{scenario_id}: invalid scenarioKind {row['scenarioKind']}")
        for field in SCENARIO_HEADERS[2:]:
            if not row[field]:
                fail(f"{scenario_id}: blank {field}")
        scenarios_by_effect[effect_id].append(row)

    demanded_operations: set[str] = set()
    missing_catalog_demands: set[str] = set()
    for effect_id in sorted(expected):
        row = index[effect_id]
        ledger_row = ledger[effect_id]
        if row["contractVersion"] != "1" or row["contractStatus"] != "READY_FOR_CAPABILITY_DESIGN":
            fail(f"{effect_id}: invalid contract version/status")
        if row["contractPath"] != ledger_row["effectContract"]:
            fail(f"{effect_id}: ledger/index contract path mismatch")
        if row["effectClusterId"] != ledger_row["effectClusterId"] or row["targetPluginId"] != ledger_row["targetPluginId"]:
            fail(f"{effect_id}: contract cluster/plugin mismatch")
        if row["effectClusterId"] not in clusters:
            fail(f"{effect_id}: unknown effect cluster {row['effectClusterId']}")
        if row["oracleStatus"] != ledger_row["oracleStatus"] or row["oracleStatus"] not in {"CONFIRMED", "PARTIAL"}:
            fail(f"{effect_id}: contract Oracle status mismatch")
        if row["exactHostBaseline"] != "Cubism Editor 5.3.02 exact artifact":
            fail(f"{effect_id}: wrong exact host baseline")
        if not row["b1AssetDisposition"] or not row["intentionalDifferenceSummary"]:
            fail(f"{effect_id}: asset disposition and intentional differences are mandatory")

        operations = split_ids(row["requiredOperationIds"])
        if not operations or len(operations) != len(set(operations)):
            fail(f"{effect_id}: operation IDs must be non-empty and unique")
        for operation in operations:
            if not OPERATION_ID.fullmatch(operation):
                fail(f"{effect_id}: invalid operation ID {operation}")
            demanded_operations.add(operation)
            if operation not in catalog_ids:
                missing_catalog_demands.add(operation)

        contract_path = ROOT / row["contractPath"]
        if not contract_path.is_file():
            fail(f"{effect_id}: missing contract file {row['contractPath']}")
        text = contract_path.read_text(encoding="utf-8")
        for marker in REQUIRED_MARKERS:
            if marker not in text:
                fail(f"{effect_id}: contract missing marker {marker}")
        for value in (effect_id, row["effectClusterId"], row["targetPluginId"], row["exactHostBaseline"]):
            if value not in text:
                fail(f"{effect_id}: contract text missing metadata value {value}")
        for operation in operations:
            if f"`{operation}`" not in text:
                fail(f"{effect_id}: contract text missing demanded operation {operation}")
        for evidence in split_ids(ledger_row["oracleEvidence"]):
            if f"`{evidence}`" not in text:
                fail(f"{effect_id}: contract text missing Oracle evidence {evidence}")

        effect_scenarios = scenarios_by_effect[effect_id]
        kinds = {scenario["scenarioKind"] for scenario in effect_scenarios}
        required_kinds = {"NORMAL", "EDGE", "FAILURE", "LIFECYCLE"}
        if row["modelMutation"] == "MODEL":
            required_kinds.add("UNDO")
        missing = required_kinds - kinds
        if missing:
            fail(f"{effect_id}: scenarios missing kinds {sorted(missing)}")
        for scenario in effect_scenarios:
            if f"`{scenario['scenarioId']}`" not in text:
                fail(f"{effect_id}: contract text missing scenario {scenario['scenarioId']}")
        cleanup_id = effect_id + ".disable-cleanup"
        if cleanup_id not in scenarios:
            fail(f"{effect_id}: missing disable-cleanup lifecycle scenario")

    for row in ledger_rows:
        if row["productDisposition"] == "MIGRATE" and row["recordKind"] != "USER_EFFECT" and row["effectContract"] != "NOT_REQUIRED":
            fail(f"{row['candidateId']}: non-User-Effect MIGRATE surface must not claim an Effect Contract")

    print(
        "PASS: legacy Effect Contracts; "
        f"effects={len(index_rows)} scenarios={len(scenario_rows)} "
        f"demandOperations={len(demanded_operations)} missingCatalogDemands={len(missing_catalog_demands)}"
    )
    if missing_catalog_demands:
        print("INFO: demanded operation IDs absent from current catalog: " + ";".join(sorted(missing_catalog_demands)))


if __name__ == "__main__":
    main()
