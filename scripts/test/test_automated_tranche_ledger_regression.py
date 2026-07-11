#!/usr/bin/env python3
"""Regression fixtures for automated-tranche ledger validation."""

from __future__ import annotations

import csv
import importlib.util
import tempfile
from pathlib import Path

SCRIPT = Path(__file__).with_name("validate_automated_tranche_ledger.py")
spec = importlib.util.spec_from_file_location("ledger_validator", SCRIPT)
module = importlib.util.module_from_spec(spec)
assert spec.loader
spec.loader.exec_module(module)
ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / "docs/migration/automated-tranche-ledger.tsv"


def load_rows() -> list[dict[str, str]]:
    with SOURCE.open(encoding="utf-8", newline="") as handle:
        return list(csv.DictReader(handle, delimiter="\t"))


def write_fixture(path: Path, rows: list[dict[str, str]]) -> None:
    with path.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=module.FIELDS, delimiter="\t", lineterminator="\n")
        writer.writeheader()
        writer.writerows(rows)


def write_raw_fixture(path: Path, raw_rows: list[list[str]]) -> None:
    with path.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.writer(handle, delimiter="\t", lineterminator="\n")
        writer.writerows(raw_rows)


def expect_failure(name: str, mutate, fragment: str) -> None:
    rows = load_rows()
    mutate(rows)
    with tempfile.TemporaryDirectory() as directory:
        fixture = Path(directory) / "ledger.tsv"
        write_fixture(fixture, rows)
        errors = module.validate(ROOT, fixture)
    assert any(fragment in error for error in errors), f"{name}: expected {fragment!r}, got {errors}"


def expect_raw_failure(name: str, mutate, fragment: str) -> None:
    with SOURCE.open(encoding="utf-8", newline="") as handle:
        raw_rows = list(csv.reader(handle, delimiter="\t"))
    mutate(raw_rows)
    with tempfile.TemporaryDirectory() as directory:
        fixture = Path(directory) / "ledger.tsv"
        write_raw_fixture(fixture, raw_rows)
        errors = module.validate(ROOT, fixture)
    assert any(fragment in error for error in errors), f"{name}: expected {fragment!r}, got {errors}"


def row(rows, work_id):
    return next(item for item in rows if item["workId"] == work_id)


