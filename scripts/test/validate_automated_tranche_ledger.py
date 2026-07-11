#!/usr/bin/env python3
"""Validate the authoritative automated-tranche TSV ledger using stdlib only."""

from __future__ import annotations

import argparse
import csv
import sys
import os
from pathlib import Path, PurePosixPath

FIELDS = [
    "workId", "entityType", "executionClass", "workStatus", "evidenceLevel",
    "readinessCeiling", "boardRowIds", "capabilityIds", "adapterSliceIds",
    "pluginIds", "evidenceRefs", "blockers", "nextSlice", "notes",
]
ENTITY_TYPES = {"OVERALL_SENTINEL", "TRANCHE_SENTINEL", "BOUNDED_SLICE", "DEFERRED_ITEM", "POLICY_BOUNDARY"}
EXECUTION_CLASSES = {"AUTO_NOW", "AUTO_WITH_AUTHORIZED_LOCAL_INPUT", "MANUAL_ONLY", "DEFERRED_SCOPE", "FORBIDDEN"}
WORK_STATUSES = {"NOT_STARTED", "PENDING", "IN_PROGRESS", "COMPLETE", "BLOCKED", "DEFERRED", "PROHIBITED"}
EVIDENCE_LEVELS = {
    "NONE", "PLAN", "FAKE", "VERIFIED_STATIC", "VERIFIED_STATIC_FAKE",
    "SYNTHETIC", "VERIFIED_STATIC_SYNTHETIC",
}
READINESS_CEILINGS = {
    "NONE", "LEDGER_CORRECTED", "OWNERSHIP_AUDITED", "CONTRACT_TESTED",
    "VERIFIED_STATIC", "SYNTHETIC_COMPOSITION_READY", "BUILD_GATED", "DRY_RUN_READY",
    "AUTOMATED_TRANCHE_CLOSED",
}
EVIDENCE_RANK = {
    "NONE": 0, "PLAN": 1, "FAKE": 2, "VERIFIED_STATIC": 3,
    "VERIFIED_STATIC_FAKE": 3, "SYNTHETIC": 4,
    "VERIFIED_STATIC_SYNTHETIC": 4,
}
CEILING_MIN_EVIDENCE = {
    "NONE": 0, "LEDGER_CORRECTED": 1, "OWNERSHIP_AUDITED": 2,
    "CONTRACT_TESTED": 2, "VERIFIED_STATIC": 3, "SYNTHETIC_COMPOSITION_READY": 4,
    "BUILD_GATED": 2, "DRY_RUN_READY": 2, "AUTOMATED_TRANCHE_CLOSED": 4,
}
REQUIRED_IDENTITIES = {
    "phase0.scope-ledger": ("BOUNDED_SLICE", "AUTO_NOW", "COMPLETE"),
    "phase1.ownership-audit": ("BOUNDED_SLICE", "AUTO_NOW", "COMPLETE"),
    "automation.phase2.dispatcher-contract": ("BOUNDED_SLICE", "AUTO_NOW", "COMPLETE"),
    "automation.phase3.synthetic-composition": ("BOUNDED_SLICE", "AUTO_NOW", "COMPLETE"),
    "automation.phase4.build-gates": ("BOUNDED_SLICE", "AUTO_NOW", "COMPLETE"),
    "automation.phase5.packaging-dryrun": ("BOUNDED_SLICE", "AUTO_NOW", "NOT_STARTED"),
    "automation.phase6.closure": ("BOUNDED_SLICE", "AUTO_NOW", "NOT_STARTED"),
    "milestone.m14.overall": ("OVERALL_SENTINEL", "MANUAL_ONLY", "IN_PROGRESS"),
    "milestone.m16.overall": ("OVERALL_SENTINEL", "MANUAL_ONLY", "NOT_STARTED"),
    "tranche.automation.overall": ("TRANCHE_SENTINEL", "AUTO_NOW", "PENDING"),
    "authorized-local.host-artifact": ("POLICY_BOUNDARY", "AUTO_WITH_AUTHORIZED_LOCAL_INPUT", "BLOCKED"),
    "manual.real-host-observation": ("POLICY_BOUNDARY", "MANUAL_ONLY", "BLOCKED"),
    "forbidden.proprietary-and-bypass": ("POLICY_BOUNDARY", "FORBIDDEN", "PROHIBITED"),
    "r4.context-menu.typed-dispatch": ("DEFERRED_ITEM", "DEFERRED_SCOPE", "DEFERRED"),
    "r5.render-status-and-production-ingress": ("DEFERRED_ITEM", "DEFERRED_SCOPE", "DEFERRED"),
    "r5.real-ui": ("DEFERRED_ITEM", "DEFERRED_SCOPE", "DEFERRED"),
    "deferred.r4.context-menu-plan": ("DEFERRED_ITEM", "DEFERRED_SCOPE", "DEFERRED"),
    "deferred.r5.render-ingress-plan": ("DEFERRED_ITEM", "DEFERRED_SCOPE", "DEFERRED"),
    "deferred.r6.real-ui-plan": ("DEFERRED_ITEM", "DEFERRED_SCOPE", "DEFERRED"),
}
CRITICAL_DEFERRED = {
    "r4.context-menu.typed-dispatch",
    "r5.render-status-and-production-ingress",
    "r5.real-ui",
}
SENTINEL_TUPLES_BY_PHASE = {
    "phase4": {
        "tranche.automation.overall": ("PENDING", "VERIFIED_STATIC_SYNTHETIC", "BUILD_GATED"),
        "milestone.m14.overall": ("IN_PROGRESS", "VERIFIED_STATIC_SYNTHETIC", "VERIFIED_STATIC"),
        "milestone.m16.overall": ("NOT_STARTED", "PLAN", "NONE"),
    },
    "phase5": {
        "tranche.automation.overall": ("PENDING", "VERIFIED_STATIC_SYNTHETIC", "DRY_RUN_READY"),
        "milestone.m14.overall": ("IN_PROGRESS", "VERIFIED_STATIC_SYNTHETIC", "VERIFIED_STATIC"),
        "milestone.m16.overall": ("NOT_STARTED", "PLAN", "NONE"),
    },
}
SENTINEL_TUPLES = SENTINEL_TUPLES_BY_PHASE["phase4"]
CANONICAL_EDGES = {
    "r4.context-menu.typed-dispatch": "deferred.r4.context-menu-plan",
    "r5.render-status-and-production-ingress": "deferred.r5.render-ingress-plan",
    "r5.real-ui": "deferred.r6.real-ui-plan",
}
PHASE_TUPLES = {
    "automation.phase2.dispatcher-contract": ("COMPLETE", "VERIFIED_STATIC_FAKE", "CONTRACT_TESTED"),
    "automation.phase3.synthetic-composition": ("COMPLETE", "VERIFIED_STATIC_SYNTHETIC", "SYNTHETIC_COMPOSITION_READY"),
    "automation.phase4.build-gates": ("COMPLETE", "VERIFIED_STATIC_FAKE", "BUILD_GATED"),
    "automation.phase5.packaging-dryrun": ("NOT_STARTED", "NONE", "NONE"),
    "automation.phase6.closure": ("NOT_STARTED", "NONE", "NONE"),
}


