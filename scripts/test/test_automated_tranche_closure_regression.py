#!/usr/bin/env python3
"""Negative regression cases for the Phase 6 closure gate."""

from __future__ import annotations

import csv
import importlib.util
import tempfile
from pathlib import Path

SCRIPT = Path(__file__).with_name("validate_automated_tranche_closure.py")
spec = importlib.util.spec_from_file_location("closure_validator", SCRIPT)
module = importlib.util.module_from_spec(spec)
assert spec.loader
spec.loader.exec_module(module)
ROOT = Path(__file__).resolve().parents[2]
LEDGER = ROOT / module.LEDGER_PATH
REPORT = ROOT / module.REPORT_PATH
NEXT = ROOT / module.NEXT_PATH
ORACLE = ROOT / module.ORACLE_PATH


def load_tsv(path: Path) -> tuple[list[str], list[dict[str, str]]]:
    with path.open(encoding="utf-8", newline="") as handle:
        reader = csv.DictReader(handle, delimiter="\t")
        return list(reader.fieldnames or []), list(reader)


def write_tsv(path: Path, fields: list[str], rows: list[dict[str, str]]) -> None:
    with path.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=fields, delimiter="\t", lineterminator="\n")
        writer.writeheader()
        writer.writerows(rows)


def row(rows: list[dict[str, str]], work_id: str) -> dict[str, str]:
    return next(item for item in rows if item["workId"] == work_id)


def validate_fixture(*, mutate_ledger=None, mutate_oracle=None) -> list[str]:
    ledger_fields, ledger_rows = load_tsv(LEDGER)
    oracle_fields, oracle_rows = load_tsv(ORACLE)
    if mutate_ledger:
        mutate_ledger(ledger_rows)
    if mutate_oracle:
        mutate_oracle(oracle_rows)
    with tempfile.TemporaryDirectory() as directory:
        temp = Path(directory)
        ledger = temp / "ledger.tsv"
        oracle = temp / "oracle.tsv"
        write_tsv(ledger, ledger_fields, ledger_rows)
        write_tsv(oracle, oracle_fields, oracle_rows)
        return module.validate(ROOT, ledger, REPORT, NEXT, oracle)


def expect(name: str, fragment: str, *, mutate_ledger=None, mutate_oracle=None) -> None:
    errors = validate_fixture(mutate_ledger=mutate_ledger, mutate_oracle=mutate_oracle)
    assert any(fragment in error for error in errors), f"{name}: expected {fragment!r}, got {errors}"


def main() -> None:
    assert module.validate(ROOT, LEDGER, REPORT, NEXT, ORACLE) == []

    expect(
        "static incomplete",
        "AUTO_NOW bounded slice static.clipmask must be COMPLETE",
        mutate_ledger=lambda rows: row(rows, "static.clipmask").update(workStatus="IN_PROGRESS"),
    )
    expect(
        "report replaced by plan",
        "requires report/gate evidence",
        mutate_ledger=lambda rows: row(rows, "automation.phase4.build-gates").update(
            evidenceRefs="docs/migration/plans/automated-tranche-completion-plan.md;scripts/test/test_phase4_build_gates.sh"
        ),
    )
    expect(
        "gate replaced by plan",
        "requires report/gate evidence",
        mutate_ledger=lambda rows: row(rows, "automation.phase5.packaging-dryrun").update(
            evidenceRefs="docs/migration/phase5-pre-m16-packaging-dryrun-report.md;docs/migration/plans/automated-tranche-completion-plan.md"
        ),
    )
    expect(
        "disposition missing",
        "Oracle disposition rows missing",
        mutate_oracle=lambda rows: rows.remove(row(rows, "automation.phase3.synthetic-composition")),
    )
    expect(
        "Oracle blocker",
        "unresolvedBlockers=0",
        mutate_oracle=lambda rows: row(rows, "automation.phase5.packaging-dryrun").update(unresolvedBlockers="1"),
    )
    expect(
        "boundary blocker cleared",
        "boundary row manual.real-host-observation blockers changed",
        mutate_ledger=lambda rows: row(rows, "manual.real-host-observation").update(blockers=""),
    )
    expect(
        "boundary next cleared",
        "boundary row r5.real-ui nextSlice changed",
        mutate_ledger=lambda rows: row(rows, "r5.real-ui").update(nextSlice=""),
    )

    old_provenance = module.PHASE5_SOURCE_PROVENANCE
    try:
        source, integrated, _ = old_provenance[0]
        module.PHASE5_SOURCE_PROVENANCE = ((source, integrated, "0" * 40),)
        errors = module.validate(ROOT, LEDGER, REPORT, NEXT, ORACLE)
        assert any("Phase 5 integrated stable patch-id mismatch" in error for error in errors), errors
    finally:
        module.PHASE5_SOURCE_PROVENANCE = old_provenance

    old_commits = module.PHASE_COMMITS
    try:
        module.PHASE_COMMITS = dict(old_commits, **{"phase0.scope-ledger": "d915b9f"})
        errors = module.validate(ROOT, LEDGER, REPORT, NEXT, ORACLE)
        assert any("not closure-baseline ancestry" in error or "lacks required evidence" in error for error in errors), errors
    finally:
        module.PHASE_COMMITS = old_commits

    print("PASS: automated tranche closure negative regression cases")


if __name__ == "__main__":
    main()