def main() -> None:
    assert module.validate(ROOT, SOURCE) == []
    expect_raw_failure("extra column", lambda rows: rows[1].append("unexpected"), "expected exactly 14 fields, got 15")
    expect_raw_failure("missing column", lambda rows: rows[1].pop(), "expected exactly 14 fields, got 13")
    expect_failure("unknown board FK", lambda rows: row(rows, "phase0.scope-ledger").update(boardRowIds="not.a.board.row"), "unknown boardRowIds")
    expect_failure("unknown capability FK", lambda rows: row(rows, "r5.render-status-and-production-ingress").update(capabilityIds="not.a.capability"), "unknown capabilityIds")
    expect_failure("unknown adapter FK", lambda rows: row(rows, "r5.real-ui").update(adapterSliceIds="not.an.adapter"), "unknown adapterSliceIds")
    expect_failure("unknown plugin FK", lambda rows: row(rows, "r5.real-ui").update(pluginIds="not.a.plugin"), "unknown pluginIds")
    expect_failure("invalid evidence", lambda rows: row(rows, "phase0.scope-ledger").update(evidenceRefs="../secret"), "invalid evidenceRef")
    expect_failure("missing phase0", lambda rows: rows.remove(row(rows, "phase0.scope-ledger")), "missing required identity")
    expect_failure("missing sentinel", lambda rows: rows.remove(row(rows, "milestone.m14.overall")), "missing required identity")
    expect_failure("missing policy", lambda rows: rows.remove(row(rows, "manual.real-host-observation")), "missing required identity")
    expect_failure("missing execution class", lambda rows: rows.remove(row(rows, "authorized-local.host-artifact")), "missing required identity")
    expect_failure("delete policy class", lambda rows: rows.remove(row(rows, "forbidden.proprietary-and-bypass")), "missing required identity")
    expect_failure("overall COMPLETE", lambda rows: row(rows, "milestone.m14.overall").update(workStatus="COMPLETE"), "overall sentinel cannot be COMPLETE")
    expect_failure("phase0 ceiling", lambda rows: row(rows, "phase0.scope-ledger").update(readinessCeiling="BUILD_GATED"), "expected exact LEDGER_CORRECTED")
    expect_failure("phase0 ceiling overclaim", lambda rows: row(rows, "phase0.scope-ledger").update(readinessCeiling="AUTOMATED_TRANCHE_CLOSED", evidenceLevel="VERIFIED_STATIC_SYNTHETIC"), "expected exact LEDGER_CORRECTED")
    expect_failure("delete phase1", lambda rows: rows.remove(row(rows, "phase1.ownership-audit")), "missing required identity")
    expect_failure("phase1 class", lambda rows: row(rows, "phase1.ownership-audit").update(executionClass="MANUAL_ONLY"), "expected BOUNDED_SLICE/AUTO_NOW/COMPLETE")
    expect_failure("phase1 status", lambda rows: row(rows, "phase1.ownership-audit").update(workStatus="PENDING"), "expected BOUNDED_SLICE/AUTO_NOW/COMPLETE")
    expect_failure("phase1 evidence", lambda rows: row(rows, "phase1.ownership-audit").update(evidenceLevel="PLAN"), "expected Phase 1 tuple COMPLETE/VERIFIED_STATIC/OWNERSHIP_AUDITED")
    expect_failure("phase1 ceiling", lambda rows: row(rows, "phase1.ownership-audit").update(readinessCeiling="CONTRACT_TESTED"), "expected Phase 1 tuple COMPLETE/VERIFIED_STATIC/OWNERSHIP_AUDITED")
    for phase_id in sorted(module.PHASE1_FUTURE_WORK):
        expect_failure(f"delete {phase_id}", lambda rows, phase_id=phase_id: rows.remove(row(rows, phase_id)), "missing required identity")
        expect_failure(f"mutate class {phase_id}", lambda rows, phase_id=phase_id: row(rows, phase_id).update(executionClass="MANUAL_ONLY"), "expected BOUNDED_SLICE/AUTO_NOW/NOT_STARTED")
    expect_failure("phase2-6 future ceiling", lambda rows: row(rows, "automation.phase6.closure").update(readinessCeiling="AUTOMATED_TRANCHE_CLOSED", evidenceLevel="VERIFIED_STATIC_SYNTHETIC"), "Phase 2-6 must remain NOT_STARTED/NONE/NONE during Phase 1")
    expect_failure("phase2-6 future evidence", lambda rows: row(rows, "automation.phase2.dispatcher-contract").update(evidenceLevel="PLAN"), "Phase 2-6 must remain NOT_STARTED/NONE/NONE during Phase 1")
    expect_failure("M14 status", lambda rows: row(rows, "milestone.m14.overall").update(workStatus="BLOCKED"), "expected OVERALL_SENTINEL/MANUAL_ONLY/IN_PROGRESS")
    expect_failure("M16 status", lambda rows: row(rows, "milestone.m16.overall").update(workStatus="PENDING"), "expected OVERALL_SENTINEL/MANUAL_ONLY/NOT_STARTED")
    expect_failure("tranche status", lambda rows: row(rows, "tranche.automation.overall").update(workStatus="IN_PROGRESS"), "expected TRANCHE_SENTINEL/AUTO_NOW/PENDING")
    expect_failure("premature tranche closed", lambda rows: row(rows, "tranche.automation.overall").update(workStatus="COMPLETE", evidenceLevel="VERIFIED_STATIC_SYNTHETIC", readinessCeiling="AUTOMATED_TRANCHE_CLOSED"), "expected sentinel tuple PENDING/VERIFIED_STATIC/OWNERSHIP_AUDITED")
    expect_failure("tranche readiness escalation", lambda rows: row(rows, "tranche.automation.overall").update(readinessCeiling="CONTRACT_TESTED"), "expected sentinel tuple PENDING/VERIFIED_STATIC/OWNERSHIP_AUDITED")
    expect_failure("tranche evidence downgrade", lambda rows: row(rows, "tranche.automation.overall").update(evidenceLevel="PLAN"), "expected sentinel tuple PENDING/VERIFIED_STATIC/OWNERSHIP_AUDITED")
    expect_failure("M14 evidence downgrade", lambda rows: row(rows, "milestone.m14.overall").update(evidenceLevel="SYNTHETIC"), "expected sentinel tuple IN_PROGRESS/VERIFIED_STATIC_SYNTHETIC/VERIFIED_STATIC")
    expect_failure("M14 readiness escalation", lambda rows: row(rows, "milestone.m14.overall").update(readinessCeiling="AUTOMATED_TRANCHE_CLOSED"), "expected sentinel tuple IN_PROGRESS/VERIFIED_STATIC_SYNTHETIC/VERIFIED_STATIC")
    expect_failure("M16 premature escalation", lambda rows: row(rows, "milestone.m16.overall").update(evidenceLevel="VERIFIED_STATIC", readinessCeiling="DRY_RUN_READY"), "expected sentinel tuple NOT_STARTED/PLAN/NONE")
    expect_failure("wrong entity", lambda rows: row(rows, "phase0.scope-ledger").update(entityType="POLICY_BOUNDARY"), "expected BOUNDED_SLICE/AUTO_NOW/COMPLETE")
    expect_failure("readiness overclaim", lambda rows: row(rows, "phase0.scope-ledger").update(evidenceLevel="NONE"), "readiness overclaim")
    expect_failure("static evidence", lambda rows: row(rows, "static.project-workspace").update(evidenceLevel="SYNTHETIC"), "must retain VERIFIED_STATIC plus synthetic evidence")
    expect_failure("manual pending", lambda rows: row(rows, "static.clipmask").update(blockers="real-host invocation"), "manual validation must remain pending")
    expect_failure("R4 class", lambda rows: row(rows, "r4.context-menu.typed-dispatch").update(executionClass="AUTO_NOW"), "expected DEFERRED_ITEM/DEFERRED_SCOPE/DEFERRED")
    expect_failure("R4 blockers", lambda rows: row(rows, "r4.context-menu.typed-dispatch").update(blockers=""), "non-empty blockers")
    expect_failure("R5 nextSlice", lambda rows: row(rows, "r5.real-ui").update(nextSlice=""), "non-empty nextSlice")
    expect_failure("R4 wrong existing target", lambda rows: row(rows, "r4.context-menu.typed-dispatch").update(nextSlice="deferred.r5.render-ingress-plan"), "canonical target deferred.r4.context-menu-plan")
    expect_failure("R5 render wrong existing target", lambda rows: row(rows, "r5.render-status-and-production-ingress").update(nextSlice="deferred.r6.real-ui-plan"), "canonical target deferred.r5.render-ingress-plan")
    expect_failure("R5 UI wrong existing target", lambda rows: row(rows, "r5.real-ui").update(nextSlice="deferred.r4.context-menu-plan"), "canonical target deferred.r6.real-ui-plan")
    expect_failure("missing deferred target", lambda rows: rows.remove(row(rows, "deferred.r6.real-ui-plan")), "missing required identity")
    expect_failure("erase R4 traceability", lambda rows: rows.remove(row(rows, "r4.context-menu.typed-dispatch")), "missing required identity")
    expect_failure("erase R5 render traceability", lambda rows: rows.remove(row(rows, "r5.render-status-and-production-ingress")), "missing required identity")
    expect_failure("erase R5 UI traceability", lambda rows: rows.remove(row(rows, "r5.real-ui")), "missing required identity")
    expect_failure("duplicate", lambda rows: rows.append(dict(rows[0])), "duplicate workId")
    expect_failure("enum", lambda rows: row(rows, "phase0.scope-ledger").update(workStatus="DONE"), "invalid workStatus")
    print("PASS: automated tranche ledger regression fixtures")


if __name__ == "__main__":
    main()
