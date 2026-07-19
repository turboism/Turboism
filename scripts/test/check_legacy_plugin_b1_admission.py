#!/usr/bin/env python3
"""Authoritative admission gate for the legacy-plugin B1 pure-business wave."""
from __future__ import annotations

import csv
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
MATRIX = ROOT / "docs/migration/capabilities/legacy-framework-capability-extraction.tsv"
PLAN = ROOT / "docs/migration/plans/legacy-plugin-b1-execution-plan.md"
PROMPT = ROOT / "docs/migration/prompts/legacy-plugin-b1-orchestrator-prompt.md"
CLOSURE = ROOT / "docs/migration/legacy-plugin-migration-foundation-closure-report.md"

EXPECTED = {
    ("turboism.bounding-box", "bounding-box.feature-settings"),
    ("turboism.clip-mask", "clip-mask.analyze"),
    ("turboism.context-menu", "context-menu.registration-lifecycle"),
    ("turboism.log-filter", "log-filter.match-policy"),
    ("turboism.log-filter", "log-filter.settings"),
    ("turboism.main-toolbar", "main-toolbar.icon-state"),
    ("turboism.parameter", "parameter.csv-codec"),
    ("turboism.perf-opt", "perf.fps-toggle"),
    ("turboism.project-panel", "project-panel.state-model"),
    ("turboism.psd-import", "psd.action-lifecycle"),
    ("turboism.render-opt", "render.opt-in-state"),
    ("turboism.ui-theme", "ui-theme.package-catalog"),
    ("turboism.ui-theme", "ui-theme.package-codec"),
    ("turboism.ui-theme", "ui-theme.generate-edit"),
}


def run(*command: str) -> None:
    subprocess.run(command, cwd=ROOT, check=True)


def load_rows() -> list[dict[str, str]]:
    with MATRIX.open(encoding="utf-8", newline="") as source:
        return list(csv.DictReader(source, delimiter="\t"))


def verify_authorized_rows(rows: list[dict[str, str]]) -> None:
    b1_rows = [row for row in rows if row["unlockLevel"] == "B1_PURE_READY"]
    blocked = [row["behavior"] for row in b1_rows if row["missingFoundation"]]
    if blocked:
        raise SystemExit(f"B1 rows retain missing foundation: {blocked}")
    actual = {(row["plugin"], row["behavior"]) for row in b1_rows}
    if actual != EXPECTED:
        missing = sorted(EXPECTED - actual)
        extra = sorted(actual - EXPECTED)
        raise SystemExit(f"B1 authorization drift: missing={missing} extra={extra}")


def verify_documents() -> None:
    for path in (PLAN, PROMPT, CLOSURE):
        if not path.is_file():
            raise SystemExit(f"B1 authority document missing: {path.relative_to(ROOT)}")
    plan = PLAN.read_text(encoding="utf-8")
    prompt = PROMPT.read_text(encoding="utf-8")
    for _plugin, behavior in sorted(EXPECTED):
        if behavior not in plan or behavior not in prompt:
            raise SystemExit(f"B1 behavior missing from plan/prompt: {behavior}")


def main() -> None:
    run("bash", "scripts/test/test_m12_plugin_readiness_gate.sh")
    run(sys.executable, "scripts/test/test_legacy_framework_capability_extraction.py")
    run(sys.executable, "scripts/test/test_legacy_framework_capability_extraction_mutations.py")
    run("bash", "scripts/test/test_migration_docs_safety_scanner.sh")
    run("bash", "scripts/test/test_host_ingress_ownership_structure.sh")
    verify_authorized_rows(load_rows())
    verify_documents()
    print("PASS: legacy plugin B1 admission (14 pure-business behaviors)")


if __name__ == "__main__":
    main()
