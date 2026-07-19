#!/usr/bin/env python3
"""Authoritative admission gate for the legacy-plugin B1 pure-business wave."""
from __future__ import annotations

import csv
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
MATRIX = ROOT / "docs/migration/capabilities/legacy-framework-capability-extraction.tsv"
PLAN = ROOT / "docs/migration/plans/legacy-plugin-b1-execution-plan.md"
PROMPT = ROOT / "docs/migration/prompts/legacy-plugin-b1-orchestrator-prompt.md"
CLOSURE = ROOT / "docs/migration/legacy-plugin-migration-foundation-closure-report.md"
CONTRACT = ROOT / "docs/migration/behavior-specs/legacy-plugin-b1-pure-behaviors.md"
SOURCE_BOUNDARY = ROOT / "docs/migration/salvage-notes/legacy-plugin-b1-source-boundary.md"
LEGACY_ROOT = Path("<local-workspace>/turboism-legacy")
BASELINE = "73ead840ecdb2eb1280c51c355ad2eade787ac24"
LEGACY_REVISION = "2411b3512380199726126786efa821231fa129d8"

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


def scan_authority_documents() -> None:
    with tempfile.TemporaryDirectory(prefix="turboism-b1-docs-") as directory:
        target = Path(directory)
        shutil.copy2(CONTRACT, target / CONTRACT.name)
        shutil.copy2(SOURCE_BOUNDARY, target / SOURCE_BOUNDARY.name)
        run(sys.executable, "scripts/test/scan_migration_docs_safety.py", str(target))


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
    authorities = (PLAN, PROMPT, CLOSURE, CONTRACT, SOURCE_BOUNDARY)
    for path in authorities:
        if not path.is_file():
            raise SystemExit(f"B1 authority document missing: {path.relative_to(ROOT)}")
    plan = PLAN.read_text(encoding="utf-8")
    prompt = PROMPT.read_text(encoding="utf-8")
    contract = CONTRACT.read_text(encoding="utf-8")
    boundary = SOURCE_BOUNDARY.read_text(encoding="utf-8")
    for _plugin, behavior in sorted(EXPECTED):
        if behavior not in plan or behavior not in prompt or behavior not in contract:
            raise SystemExit(f"B1 behavior missing from authority chain: {behavior}")
    required_contract_markers = (
        BASELINE,
        "b1.domain",
        "b1.application",
        "PARTIAL_PERSISTENCE",
        "currentColor",
    )
    for marker in required_contract_markers:
        if marker not in contract:
            raise SystemExit(f"B1 contract marker missing: {marker}")
    required_boundary_markers = (
        str(LEGACY_ROOT),
        LEGACY_REVISION,
        "listed line range",
        "stop condition",
    )
    for marker in required_boundary_markers:
        if marker not in boundary:
            raise SystemExit(f"B1 source-boundary marker missing: {marker}")
    if not LEGACY_ROOT.is_dir():
        raise SystemExit(f"B1 legacy root missing: {LEGACY_ROOT}")
    actual_revision = subprocess.run(
        ("git", "-C", str(LEGACY_ROOT), "rev-parse", "HEAD"),
        check=True,
        capture_output=True,
        text=True,
    ).stdout.strip()
    if actual_revision != LEGACY_REVISION:
        raise SystemExit(
            f"B1 legacy revision drift: expected={LEGACY_REVISION} actual={actual_revision}"
        )


def main() -> None:
    run("bash", "scripts/test/test_m12_plugin_readiness_gate.sh")
    run(sys.executable, "scripts/test/test_legacy_plugin_b1_source_boundaries.py")
    run(sys.executable, "scripts/test/check_legacy_plugin_b1_source_boundaries.py")
    run(sys.executable, "scripts/test/test_legacy_framework_capability_extraction.py")
    run(sys.executable, "scripts/test/test_legacy_framework_capability_extraction_mutations.py")
    run("bash", "scripts/test/test_migration_docs_safety_scanner.sh")
    scan_authority_documents()
    run("bash", "scripts/test/test_host_ingress_ownership_structure.sh")
    verify_authorized_rows(load_rows())
    verify_documents()
    print("PASS: legacy plugin B1 admission (14 pure-business behaviors)")


if __name__ == "__main__":
    main()
