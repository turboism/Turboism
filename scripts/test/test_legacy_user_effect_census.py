#!/usr/bin/env python3
from __future__ import annotations

import csv
import glob
import sys
from collections import Counter
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
LEDGER = ROOT / "docs/migration/legacy-user-effects.tsv"
CENSUS_ALIAS = ROOT / "docs/migration/legacy-user-effect-census.tsv"
COVERAGE = ROOT / "docs/migration/legacy-user-entry-coverage.tsv"
RECONCILIATION = ROOT / "docs/migration/legacy-user-effect-reconciliation.tsv"
LEGACY_SNAPSHOT = ROOT / "docs/migration/history/legacy-user-effects-pre-census.tsv"
EXTRACTION = ROOT / "docs/migration/capabilities/legacy-framework-capability-extraction.tsv"
EXTRACTION_MAP = ROOT / "docs/migration/legacy-framework-extraction-user-effect-map.tsv"
CLUSTERS = ROOT / "docs/migration/effect-clusters.tsv"
CAPABILITY_CATALOG = ROOT / "docs/migration/capabilities/capability-catalog.tsv"
WORKBOOK = ROOT / "docs/migration/legacy-user-effect-census-workbook.md"
ADR = ROOT / "docs/adr/0027-user-effect-led-legacy-migration.md"
LEGACY_ROOT = ROOT.parent / "turboism-legacy"

LEDGER_HEADERS = [
    "candidateId",
    "recordKind",
    "userGoal",
    "entrypoints",
    "observableOutcome",
    "productDisposition",
    "boundaryDisposition",
    "implementationStrategy",
    "effectClusterId",
    "targetPluginId",
    "legacyEvidence",
    "oracleEvidence",
    "oracleStatus",
    "effectContract",
    "requiredCapabilities",
    "riskTags",
    "existingLedgerRelation",
    "censusFinding",
]
COVERAGE_HEADERS = [
    "surfaceId",
    "surfaceKind",
    "entryIndex",
    "legacySources",
    "scanStatus",
    "candidateIds",
    "excludedLegacyItems",
    "notes",
]
RECONCILIATION_HEADERS = [
    "oldEffectId",
    "oldDecision",
    "reconciliationDisposition",
    "censusCandidateIds",
    "newAuthority",
    "notes",
]
LEGACY_HEADERS = [
    "effectId",
    "proposedPluginId",
    "userEffect",
    "frameworkCapabilities",
    "legacySources",
    "currentCoverage",
    "readRisk",
    "writeRisk",
    "uiRisk",
    "hookRisk",
    "decision",
    "notes",
]
EXTRACTION_HEADERS = [
    "plugin",
    "behavior",
    "legacyDependencies",
    "legacyEvidence",
    "salvageLevel",
    "targetOwner",
    "requiredCapabilities",
    "currentStatus",
    "missingFoundation",
    "riskClass",
    "unlockLevel",
    "firstSlice",
    "evidenceGate",
    "notes",
]
EXTRACTION_MAP_HEADERS = [
    "legacyPlugin",
    "legacyBehavior",
    "mappingDisposition",
    "censusCandidateIds",
    "historicalCapabilityIds",
    "sourceUnlockLevel",
    "assetDisposition",
    "notes",
]
CLUSTER_HEADERS = [
    "effectClusterId",
    "clusterKind",
    "targetPluginId",
    "memberEffectIds",
    "clusterDisposition",
    "deliveryGate",
    "sharedUserWorkflow",
    "permissionRiskEnvelope",
    "hostDependencyEnvelope",
    "forcedSplitRationale",
    "scaffoldPlan",
    "stability",
]