def values(cell: str) -> list[str]:
    return [value.strip() for value in cell.split(";") if value.strip()]


def first_column(path: Path) -> set[str]:
    with path.open(encoding="utf-8", newline="") as handle:
        return {row[0] for row in list(csv.reader(handle, delimiter="\t"))[1:] if row}


def adapter_ids(root: Path) -> set[str]:
    result: set[str] = set()
    for path in (root / "docs/migration/adapter-specs").glob("*.md"):
        for line in path.read_text(encoding="utf-8").splitlines():
            if "| adapterSliceId |" in line:
                parts = [part.strip().strip("`") for part in line.split("|")]
                if len(parts) >= 4:
                    result.add(parts[2])
    return result


def valid_relative_reference(ref: str) -> bool:
    rel = ref.split("#", 1)[0]
    if not rel or "\\" in rel:
        return False
    pure = PurePosixPath(rel)
    return (
        not pure.is_absolute()
        and rel == pure.as_posix()
        and not rel.endswith("/")
        and all(part not in {"", ".", ".."} for part in rel.split("/"))
    )


def detect_target_phase(rows: list[dict[str, str]]) -> str:
    by_id = {row.get("workId", ""): row for row in rows}
    phase5 = by_id.get("automation.phase5.packaging-dryrun", {})
    phase6 = by_id.get("automation.phase6.closure", {})
    tranche = by_id.get("tranche.automation.overall", {})
    phase5_complete = (
        (phase5.get("workStatus"), phase5.get("evidenceLevel"), phase5.get("readinessCeiling"))
        == ("COMPLETE", "VERIFIED_STATIC_FAKE", "DRY_RUN_READY")
        and (phase6.get("workStatus"), phase6.get("evidenceLevel"), phase6.get("readinessCeiling"))
        == ("NOT_STARTED", "NONE", "NONE")
        and (tranche.get("workStatus"), tranche.get("evidenceLevel"), tranche.get("readinessCeiling"), tranche.get("nextSlice"))
        == ("PENDING", "VERIFIED_STATIC_SYNTHETIC", "DRY_RUN_READY", "automation.phase6.closure")
    )
    # Auto-promote only an internally complete authoritative Phase 5 state. A lone
    # mutation must remain on the Phase 4 contract so its own validation error is
    # reported instead of being masked by premature phase selection. Phase-specific
    # wrappers may still pass --target-phase or TURBOISM_LEDGER_PHASE explicitly.
    return "phase5" if phase5_complete else "phase4"


