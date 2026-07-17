#!/usr/bin/env python3
from __future__ import annotations

import csv
import re
import sys
from collections import Counter, defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
MATRIX = ROOT / "docs/migration/capabilities/legacy-framework-capability-extraction.tsv"
PRD = ROOT / "docs/migration/plans/legacy-framework-capability-extraction-prd.md"
CATALOG = ROOT / "docs/migration/capabilities/capability-catalog.tsv"
READINESS = ROOT / "docs/migration/capabilities/plugin-readiness-matrix.tsv"
SALVAGE_DIR = ROOT / "docs/migration/salvage-notes"
DUPLICATE_MATRIX = ROOT / "docs/migration/legacy-business-capability-extraction.tsv"
DUPLICATE_PRD = ROOT / "docs/migration/plans/legacy-sdk-only-capability-unlock-prd.md"

EXPECTED_HEADERS = [
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
EXPECTED_PLUGINS = {
    "turboism.bounding-box",
    "turboism.clip-mask",
    "turboism.context-menu",
    "turboism.log-filter",
    "turboism.main-toolbar",
    "turboism.mesh-edit",
    "turboism.parameter",
    "turboism.perf-opt",
    "turboism.project-panel",
    "turboism.psd-import",
    "turboism.render-opt",
    "turboism.texture-atlas",
    "turboism.ui-theme",
}
SALVAGE_LEVELS = {"L2", "L3", "L4"}
OWNER_ITEMS = {"PLUGIN", "SDK", "RUNTIME", "ADAPTER", "HOOK", "UI_ADAPTER", "TRANSACTION", "SIDECAR"}
STATUSES = {"AVAILABLE", "FAKE_VERIFIED", "STATIC_VERIFIED", "RUNTIME_VERIFIED", "PLANNED", "BLOCKED"}
RISK_CLASSES = {"PURE", "READ", "UI", "EVENT", "WRITE", "SIDECAR", "RENDER"}
UNLOCK_LEVELS = {
    "B1_PURE_READY",
    "B2_READ_READY",
    "B3_UI_BLOCKED",
    "B4_WRITE_BLOCKED",
    "B5_HOOK_RENDER_BLOCKED",
}
EVIDENCE_ITEMS = {
    "SPEC",
    "SDK_BOUNDARY",
    "UNIT",
    "PERMISSION",
    "FAKE_HOST",
    "FAKE_INGRESS",
    "STATIC_MAPPING",
    "RUNTIME_COMPOSITION",
    "TRANSACTION",
    "ROLLBACK",
    "LIMITS",
    "TIMEOUT",
    "CANCEL",
    "LIFECYCLE",
    "HOOK_EVIDENCE",
    "PERFORMANCE",
    "PROVENANCE",
    "MANUAL_UI",
    "MANUAL_WRITE",
    "MANUAL_RENDER",
}
IMPLEMENTED_FOUNDATIONS = {
    "plugin.localization",
    "plugin.task.schedule",
    "plugin.storage",
    "plugin.config.typed",
    "plugin.user-file",
}
B1_REQUIRED = {
    "turboism.ui-theme": {"ui-theme.package-catalog", "ui-theme.generate-edit"},
    "turboism.project-panel": {"project-panel.state-model"},
    "turboism.log-filter": {"log-filter.match-policy"},
    "turboism.parameter": {"parameter.csv-codec"},
}
B2_ASYNC_REQUIRED = {
    "clip-mask.inspect",
    "clip-mask.refresh-lifecycle",
    "parameter.csv-export",
    "parameter.csv-import",
    "parameter.edit-plan",
    "mesh.select-parent",
    "mesh.apply-to-children",
    "mesh.mirror-left-right",
    "psd.candidate-report",
    "render.status-presentation",
    "texture-atlas.inspect",
    "texture-atlas.reinit-events",
}


def fail(message: str) -> None:
    print(f"FAIL: {message}", file=sys.stderr)
    raise SystemExit(1)


def read_tsv(path: Path) -> tuple[list[str], list[dict[str, str]]]:
    if not path.is_file():
        fail(f"missing {path.relative_to(ROOT)}")
    with path.open(encoding="utf-8", newline="") as handle:
        reader = csv.DictReader(handle, delimiter="\t")
        return list(reader.fieldnames or []), list(reader)


def split_ids(value: str) -> list[str]:
    return [item for item in value.split(";") if item]


def main() -> None:
    if DUPLICATE_MATRIX.exists() or DUPLICATE_PRD.exists():
        fail("duplicate legacy extraction authority remains")

    headers, rows = read_tsv(MATRIX)
    if headers != EXPECTED_HEADERS:
        fail(f"matrix headers differ: {headers}")
    if not rows:
        fail("matrix has no behavior rows")

    catalog_headers, catalog_rows = read_tsv(CATALOG)
    if "capabilityId" not in catalog_headers or "status" not in catalog_headers:
        fail("capability catalog schema is incomplete")
    catalog = {row["capabilityId"]: row for row in catalog_rows}
    if len(catalog) != len(catalog_rows):
        fail("capability catalog contains duplicate capability IDs")

    catalog_categories = {"read", "write", "ui", "event", "hook-ingress", "diagnostic", "sidecar", "foundation"}
    catalog_statuses = {"planned", "draft", "fake-verified", "adapter-ready", "production-ready", "deferred"}
    threading_budgets = {
        "plugin-bounded",
        "ui-short",
        "sidecar-required",
        "editor-critical-enqueue-only",
        "io-bounded",
        "host-read-bounded",
        "host-command-bounded",
    }
    for capability_id, catalog_row in catalog.items():
        if catalog_row["category"] not in catalog_categories:
            fail(f"catalog {capability_id}: invalid category {catalog_row['category']}")
        if catalog_row["status"] not in catalog_statuses:
            fail(f"catalog {capability_id}: invalid status {catalog_row['status']}")
        if catalog_row["threadingBudget"] not in threading_budgets:
            fail(f"catalog {capability_id}: invalid threadingBudget {catalog_row['threadingBudget']}")
        for boolean_field in ("requiresTransaction", "requiresHook", "requiresMapping"):
            if catalog_row[boolean_field] not in {"true", "false"}:
                fail(f"catalog {capability_id}: invalid {boolean_field} {catalog_row[boolean_field]}")
        if catalog_row["status"] == "planned" and catalog_row["fakeHostFixture"] != "planned":
            fail(f"catalog {capability_id}: planned row must use planned fakeHostFixture")

    async_host_read = catalog.get("runtime.host-read.async")
    if async_host_read is None:
        fail("catalog lacks runtime.host-read.async")
    expected_async_host_read = {
        "sdkSurface": "dev.turboism.sdk.hostread.AsyncHostReadService",
        "runtimeOwner": "dev.turboism.hostread.RuntimeAsyncHostReadService",
        "adapterOwner": "dev.turboism.adapter.cubism.ProjectWorkspaceAdapter",
        "threadingBudget": "host-read-bounded",
        "status": "fake-verified",
    }
    for field, expected in expected_async_host_read.items():
        if async_host_read[field] != expected:
            fail(f"runtime.host-read.async: expected {field}={expected!r}, found {async_host_read[field]!r}")
    if "turboism.cubism.project.read" not in async_host_read["permissions"]:
        fail("runtime.host-read.async must use the descriptor-derived project read permission")
    for fixture in (
        "AsyncHostReadContractTest",
        "RuntimeAsyncHostReadServiceTest",
        "ProjectWorkspaceHostReadSourceTest",
        "ProjectInspectorLifecycleTest",
    ):
        if fixture not in async_host_read["fakeHostFixture"]:
            fail(f"runtime.host-read.async lacks fake fixture {fixture}")

    plugins = {row["plugin"] for row in rows}
    if plugins != EXPECTED_PLUGINS:
        fail(f"plugin set differs: missing={sorted(EXPECTED_PLUGINS - plugins)} extra={sorted(plugins - EXPECTED_PLUGINS)}")

    counts = Counter(row["plugin"] for row in rows)
    for plugin, count in sorted(counts.items()):
        if not 2 <= count <= 8:
            fail(f"{plugin} has {count} rows; expected 2..8")

    behavior_ids: set[str] = set()
    rows_by_plugin: dict[str, list[dict[str, str]]] = defaultdict(list)
    for line_number, row in enumerate(rows, start=2):
        plugin = row["plugin"]
        behavior = row["behavior"]
        rows_by_plugin[plugin].append(row)
        if not behavior or behavior in behavior_ids:
            fail(f"line {line_number}: blank or duplicate behavior {behavior!r}")
        behavior_ids.add(behavior)

        for field in EXPECTED_HEADERS:
            if field != "missingFoundation" and not row[field].strip():
                fail(f"line {line_number}: {field} must not be blank")

        evidence_path = row["legacyEvidence"]
        if not (
            evidence_path.startswith("../turboism-legacy/plugins/")
            or evidence_path.startswith("plugins/")
        ):
            fail(f"line {line_number}: legacyEvidence is not an approved relative path: {evidence_path}")
        if any(token in evidence_path for token in ("/workspace/", "/root/", "file:", "http:", "https:")):
            fail(f"line {line_number}: forbidden absolute/URI evidence path")
        if re.match(r"^[A-Za-z]:[\\/]", evidence_path):
            fail(f"line {line_number}: drive-qualified evidence path")

        if row["salvageLevel"] not in SALVAGE_LEVELS:
            fail(f"line {line_number}: invalid salvageLevel {row['salvageLevel']}")
        owner_items = row["targetOwner"].split("+")
        if not owner_items or any(item not in OWNER_ITEMS for item in owner_items):
            fail(f"line {line_number}: invalid targetOwner {row['targetOwner']}")
        if len(owner_items) != len(set(owner_items)):
            fail(f"line {line_number}: duplicate targetOwner item")
        if row["currentStatus"] not in STATUSES:
            fail(f"line {line_number}: invalid currentStatus {row['currentStatus']}")
        if row["riskClass"] not in RISK_CLASSES:
            fail(f"line {line_number}: invalid riskClass {row['riskClass']}")
        if row["unlockLevel"] not in UNLOCK_LEVELS:
            fail(f"line {line_number}: invalid unlockLevel {row['unlockLevel']}")

        gates = row["evidenceGate"].split("+")
        if not gates or any(gate not in EVIDENCE_ITEMS for gate in gates):
            fail(f"line {line_number}: invalid evidenceGate {row['evidenceGate']}")
        if len(gates) != len(set(gates)):
            fail(f"line {line_number}: duplicate evidence gate")
        if "SPEC" not in gates or "SDK_BOUNDARY" not in gates or "LIFECYCLE" not in gates:
            fail(f"line {line_number}: mandatory evidence gates are missing")

        required = split_ids(row["requiredCapabilities"])
        missing = split_ids(row["missingFoundation"])
        if len(required) != len(set(required)) or len(missing) != len(set(missing)):
            fail(f"line {line_number}: duplicate capability ID")
        for capability_id in required + missing:
            if capability_id not in catalog:
                fail(f"line {line_number}: unknown capability {capability_id}")
        forbidden_missing = IMPLEMENTED_FOUNDATIONS.intersection(missing)
        if forbidden_missing:
            fail(f"line {line_number}: implemented foundation listed as missing: {sorted(forbidden_missing)}")
        for capability_id in missing:
            if capability_id not in required:
                fail(f"line {line_number}: missingFoundation {capability_id} is not in requiredCapabilities")
            if catalog[capability_id]["status"] == "production-ready":
                fail(f"line {line_number}: production-ready capability cannot be missing: {capability_id}")

        if row["unlockLevel"] == "B1_PURE_READY" and row["riskClass"] != "PURE":
            fail(f"line {line_number}: B1 row must be PURE")
        if row["unlockLevel"] == "B2_READ_READY" and row["riskClass"] != "READ":
            fail(f"line {line_number}: B2 row must be READ")
        if row["riskClass"] in {"WRITE", "SIDECAR"} and row["unlockLevel"] != "B4_WRITE_BLOCKED":
            fail(f"line {line_number}: WRITE/SIDECAR row must remain B4 blocked")
        if row["riskClass"] == "SIDECAR" and any(
            capability_id.startswith("cubism.transaction.") for capability_id in required + missing
        ):
            fail(f"line {line_number}: SIDECAR row must not depend on Cubism model transaction/Undo")
        if row["unlockLevel"] == "B2_READ_READY" and any(
            gate in gates for gate in {"TRANSACTION", "ROLLBACK", "MANUAL_WRITE"}
        ):
            fail(f"line {line_number}: B2 row contains write evidence gate")
        if row["riskClass"] == "WRITE" and not {"TRANSACTION", "ROLLBACK"}.issubset(gates):
            fail(f"line {line_number}: WRITE row lacks transaction/rollback gates")
        if row["riskClass"] in {"EVENT", "RENDER"} and row["unlockLevel"] != "B5_HOOK_RENDER_BLOCKED":
            fail(f"line {line_number}: EVENT/RENDER row must remain B5 blocked")

    for plugin, plugin_rows in rows_by_plugin.items():
        if not any(row["firstSlice"].strip() for row in plugin_rows):
            fail(f"{plugin} has no first slice")
        note = SALVAGE_DIR / f"legacy-{plugin.replace('.', '-')}.md"
        if not note.is_file():
            fail(f"missing salvage note {note.relative_to(ROOT)}")
        text = note.read_text(encoding="utf-8")
        for section in ("Source boundary", "First safe slice", "Blockers", "Review Summary"):
            if section not in text:
                fail(f"{note.name} lacks section {section}")
        if "Allowed:" not in text or "Prohibited:" not in text or "First slice:" not in text:
            fail(f"{note.name} does not state allowed/prohibited/first slice")
        if "/workspace/" in text or "/root/" in text:
            fail(f"{note.name} contains absolute workspace path")

    for plugin, required_behaviors in B1_REQUIRED.items():
        actual = {row["behavior"] for row in rows_by_plugin[plugin] if row["unlockLevel"] == "B1_PURE_READY"}
        if not required_behaviors.issubset(actual):
            fail(f"{plugin} lacks required B1 slice rows: {sorted(required_behaviors - actual)}")

    for behavior in B2_ASYNC_REQUIRED:
        row = next(item for item in rows if item["behavior"] == behavior)
        if "runtime.host-read.async" not in split_ids(row["missingFoundation"]):
            fail(f"{behavior} must remain blocked by runtime.host-read.async")
        if row["riskClass"] == "READ" and row["unlockLevel"] != "B2_READ_READY":
            fail(f"{behavior} read behavior must remain B2")

    prd_text = PRD.read_text(encoding="utf-8") if PRD.is_file() else ""
    if MATRIX.relative_to(ROOT).as_posix() not in prd_text:
        fail("PRD does not name the canonical matrix")
    if "/workspace/" in prd_text or "/root/" in prd_text:
        fail("PRD contains absolute workspace path")
    for token in ("B1_PURE_READY", "B2_READ_READY", "B3_UI_BLOCKED", "B4_WRITE_BLOCKED", "B5_HOOK_RENDER_BLOCKED"):
        if token not in prd_text:
            fail(f"PRD lacks unlock level {token}")
    scope_match = re.search(r"Scope: 13 legacy business plugins, (\d+) reviewable behavior rows", prd_text)
    if scope_match is None or int(scope_match.group(1)) != len(rows):
        fail(f"PRD behavior-row count does not match matrix: PRD={scope_match.group(1) if scope_match else 'missing'} matrix={len(rows)}")

    _, readiness_rows = read_tsv(READINESS)
    readiness_plugins = {row["plugin"] for row in readiness_rows}
    if readiness_plugins != EXPECTED_PLUGINS:
        fail("readiness matrix plugin set differs from extraction")
    readiness_rank = {"shell-ready": 0, "blocked": 0, "fake-ready": 2, "adapter-ready": 3, "production-ready": 4}
    capability_rank = {"planned": 0, "draft": 1, "fake-verified": 2, "adapter-ready": 3, "production-ready": 4, "deferred": -1}
    readiness_production_consumers = {
        "runtime.host-read.async": re.compile(r"\.hostReads\(\)"),
        "plugin.localization": re.compile(r"\.localization\(\)"),
        "plugin.task.schedule": re.compile(r"\.tasks\(\)"),
        "plugin.storage": re.compile(r"\.storage\(\)"),
        "plugin.user-file": re.compile(r"\.userFiles\(\)"),
        "plugin.config.typed": re.compile(r"\.config\(\)\.(?:registerSchema|read|write)\("),
    }
    production_source_by_plugin = {
        plugin: list((ROOT / "plugins" / plugin.removeprefix("turboism.") / "src/main/java").rglob("*.java"))
        for plugin in EXPECTED_PLUGINS
    }
    # Module names with a legacy ID mismatch are explicit.
    production_source_by_plugin["turboism.mesh-edit"] = list((ROOT / "plugins/mesh/src/main/java").rglob("*.java"))
    production_source_by_plugin["turboism.ui-theme"] = list((ROOT / "plugins/ui-theme/src/main/java").rglob("*.java"))
    production_source_by_plugin["turboism.perf-opt"] = list((ROOT / "plugins/perf-opt/src/main/java").rglob("*.java"))
    production_source_by_plugin["turboism.render-opt"] = list((ROOT / "plugins/render-opt/src/main/java").rglob("*.java"))
    for row in readiness_rows:
        required_ids = split_ids(row["requiredCapabilities"])
        for capability_id in required_ids:
            if capability_id not in catalog:
                fail(f"readiness row {row['plugin']} references unknown capability {capability_id}")
            if catalog[capability_id]["status"] == "planned":
                fail(f"readiness row {row['plugin']} promotes planned capability {capability_id}")
            if capability_rank[catalog[capability_id]["status"]] < readiness_rank[row["readiness"]]:
                fail(f"readiness row {row['plugin']} exceeds {capability_id} evidence")
        for foundation_id, pattern in readiness_production_consumers.items():
            if foundation_id not in required_ids:
                continue
            source_text = "\n".join(
                path.read_text(encoding="utf-8")
                for path in production_source_by_plugin[row["plugin"]]
            )
            if not pattern.search(source_text):
                fail(f"readiness row {row['plugin']} falsely claims unconsumed foundation {foundation_id}")
        future_text = row["productionBlockedBy"] + " " + row["nextSlice"]
        for capability_id, catalog_row in catalog.items():
            if catalog_row["status"] == "planned" and capability_id in required_ids:
                fail(f"planned capability {capability_id} must be blocker/nextSlice only")
        if row["readiness"] != "production-ready" and not row["productionBlockedBy"].strip():
            fail(f"readiness row {row['plugin']} lacks production blocker")
        if "planned future IDs:" in row["productionBlockedBy"] and not any(
            capability_id in future_text
            for capability_id, catalog_row in catalog.items()
            if catalog_row["status"] == "planned"
        ):
            fail(f"readiness row {row['plugin']} has malformed planned future IDs marker")

    java_changes = []
    # This phase owns docs and a Python gate only. The check intentionally
    # rejects Java files named for this extraction deliverable.
    for path in ROOT.rglob("*LegacyFrameworkCapabilityExtraction*.java"):
        java_changes.append(path.relative_to(ROOT).as_posix())
    if java_changes:
        fail(f"unexpected Java API/implementation files: {java_changes}")

    print(
        "PASS: legacy framework capability extraction "
        f"({len(rows)} behaviors, {len(EXPECTED_PLUGINS)} plugins)"
    )


if __name__ == "__main__":
    main()