ALLOWED_KINDS = {
    "USER_EFFECT",
    "PRODUCT_SURFACE",
    "DEVELOPER_SURFACE",
    "HISTORICAL_CLAIM",
    "ENTRY_ALIAS",
    "EFFECT_OPTION",
    "FRAMEWORK_MECHANISM",
    "INCOMPLETE_SURFACE",
    "PACKAGING_SURFACE",
}
ALLOWED_PRODUCTS = {"MIGRATE", "DEFER", "DROP", "UNCONFIRMED"}
ALLOWED_BOUNDARIES = {"RETAIN_CLUSTER", "RECLUSTER", "FRAMEWORK", "NONE"}
ALLOWED_ORACLES = {"CONFIRMED", "PARTIAL", "UNCONFIRMED", "NOT_REQUIRED"}
ALLOWED_CONTRACTS = {"PENDING", "PENDING_IF_PROMOTED", "NOT_REQUIRED", "BLOCKED_BY_ORACLE"}
ALLOWED_EXTRACTION_MAPPINGS = {
    "DIRECT_EFFECT",
    "SHARED_EFFECT_SLICE",
    "ENTRY_SUPPORT",
    "FRAMEWORK_RECLASSIFIED",
    "CORRECTED",
    "SCAFFOLD_ONLY",
}
ALLOWED_ASSET_DISPOSITIONS = {
    "REVIEW_FOR_CODE_AND_TEST_MIGRATION",
    "ORACLE_OR_FIXTURE_ONLY",
    "REFERENCE_ONLY",
}
FRAMEWORK_OLD_ROWS = {
    "framework.context-menu-bridge",
    "framework.menu-toolbar-bridge",
    "framework.tab-dock-surface",
    "framework.color-picker-arbitration",
    "framework.palette-appearance",
}


def fail(message: str) -> None:
    print(f"FAIL: {message}", file=sys.stderr)
    raise SystemExit(1)


def read_tsv(path: Path, expected_headers: list[str]) -> list[dict[str, str]]:
    if not path.is_file():
        fail(f"missing {path.relative_to(ROOT)}")
    with path.open(encoding="utf-8", newline="") as handle:
        reader = csv.DictReader(handle, delimiter="\t")
        headers = list(reader.fieldnames or [])
        rows = list(reader)
    if headers != expected_headers:
        fail(f"{path.relative_to(ROOT)} headers differ: {headers}")
    return rows


def split_ids(value: str) -> list[str]:
    return [item for item in value.split(";") if item]


def validate_legacy_reference(reference: str, owner: str) -> None:
    if not reference.startswith("../turboism-legacy/"):
        fail(f"{owner}: legacy reference must use ../turboism-legacy/: {reference}")
    suffix = reference.removeprefix("../turboism-legacy/")
    if not suffix or "\\" in suffix or any(part == ".." for part in Path(suffix).parts):
        fail(f"{owner}: unsafe legacy reference: {reference}")
    if any(token in reference for token in ("/workspace/", "/root/", "file:", "http:", "https:")):
        fail(f"{owner}: absolute/URI legacy reference is forbidden: {reference}")
    # CI does not necessarily check out the read-only legacy repository. When it
    # is locally available, strengthen the format check with an existence check.
    if LEGACY_ROOT.is_dir():
        resolved = str(LEGACY_ROOT / suffix)
        exists = bool(glob.glob(resolved, recursive=True)) if "*" in resolved else Path(resolved).exists()
        if not exists:
            fail(f"{owner}: missing local legacy reference: {reference}")


def ensure_unique(rows: list[dict[str, str]], key: str, label: str) -> set[str]:
    values = [row[key] for row in rows]
    if any(not value for value in values):
        fail(f"{label} contains a blank {key}")
    if len(values) != len(set(values)):
        duplicates = sorted(value for value, count in Counter(values).items() if count > 1)
        fail(f"{label} contains duplicate {key}: {duplicates}")
    return set(values)


