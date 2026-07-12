#!/usr/bin/env python3
"""Fail-closed Phase 6 automated-tranche closure validation."""

from __future__ import annotations

import argparse
import csv
import subprocess
import sys
from pathlib import Path

CLOSURE_BASELINE = "a6b1861086142670137dbaa3a231d05648df73ca"
LEDGER_PATH = "docs/migration/automated-tranche-ledger.tsv"
REPORT_PATH = "docs/migration/phase6-automated-tranche-closure-report.md"
NEXT_PATH = "docs/migration/next-migration-slices.md"
ORACLE_PATH = "docs/migration/evidence/automated-tranche-oracle-dispositions.tsv"
PHASE_COMMITS = {
    "phase0.scope-ledger": "6d2acdcf8de9460aa819956781aa4f17f219f55c",
    "phase1.ownership-audit": "f24755122d2a33c8895f45cd5956a81a87a41d3d",
    "automation.phase2.dispatcher-contract": "1b74f7d0d6a8b9c03983bb30993ca0d4b521905e",
    "automation.phase3.synthetic-composition": "5b42c656a0f10b2959119663610f59f6d98d77fb",
    "automation.phase4.build-gates": "8adfcd88ff66db849d1f3a82326306a44f3fd568",
}
PHASE5_SOURCE_PROVENANCE = (
    ("aa1c66193d93a41c69d3082a3ace0eef3736f146", "6b2f252b882a05fdda35fc59cf243da4d42d0435", "dff5d6a4b337af074e45686e4d28bb6d73ce97c7"),
    ("97dd29d7aae5e56db7607b577fd31dd868760652", "8320de12670ec9b77888763187ec76f23307e3d2", "c2d041490e75455966fee9ffd30017ff1ccfc1e8"),
    ("ace93450112847c9fb3f6bc0a5fc685b6c25362b", CLOSURE_BASELINE, "d097490f91f7a050d6e9314a40f1d3833427fe74"),
)
PHASE5_INTEGRATED = tuple(item[1] for item in PHASE5_SOURCE_PROVENANCE)
REQUIRED_EVIDENCE = {
    "tranche.automation.overall": {
        REPORT_PATH,
        ORACLE_PATH,
    },
    "phase0.scope-ledger": {
        "docs/migration/automated-tranche-ledger.tsv",
        "scripts/test/validate_automated_tranche_ledger.py",
    },
    "phase1.ownership-audit": {
        "docs/migration/phase1-ingress-ownership-audit-report.md",
        "scripts/test/test_host_ingress_ownership_structure.sh",
    },
    "automation.phase2.dispatcher-contract": {
        "docs/migration/phase2-mailbox-contract-report.md",
        "runtime/src/test/java/dev/turboism/hook/ingress/BoundedHookEventMailboxTest.java",
    },
    "automation.phase3.synthetic-composition": {
        "docs/migration/phase3-synthetic-composition-report.md",
        "runtime/src/test/java/dev/turboism/adapter/host/HostSessionPluginContextIntegrationTest.java",
    },
    "automation.phase4.build-gates": {
        "docs/migration/phase4-build-gates-report.md",
        "scripts/test/test_phase4_build_gates.sh",
    },
    "automation.phase5.packaging-dryrun": {
        "docs/migration/phase5-pre-m16-packaging-dryrun-report.md",
        "scripts/test/test_pre_m16_packaging_dryrun.sh",
    },
    "automation.phase6.closure": {
        REPORT_PATH,
        "scripts/test/test_automated_tranche_closure.sh",
        "scripts/test/validate_automated_tranche_closure.py",
    },
    "static.project-workspace": {
        "docs/migration/m15-project-workspace-static-verification-report.md",
    },
    "static.clipmask": {
        "docs/migration/m15-clipmask-static-verification-report.md",
    },
}
PRE_CLOSURE_ROWS = {
    "phase0.scope-ledger": ("COMPLETE", "PLAN", "LEDGER_CORRECTED"),
    "phase1.ownership-audit": ("COMPLETE", "VERIFIED_STATIC", "OWNERSHIP_AUDITED"),
    "automation.phase2.dispatcher-contract": ("COMPLETE", "VERIFIED_STATIC_FAKE", "CONTRACT_TESTED"),
    "automation.phase3.synthetic-composition": ("COMPLETE", "VERIFIED_STATIC_SYNTHETIC", "SYNTHETIC_COMPOSITION_READY"),
    "automation.phase4.build-gates": ("COMPLETE", "VERIFIED_STATIC_FAKE", "BUILD_GATED"),
    "automation.phase5.packaging-dryrun": ("COMPLETE", "VERIFIED_STATIC_FAKE", "DRY_RUN_READY"),
    "static.project-workspace": ("COMPLETE", "VERIFIED_STATIC_SYNTHETIC", "SYNTHETIC_COMPOSITION_READY"),
    "static.clipmask": ("COMPLETE", "VERIFIED_STATIC_SYNTHETIC", "SYNTHETIC_COMPOSITION_READY"),
}
BOUNDARY_ROWS = {
    "milestone.m14.overall": (
        ("MANUAL_ONLY", "IN_PROGRESS", "VERIFIED_STATIC_SYNTHETIC", "VERIFIED_STATIC"),
        "real host invocation; manual GUI and lifecycle validation",
        "manual.real-host-observation",
        ("M14 overall remains incomplete", "bounded static and synthetic slices"),
        {"docs/migration/m14-complete-report.md"},
    ),
    "milestone.m16.overall": (
        ("MANUAL_ONLY", "NOT_STARTED", "PLAN", "NONE"),
        "M14/M15 evidence; release approval; manual install and rollback validation",
        "manual.real-host-observation",
        ("M16 production hardening remains not started", "outside this automated tranche"),
        {"docs/migration/plans/m16-production-hardening-prd.md"},
    ),
    "authorized-local.host-artifact": (
        ("AUTO_WITH_AUTHORIZED_LOCAL_INPUT", "BLOCKED", "NONE", "NONE"),
        "explicit user authorization and exact local path",
        "authorized-local.host-artifact",
        ("legally installed local artifact", "never committed or packaged"),
        {"docs/migration/plans/automated-tranche-completion-plan.md"},
    ),
    "manual.real-host-observation": (
        ("MANUAL_ONLY", "BLOCKED", "NONE", "NONE"),
        "human GUI, lifecycle, semantic, performance, compliance, and release judgment",
        "manual.real-host-observation",
        ("Automation cannot complete this row",),
        {"docs/migration/plans/automated-tranche-completion-plan.md"},
    ),
    "forbidden.proprietary-and-bypass": (
        ("FORBIDDEN", "PROHIBITED", "NONE", "NONE"),
        "permanent policy prohibition",
        "forbidden.proprietary-and-bypass",
        ("禁止复制 Cubism 私有源码", "禁止授权、试用、水印或安全绕过"),
        {"docs/migration/plans/automated-tranche-completion-plan.md"},
    ),
    "r4.context-menu.typed-dispatch": (
        ("DEFERRED_SCOPE", "DEFERRED", "FAKE", "NONE"),
        "verified ingress; typed metadata; native placement and manual GUI",
        "deferred.r4.context-menu-plan",
        ("production context-menu ingress", "explicitly deferred"),
        {"docs/migration/capabilities/plugin-readiness-matrix.tsv"},
    ),
    "r5.render-status-and-production-ingress": (
        ("DEFERRED_SCOPE", "DEFERRED", "FAKE", "NONE"),
        "exact evidence; render-status dependency; production ingress and manual validation",
        "deferred.r5.render-ingress-plan",
        ("production ingress", "independently traceable"),
        {"docs/migration/adapter-specs/adapter-render-status-readonly.md"},
    ),
    "r5.real-ui": (
        ("DEFERRED_SCOPE", "DEFERRED", "FAKE", "NONE"),
        "real adapter wiring; placement; EDT lifecycle and manual visual validation",
        "deferred.r6.real-ui-plan",
        ("R6 is the canonical future owner", "concrete real UI implementation"),
        {"docs/migration/adapter-specs/adapter-ui-surface.md", "docs/migration/adapter-specs/adapter-ui-status-toolbar.md"},
    ),
    "deferred.r4.context-menu-plan": (
        ("DEFERRED_SCOPE", "DEFERRED", "PLAN", "NONE"),
        "separate reviewed R4 authorization",
        "deferred.r4.context-menu-plan",
        ("Stable target identity", "future R4 plan"),
        {"docs/migration/plans/automated-tranche-completion-plan.md"},
    ),
    "deferred.r5.render-ingress-plan": (
        ("DEFERRED_SCOPE", "DEFERRED", "PLAN", "NONE"),
        "separate reviewed R5 authorization",
        "deferred.r5.render-ingress-plan",
        ("Stable target identity", "render-ingress plan"),
        {"docs/migration/plans/automated-tranche-completion-plan.md"},
    ),
    "deferred.r6.real-ui-plan": (
        ("DEFERRED_SCOPE", "DEFERRED", "PLAN", "NONE"),
        "separate reviewed R6 authorization and manual GUI plan",
        "deferred.r6.real-ui-plan",
        ("Canonical target identity", "concrete real UI implementation"),
        {"docs/migration/plans/automated-tranche-completion-plan.md"},
    ),
}