def validate(
    root: Path,
    ledger: Path,
    *,
    schema_only: bool = False,
    target_phase: str = "auto",
    evidence_overrides: dict[str, Path] | None = None,
) -> list[str]:
    errors: list[str] = []
    try:
        data = ledger.read_bytes()
    except OSError as exc:
        return [f"cannot read ledger: {exc}"]
    if data.startswith(b"\xef\xbb\xbf"):
        return ["UTF-8 BOM is not allowed"]
    try:
        text = data.decode("utf-8", errors="strict")
    except UnicodeDecodeError as exc:
        return [f"invalid UTF-8: {exc}"]
    raw_rows = list(csv.reader(text.splitlines(), delimiter="\t"))
    if not raw_rows or raw_rows[0] != FIELDS:
        return [f"header must be exactly: {'/'.join(FIELDS)}"]
    for line_number, raw_row in enumerate(raw_rows[1:], start=2):
        if len(raw_row) != len(FIELDS):
            errors.append(f"line {line_number}: expected exactly 14 fields, got {len(raw_row)}")
    if errors:
        return errors
    with ledger.open(encoding="utf-8", newline="") as handle:
        reader = csv.DictReader(handle, delimiter="\t")
        rows = list(reader)
    for line_number, row in enumerate(rows, start=2):
        if None in row or any(row.get(field) is None for field in FIELDS):
            errors.append(f"line {line_number}: DictReader produced None for malformed fields")
    if errors:
        return errors

    ids = [row["workId"] for row in rows]
    duplicates = sorted({item for item in ids if ids.count(item) > 1})
    if duplicates:
        errors.append(f"duplicate workId: {';'.join(duplicates)}")
    by_id = {row["workId"]: row for row in rows}

    board = first_column(root / "docs/migration/migration-board.tsv") if not schema_only else set()
    capabilities = first_column(root / "docs/migration/capabilities/capability-catalog.tsv") if not schema_only else set()
    plugins = first_column(root / "docs/migration/capabilities/plugin-readiness-matrix.tsv") if not schema_only else set()
    adapters = adapter_ids(root) if not schema_only else set()

    for line_number, row in enumerate(rows, start=2):
        label = f"line {line_number} ({row['workId'] or '<empty>'})"
        for field, allowed in (
            ("entityType", ENTITY_TYPES), ("executionClass", EXECUTION_CLASSES),
            ("workStatus", WORK_STATUSES), ("evidenceLevel", EVIDENCE_LEVELS),
            ("readinessCeiling", READINESS_CEILINGS),
        ):
            if row[field] not in allowed:
                errors.append(f"{label}: invalid {field}: {row[field]}")
        if not row["workId"]:
            errors.append(f"{label}: empty workId")
            continue
        if not schema_only:
            for field, known in (("boardRowIds", board), ("capabilityIds", capabilities), ("adapterSliceIds", adapters), ("pluginIds", plugins)):
                unknown = sorted(set(values(row[field])) - known)
                if unknown:
                    errors.append(f"{label}: unknown {field}: {';'.join(unknown)}")
        for ref in values(row["evidenceRefs"]):
            rel = ref.split("#", 1)[0]
            override = (evidence_overrides or {}).get(rel)
            evidence_exists = override.is_file() if override is not None else (root / rel).is_file()
            if not valid_relative_reference(ref) or (not schema_only and not evidence_exists):
                errors.append(f"{label}: invalid evidenceRef: {ref}")

        if row["evidenceLevel"] in EVIDENCE_RANK and row["readinessCeiling"] in CEILING_MIN_EVIDENCE:
            if EVIDENCE_RANK[row["evidenceLevel"]] < CEILING_MIN_EVIDENCE[row["readinessCeiling"]]:
                errors.append(f"{label}: readiness overclaim: {row['readinessCeiling']} from {row['evidenceLevel']}")
        if row["entityType"] == "OVERALL_SENTINEL" and row["workStatus"] == "COMPLETE":
            errors.append(f"{label}: overall sentinel cannot be COMPLETE")
        if row["workStatus"] == "COMPLETE" and row["entityType"] != "BOUNDED_SLICE":
            errors.append(f"{label}: COMPLETE is legal only for BOUNDED_SLICE")
        if row["executionClass"] == "DEFERRED_SCOPE" and row["workStatus"] != "DEFERRED":
            errors.append(f"{label}: DEFERRED_SCOPE must have DEFERRED status")
        if row["executionClass"] == "FORBIDDEN" and row["workStatus"] != "PROHIBITED":
            errors.append(f"{label}: FORBIDDEN must have PROHIBITED status")
        if row["executionClass"] in {"AUTO_WITH_AUTHORIZED_LOCAL_INPUT", "MANUAL_ONLY"} and row["workStatus"] == "COMPLETE":
            errors.append(f"{label}: {row['executionClass']} cannot be automatically COMPLETE")

    if schema_only:
        return errors

    missing = sorted(REQUIRED_IDENTITIES.keys() - by_id.keys())
    if missing:
        errors.append(f"missing required identity: {';'.join(missing)}")
        return errors

    if target_phase == "auto":
        target_phase = detect_target_phase(rows)

    identities = dict(REQUIRED_IDENTITIES)
    phase_tuples = dict(PHASE_TUPLES)
    if target_phase == "phase5":
        identities["automation.phase5.packaging-dryrun"] = ("BOUNDED_SLICE", "AUTO_NOW", "COMPLETE")
        phase_tuples["automation.phase5.packaging-dryrun"] = ("COMPLETE", "VERIFIED_STATIC_FAKE", "DRY_RUN_READY")
    for work_id, (entity_type, execution_class, work_status) in identities.items():
        if work_id not in by_id:
            continue
        row = by_id[work_id]
        actual = (row["entityType"], row["executionClass"], row["workStatus"])
        expected = (entity_type, execution_class, work_status)
        if actual != expected:
            errors.append(f"{work_id}: expected {'/'.join(expected)}")

    if "phase0.scope-ledger" in by_id and by_id["phase0.scope-ledger"]["readinessCeiling"] != "LEDGER_CORRECTED":
        errors.append("phase0.scope-ledger: expected exact LEDGER_CORRECTED readiness ceiling")
    sentinel_tuples = SENTINEL_TUPLES_BY_PHASE[target_phase]
    for work_id, expected in sentinel_tuples.items():
        if work_id not in by_id:
            continue
        row = by_id[work_id]
        actual = (row["workStatus"], row["evidenceLevel"], row["readinessCeiling"])
        if actual != expected:
            errors.append(f"{work_id}: expected sentinel tuple {'/'.join(expected)}")
    if "phase1.ownership-audit" in by_id:
        row = by_id["phase1.ownership-audit"]
        actual = (row["workStatus"], row["evidenceLevel"], row["readinessCeiling"])
        expected = ("COMPLETE", "VERIFIED_STATIC", "OWNERSHIP_AUDITED")
        if actual != expected:
            errors.append("phase1.ownership-audit: expected Phase 1 tuple COMPLETE/VERIFIED_STATIC/OWNERSHIP_AUDITED")
    for work_id, expected in phase_tuples.items():
        if work_id not in by_id:
            continue
        row = by_id[work_id]
        actual = (row["workStatus"], row["evidenceLevel"], row["readinessCeiling"])
        if actual != expected:
            errors.append(f"{work_id}: expected exact phase tuple {'/'.join(expected)}")

    for work_id in CRITICAL_DEFERRED & by_id.keys():
        row = by_id[work_id]
        if not row["blockers"].strip():
            errors.append(f"{work_id}: critical deferred row requires non-empty blockers")
        if not row["nextSlice"].strip():
            errors.append(f"{work_id}: critical deferred row requires non-empty nextSlice")
        elif row["nextSlice"] not in by_id:
            errors.append(f"{work_id}: nextSlice must reference a ledger workId: {row['nextSlice']}")
        expected_target = CANONICAL_EDGES[work_id]
        if row["nextSlice"] != expected_target:
            errors.append(f"{work_id}: nextSlice must be canonical target {expected_target}")

    for work_id in ("static.project-workspace", "static.clipmask"):
        if work_id not in by_id:
            errors.append(f"missing static evidence row: {work_id}")
            continue
        row = by_id[work_id]
        if row["evidenceLevel"] != "VERIFIED_STATIC_SYNTHETIC":
            errors.append(f"{work_id}: must retain VERIFIED_STATIC plus synthetic evidence")
        if "manual" not in row["blockers"].lower():
            errors.append(f"{work_id}: manual validation must remain pending")

    phase3 = by_id.get("automation.phase3.synthetic-composition")
    tranche = by_id.get("tranche.automation.overall")
    phase4 = by_id.get("automation.phase4.build-gates")
    if phase3:
        required_capabilities = {"cubism.project.read", "cubism.workspace.read", "cubism.clipmask.read"}
        required_adapters = {"adapter.project-workspace.readonly", "adapter.clipmask.readonly"}
        if set(values(phase3["capabilityIds"])) != required_capabilities:
            errors.append("automation.phase3.synthetic-composition: must reference both read slices' capability IDs")
        if set(values(phase3["adapterSliceIds"])) != required_adapters:
            errors.append("automation.phase3.synthetic-composition: must reference both independent adapter slices")
        if "docs/migration/phase3-synthetic-composition-report.md" not in values(phase3["evidenceRefs"]):
            errors.append("automation.phase3.synthetic-composition: missing Phase 3 report evidence")
        if "manual" not in phase3["blockers"].lower() or "real-host" not in phase3["blockers"].lower():
            errors.append("automation.phase3.synthetic-composition: real-host and manual validation must remain pending")
        phase3_notes = phase3["notes"].lower()
        if "seam-chain" not in phase3_notes or "atomic" not in phase3_notes:
            errors.append("automation.phase3.synthetic-composition: notes must preserve seam-chain and dual atomic semantics")
    if phase3 and tranche:
        if target_phase == "phase5":
            expected_tranche = ("VERIFIED_STATIC_SYNTHETIC", "DRY_RUN_READY", "automation.phase6.closure")
            actual_tranche = (tranche["evidenceLevel"], tranche["readinessCeiling"], tranche["nextSlice"])
            if actual_tranche != expected_tranche:
                errors.append(f"tranche.automation.overall: expected target phase5 tuple {'/'.join(expected_tranche)}")
        else:
            if tranche["evidenceLevel"] != "VERIFIED_STATIC_SYNTHETIC" or tranche["readinessCeiling"] != "BUILD_GATED":
                errors.append("tranche.automation.overall: must preserve Phase 3 evidence and advance only to BUILD_GATED")
            if tranche["nextSlice"] != "automation.phase5.packaging-dryrun":
                errors.append("tranche.automation.overall: nextSlice must advance to automation.phase5.packaging-dryrun")
    if phase4:
        required_refs = {
            "docs/migration/evidence/synthetic-composition-evidence-v1.json",
            "docs/migration/phase4-build-gates-report.md",
            "scripts/test/test_phase4_build_gates.sh",
        }
        if not required_refs.issubset(set(values(phase4["evidenceRefs"]))):
            errors.append("automation.phase4.build-gates: missing authoritative Phase 4 evidence refs")
    locked = ("automation.phase6.closure",) if target_phase == "phase5" else ("automation.phase5.packaging-dryrun", "automation.phase6.closure")
    for work_id in locked:
        row = by_id.get(work_id)
        if row and (row["workStatus"], row["evidenceLevel"], row["readinessCeiling"]) != ("NOT_STARTED", "NONE", "NONE"):
            errors.append(f"{work_id}: must remain locked at NOT_STARTED/NONE/NONE for target {target_phase}")
    return errors


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("ledger", type=Path)
    parser.add_argument("--repo-root", type=Path, default=Path(__file__).resolve().parents[2])
    parser.add_argument("--schema-only", action="store_true")
    parser.add_argument("--target-phase", choices=("auto", "phase4", "phase5"), default=os.environ.get("TURBOISM_LEDGER_PHASE", "auto"))
    args = parser.parse_args()
    errors = validate(args.repo_root.resolve(), args.ledger.resolve(), schema_only=args.schema_only, target_phase=args.target_phase)
    for error in errors:
        print(f"FAIL: {error}", file=sys.stderr)
    if errors:
        return 1
    print(f"PASS: automated tranche ledger ({args.ledger})")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