def main() -> None:
    ledger_rows = read_tsv(LEDGER, LEDGER_HEADERS)
    alias_rows = read_tsv(CENSUS_ALIAS, LEDGER_HEADERS)
    coverage_rows = read_tsv(COVERAGE, COVERAGE_HEADERS)
    reconciliation_rows = read_tsv(RECONCILIATION, RECONCILIATION_HEADERS)
    snapshot_rows = read_tsv(LEGACY_SNAPSHOT, LEGACY_HEADERS)
    extraction_rows = read_tsv(EXTRACTION, EXTRACTION_HEADERS)
    extraction_map_rows = read_tsv(EXTRACTION_MAP, EXTRACTION_MAP_HEADERS)
    cluster_rows = read_tsv(CLUSTERS, CLUSTER_HEADERS)

    if LEDGER.read_bytes() != CENSUS_ALIAS.read_bytes():
        fail("legacy-user-effect-census.tsv must be a byte-identical review alias of legacy-user-effects.tsv")

    candidate_ids = ensure_unique(ledger_rows, "candidateId", "effect ledger")
    if len(candidate_ids) != 95:
        fail(f"effect ledger must contain the closed 95-row census, got {len(candidate_ids)}")

    for row in ledger_rows:
        candidate_id = row["candidateId"]
        if row["recordKind"] not in ALLOWED_KINDS:
            fail(f"{candidate_id}: invalid recordKind {row['recordKind']}")
        if row["productDisposition"] not in ALLOWED_PRODUCTS:
            fail(f"{candidate_id}: invalid productDisposition {row['productDisposition']}")
        if row["boundaryDisposition"] not in ALLOWED_BOUNDARIES:
            fail(f"{candidate_id}: invalid boundaryDisposition {row['boundaryDisposition']}")
        if row["oracleStatus"] not in ALLOWED_ORACLES:
            fail(f"{candidate_id}: invalid oracleStatus {row['oracleStatus']}")
        effect_contract = row["effectContract"]
        expected_contract_path = f"docs/migration/effect-contracts/{candidate_id}.md"
        if effect_contract not in ALLOWED_CONTRACTS and effect_contract != expected_contract_path:
            fail(f"{candidate_id}: invalid effectContract {effect_contract}")
        for field in ("userGoal", "entrypoints", "observableOutcome", "legacyEvidence", "censusFinding"):
            if not row[field]:
                fail(f"{candidate_id}: blank {field}")
        for reference in split_ids(row["legacyEvidence"]):
            validate_legacy_reference(reference, candidate_id)
        for reference in split_ids(row["oracleEvidence"]):
            validate_legacy_reference(reference, candidate_id)

        product = row["productDisposition"]
        if product in {"MIGRATE", "DEFER"}:
            if row["oracleStatus"] not in {"CONFIRMED", "PARTIAL"} or not row["oracleEvidence"]:
                fail(f"{candidate_id}: {product} requires an independent CONFIRMED/PARTIAL Oracle")
            if product == "MIGRATE" and row["recordKind"] == "USER_EFFECT":
                expected = f"docs/migration/effect-contracts/{candidate_id}.md"
            elif product == "DEFER" and row["recordKind"] == "USER_EFFECT":
                expected = "PENDING_IF_PROMOTED"
            else:
                expected = "NOT_REQUIRED"
            if row["effectContract"] != expected:
                fail(f"{candidate_id}: {product}/{row['recordKind']} requires effectContract={expected}")
            if row["implementationStrategy"] != "BEHAVIOR_EQUIVALENT_REIMPLEMENTATION":
                fail(f"{candidate_id}: {product} requires behavior-equivalent reimplementation")
        elif product == "DROP":
            if row["effectContract"] != "NOT_REQUIRED":
                fail(f"{candidate_id}: DROP requires effectContract=NOT_REQUIRED")
        else:
            if row["effectContract"] != "BLOCKED_BY_ORACLE":
                fail(f"{candidate_id}: UNCONFIRMED must remain blocked by Oracle")
            if row["boundaryDisposition"] != "NONE":
                fail(f"{candidate_id}: UNCONFIRMED cannot select a boundary")

        if row["requiredCapabilities"]:
            fail(f"{candidate_id}: requiredCapabilities must remain blank until Effect Contracts are complete")

    cluster_ids = ensure_unique(cluster_rows, "effectClusterId", "effect clusters")
    target_plugin_ids = ensure_unique(cluster_rows, "targetPluginId", "effect clusters")
    if len(cluster_ids) != 46:
        fail(f"effect-clusters.tsv must contain the reviewed 46 clusters/product plugins, got {len(cluster_ids)}")
    cluster_by_id = {row["effectClusterId"]: row for row in cluster_rows}
    member_to_cluster: dict[str, str] = {}
    for cluster in cluster_rows:
        cluster_id = cluster["effectClusterId"]
        if cluster["clusterKind"] not in {"EFFECT_CLUSTER", "PRODUCT_PLUGIN"}:
            fail(f"{cluster_id}: invalid clusterKind {cluster['clusterKind']}")
        if cluster["stability"] != "PLANNED_UNPUBLISHED":
            fail(f"{cluster_id}: cluster ID is not an unpublished plan")
        for field in (
            "sharedUserWorkflow", "permissionRiskEnvelope", "hostDependencyEnvelope",
            "forcedSplitRationale", "scaffoldPlan", "deliveryGate",
        ):
            if not cluster[field]:
                fail(f"{cluster_id}: blank {field}")
        members = split_ids(cluster["memberEffectIds"])
        if not members:
            fail(f"{cluster_id}: cluster has no members")
        for member in members:
            if member not in candidate_ids:
                fail(f"{cluster_id}: unknown member {member}")
            if member in member_to_cluster:
                fail(f"{member}: appears in both {member_to_cluster[member]} and {cluster_id}")
            member_to_cluster[member] = cluster_id
            ledger_row = next(row for row in ledger_rows if row["candidateId"] == member)
            if ledger_row["effectClusterId"] != cluster_id:
                fail(f"{member}: ledger cluster differs from effect-clusters.tsv")
            if ledger_row["targetPluginId"] != cluster["targetPluginId"]:
                fail(f"{member}: ledger targetPluginId differs from effect-clusters.tsv")

    expected_cluster_members = {
        row["candidateId"] for row in ledger_rows
        if row["recordKind"] == "USER_EFFECT" and row["productDisposition"] != "DROP"
    } | {"platform.update-management"}
    if set(member_to_cluster) != expected_cluster_members:
        fail(
            "cluster membership must cover every non-DROP User Effect plus update-management: "
            f"missing={sorted(expected_cluster_members-set(member_to_cluster))} "
            f"extra={sorted(set(member_to_cluster)-expected_cluster_members)}"
        )

    for row in ledger_rows:
        candidate_id = row["candidateId"]
        if candidate_id in expected_cluster_members:
            if not row["effectClusterId"] or not row["targetPluginId"]:
                fail(f"{candidate_id}: clustered record lacks cluster/plugin assignment")
        elif row["recordKind"] == "EFFECT_OPTION":
            if row["effectClusterId"] != "warp-mirror" or row["targetPluginId"] != "dev.turboism.plugin.warp-mirror":
                fail(f"{candidate_id}: mirror option must inherit the warp-mirror cluster")
        elif row["recordKind"] == "ENTRY_ALIAS" and candidate_id == "ui.home-button":
            if row["effectClusterId"] != "platform-settings-entry" or row["targetPluginId"] != "FRAMEWORK":
                fail("ui.home-button must remain a framework-owned settings entry alias")
        elif row["boundaryDisposition"] == "FRAMEWORK":
            if row["effectClusterId"] or row["targetPluginId"]:
                fail(f"{candidate_id}: framework product/developer surface must not create a business plugin")
        elif row["effectClusterId"] or row["targetPluginId"]:
            fail(f"{candidate_id}: non-cluster record unexpectedly has a target plugin")

    surface_ids = ensure_unique(coverage_rows, "surfaceId", "entry coverage")
    if len(surface_ids) != 19:
        fail(f"entry coverage must contain 19 closed indexes, got {len(surface_ids)}")
    covered: set[str] = set()
    for row in coverage_rows:
        for reference in split_ids(row["legacySources"]):
            validate_legacy_reference(reference, row["surfaceId"])
        for candidate_id in split_ids(row["candidateIds"]):
            if candidate_id not in candidate_ids:
                fail(f"{row['surfaceId']}: unknown candidate {candidate_id}")
            covered.add(candidate_id)
    missing_coverage = sorted(candidate_ids - covered)
    if missing_coverage:
        fail(f"candidates absent from every entry/reverse index: {missing_coverage}")

    old_ids = ensure_unique(snapshot_rows, "effectId", "pre-census snapshot")
    if len(old_ids) != 48:
        fail(f"pre-census snapshot must contain 48 rows, got {len(old_ids)}")
    reconciled_ids = ensure_unique(reconciliation_rows, "oldEffectId", "reconciliation")
    if reconciled_ids != old_ids:
        fail(f"reconciliation does not cover the exact old ledger: missing={sorted(old_ids-reconciled_ids)} extra={sorted(reconciled_ids-old_ids)}")

    for row in reconciliation_rows:
        old_id = row["oldEffectId"]
        mapped = split_ids(row["censusCandidateIds"])
        if old_id in FRAMEWORK_OLD_ROWS:
            if row["reconciliationDisposition"] != "FRAMEWORK_RECLASSIFIED" or mapped:
                fail(f"{old_id}: framework row must be reclassified without a census candidate")
            if row["newAuthority"] != "docs/migration/capabilities/capability-catalog.tsv":
                fail(f"{old_id}: wrong framework authority {row['newAuthority']}")
        else:
            if not mapped:
                fail(f"{old_id}: non-framework row lacks a census mapping")
            unknown = sorted(set(mapped) - candidate_ids)
            if unknown:
                fail(f"{old_id}: unknown mapped candidates {unknown}")
            if row["newAuthority"] != "docs/migration/legacy-user-effects.tsv":
                fail(f"{old_id}: wrong effect authority {row['newAuthority']}")
        authority = ROOT / row["newAuthority"]
        if not authority.is_file():
            fail(f"{old_id}: missing authority {row['newAuthority']}")

    extraction_by_behavior = {row["behavior"]: row for row in extraction_rows}
    extraction_behavior_ids = ensure_unique(extraction_rows, "behavior", "legacy extraction")
    mapped_behavior_ids = ensure_unique(extraction_map_rows, "legacyBehavior", "legacy extraction map")
    if len(extraction_behavior_ids) != 54 or mapped_behavior_ids != extraction_behavior_ids:
        fail(
            "legacy extraction map must cover the exact 54 rows: "
            f"source={len(extraction_behavior_ids)} mapped={len(mapped_behavior_ids)} "
            f"missing={sorted(extraction_behavior_ids-mapped_behavior_ids)} "
            f"extra={sorted(mapped_behavior_ids-extraction_behavior_ids)}"
        )
    for row in extraction_map_rows:
        behavior = row["legacyBehavior"]
        source = extraction_by_behavior[behavior]
        if row["legacyPlugin"] != source["plugin"]:
            fail(f"{behavior}: legacy plugin drifted from extraction source")
        if row["historicalCapabilityIds"] != source["requiredCapabilities"]:
            fail(f"{behavior}: historical capability IDs drifted from extraction source")
        if row["sourceUnlockLevel"] != source["unlockLevel"]:
            fail(f"{behavior}: unlock level drifted from extraction source")
        if row["mappingDisposition"] not in ALLOWED_EXTRACTION_MAPPINGS:
            fail(f"{behavior}: invalid mapping disposition {row['mappingDisposition']}")
        if row["assetDisposition"] not in ALLOWED_ASSET_DISPOSITIONS:
            fail(f"{behavior}: invalid asset disposition {row['assetDisposition']}")
        mapped = split_ids(row["censusCandidateIds"])
        if row["mappingDisposition"] in {"FRAMEWORK_RECLASSIFIED", "SCAFFOLD_ONLY"}:
            if mapped:
                fail(f"{behavior}: framework/scaffold-only row must not invent a census effect")
        elif not mapped:
            fail(f"{behavior}: mapped extraction row lacks census candidates")
        unknown = sorted(set(mapped) - candidate_ids)
        if unknown:
            fail(f"{behavior}: unknown census candidates {unknown}")
        if source["unlockLevel"] == "B1_PURE_READY" and row["assetDisposition"] == "ORACLE_OR_FIXTURE_ONLY":
            fail(f"{behavior}: completed B1 pure asset must be explicitly reviewed or reference-only")

    workbook = WORKBOOK.read_text(encoding="utf-8")
    for required in (
        "STATUS: COMPLETE",
        "AUTHORITATIVE_LEDGER: docs/migration/legacy-user-effects.tsv",
        "CANDIDATE_RECORDS: 95",
        "OLD_LEDGER_ROWS_RECONCILED: 48/48",
        "LEGACY_EXTRACTION_ROWS_MAPPED: 54/54",
        "EFFECT_CLUSTERS: 46",
        "TARGET_PLUGIN_IDS: PLANNED_UNPUBLISHED",
        "MIGRATE_USER_EFFECT_CONTRACTS: 20/20",
        "EXECUTABLE_CONTRACT_SCENARIOS: 110",
    ):
        if required not in workbook:
            fail(f"workbook missing closure marker: {required}")

    adr = ADR.read_text(encoding="utf-8")
    if "docs/migration/legacy-user-effects.tsv" not in adr:
        fail("ADR 0027 does not name the authoritative effect ledger")
    if not CAPABILITY_CATALOG.is_file():
        fail("capability catalog is missing")

    counts = Counter(row["productDisposition"] for row in ledger_rows)
    oracle_counts = Counter(row["oracleStatus"] for row in ledger_rows)
    print(
        "PASS: legacy User Effect census closed; "
        f"records={len(ledger_rows)} clusters={len(cluster_rows)} surfaces={len(coverage_rows)} oldRows={len(reconciliation_rows)} extractionRows={len(extraction_map_rows)} "
        f"product={dict(sorted(counts.items()))} oracle={dict(sorted(oracle_counts.items()))}"
    )


if __name__ == "__main__":
    main()