def git(root: Path, *args: str) -> subprocess.CompletedProcess[str]:
    return subprocess.run(["git", "-C", str(root), *args], text=True, capture_output=True)


def read_rows(path: Path) -> dict[str, dict[str, str]]:
    with path.open(encoding="utf-8", newline="") as handle:
        return {row["workId"]: row for row in csv.DictReader(handle, delimiter="\t")}


def refs(row: dict[str, str]) -> set[str]:
    return {ref.split("#", 1)[0] for ref in row.get("evidenceRefs", "").split(";") if ref}


def stable_patch_id(root: Path, commit: str) -> str | None:
    shown = git(root, "show", commit, "--pretty=format:", "--patch")
    if shown.returncode:
        return None
    result = subprocess.run(["git", "patch-id", "--stable"], input=shown.stdout, text=True, capture_output=True)
    if result.returncode or not result.stdout.strip():
        return None
    return result.stdout.split()[0]


def validate(root: Path, ledger: Path, report: Path, next_doc: Path, oracle: Path) -> list[str]:
    errors: list[str] = []
    try:
        rows = read_rows(ledger)
    except (OSError, KeyError, csv.Error) as exc:
        return [f"cannot read closure ledger: {exc}"]

    baseline = git(root, "rev-parse", CLOSURE_BASELINE)
    if baseline.returncode or baseline.stdout.strip() != CLOSURE_BASELINE:
        errors.append("fixed closureBaseline commit is unavailable")
    if git(root, "merge-base", "--is-ancestor", CLOSURE_BASELINE, "HEAD").returncode:
        errors.append("closureBaseline must be an ancestor of HEAD")

    auto_rows = {work_id: row for work_id, row in rows.items() if row.get("executionClass") == "AUTO_NOW"}
    for work_id, row in auto_rows.items():
        if row.get("entityType") == "TRANCHE_SENTINEL":
            expected = ("PENDING", "VERIFIED_STATIC_SYNTHETIC", "AUTOMATED_TRANCHE_CLOSED")
            actual = (row.get("workStatus"), row.get("evidenceLevel"), row.get("readinessCeiling"))
            if actual != expected:
                errors.append(f"AUTO_NOW tranche sentinel {work_id} must remain {'/'.join(expected)}")
        elif row.get("entityType") == "BOUNDED_SLICE":
            if row.get("workStatus") != "COMPLETE":
                errors.append(f"AUTO_NOW bounded slice {work_id} must be COMPLETE")
        else:
            errors.append(f"AUTO_NOW row {work_id} must be BOUNDED_SLICE or TRANCHE_SENTINEL")

    for work_id, expected in PRE_CLOSURE_ROWS.items():
        row = rows.get(work_id, {})
        actual = (row.get("workStatus"), row.get("evidenceLevel"), row.get("readinessCeiling"))
        if actual != expected:
            errors.append(f"preclosure row {work_id} must remain {'/'.join(expected)}")

    phase6 = rows.get("automation.phase6.closure", {})
    if (phase6.get("workStatus"), phase6.get("evidenceLevel"), phase6.get("readinessCeiling")) != (
        "COMPLETE", "VERIFIED_STATIC_SYNTHETIC", "AUTOMATED_TRANCHE_CLOSED"
    ):
        errors.append("Phase 6 must be COMPLETE/VERIFIED_STATIC_SYNTHETIC/AUTOMATED_TRANCHE_CLOSED")
    tranche = rows.get("tranche.automation.overall", {})
    if tranche.get("nextSlice") != "manual.real-host-observation":
        errors.append("closed automated tranche must point to manual.real-host-observation")

    for work_id in auto_rows:
        required = REQUIRED_EVIDENCE.get(work_id)
        if required is None:
            errors.append(f"AUTO_NOW row lacks explicit required evidence map: {work_id}")
            continue
        actual_refs = refs(rows[work_id])
        if not required.issubset(actual_refs):
            errors.append(f"AUTO_NOW row {work_id} requires report/gate evidence: {';'.join(sorted(required))}")
        for ref in actual_refs:
            if not (root / ref).is_file():
                errors.append(f"missing evidence ref for {work_id}: {ref}")

    for work_id, (expected, blockers, next_slice, note_fragments, evidence) in BOUNDARY_ROWS.items():
        row = rows.get(work_id, {})
        actual = (row.get("executionClass"), row.get("workStatus"), row.get("evidenceLevel"), row.get("readinessCeiling"))
        if actual != expected:
            errors.append(f"boundary row {work_id} changed from {'/'.join(expected)}")
        if row.get("blockers") != blockers:
            errors.append(f"boundary row {work_id} blockers changed")
        if row.get("nextSlice") != next_slice:
            errors.append(f"boundary row {work_id} nextSlice changed")
        if not evidence.issubset(refs(row)):
            errors.append(f"boundary row {work_id} evidence refs changed")
        for fragment in note_fragments:
            if fragment not in row.get("notes", ""):
                errors.append(f"boundary row {work_id} notes lost required semantics: {fragment}")

    if not oracle.is_file():
        errors.append(f"Oracle disposition evidence missing: {ORACLE_PATH}")
    else:
        oracle_rows = read_rows(oracle)
        required_oracle = set(PRE_CLOSURE_ROWS)
        missing = sorted(required_oracle - oracle_rows.keys())
        if missing:
            errors.append(f"Oracle disposition rows missing: {';'.join(missing)}")
        for work_id in sorted(required_oracle & oracle_rows.keys()):
            review = oracle_rows[work_id]
            if review.get("disposition") != "APPROVE":
                errors.append(f"Oracle disposition for {work_id} must be APPROVE")
            if review.get("unresolvedBlockers") != "0":
                errors.append(f"Oracle disposition for {work_id} must have unresolvedBlockers=0")
            if not review.get("reviewRef", "").startswith("session-oracle:"):
                errors.append(f"Oracle disposition for {work_id} requires explicit session-oracle reviewRef")
            if not review.get("reviewScope", "").strip():
                errors.append(f"Oracle disposition for {work_id} requires reviewScope")

    for work_id, commit in PHASE_COMMITS.items():
        if git(root, "merge-base", "--is-ancestor", commit, CLOSURE_BASELINE).returncode:
            errors.append(f"integrated phase commit is not closure-baseline ancestry: {work_id} {commit}")
        for ref in REQUIRED_EVIDENCE[work_id]:
            if git(root, "cat-file", "-e", f"{commit}:{ref}").returncode:
                errors.append(f"integrated phase commit lacks required evidence: {work_id} {commit}:{ref}")

    for source, integrated, expected_patch in PHASE5_SOURCE_PROVENANCE:
        if git(root, "merge-base", "--is-ancestor", integrated, CLOSURE_BASELINE).returncode:
            errors.append(f"Phase 5 integrated commit missing from closure ancestry: {integrated}")
        integrated_patch = stable_patch_id(root, integrated)
        if integrated_patch is None or integrated_patch != expected_patch:
            errors.append(f"Phase 5 integrated stable patch-id mismatch: {source} -> {integrated}")

    report_text = report.read_text(encoding="utf-8") if report.is_file() else ""
    required_report_text = (
        f"closureBaseline: `{CLOSURE_BASELINE}`",
        "Phase 5 reviewed source chain: `aa1c661 + 97dd29d + ace9345`",
        ORACLE_PATH,
        "orchestration-session persistence summary",
        "does not authenticate the session history",
        "final Oracle review remains external",
        "M14 overall remains incomplete",
        "M16 overall remains not started",
        NEXT_PATH,
    )
    for fragment in required_report_text:
        if fragment not in report_text:
            errors.append(f"closure report missing required statement: {fragment}")

    next_text = next_doc.read_text(encoding="utf-8") if next_doc.is_file() else ""
    required_next = (
        "AUTOMATED_TRANCHE_CLOSED",
        REPORT_PATH,
        "M14 overall: IN_PROGRESS",
        "M16 overall: NOT_STARTED",
        "separate reviewed real-host plan",
        "explicit authorization",
        "production HostOperations/bootstrap",
        "manual observation",
    )
    for fragment in required_next:
        if fragment not in next_text:
            errors.append(f"next migration slices missing closure boundary: {fragment}")
    return errors


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--repo-root", type=Path, default=Path(__file__).resolve().parents[2])
    parser.add_argument("--ledger", type=Path)
    parser.add_argument("--report", type=Path)
    parser.add_argument("--next-doc", type=Path)
    parser.add_argument("--oracle", type=Path)
    args = parser.parse_args()
    root = args.repo_root.resolve()
    errors = validate(
        root,
        (args.ledger or root / LEDGER_PATH).resolve(),
        (args.report or root / REPORT_PATH).resolve(),
        (args.next_doc or root / NEXT_PATH).resolve(),
        (args.oracle or root / ORACLE_PATH).resolve(),
    )
    for error in errors:
        print(f"FAIL: {error}", file=sys.stderr)
    if errors:
        return 1
    print(f"PASS: Phase 6 automated tranche closure at {CLOSURE_BASELINE}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
