#!/usr/bin/env python3
from __future__ import annotations

import csv
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
VALIDATOR = ROOT / "scripts/test/test_legacy_framework_capability_extraction.py"
MATRIX_REL = Path("docs/migration/capabilities/legacy-framework-capability-extraction.tsv")
CATALOG_REL = Path("docs/migration/capabilities/capability-catalog.tsv")
READINESS_REL = Path("docs/migration/capabilities/plugin-readiness-matrix.tsv")


def read_rows(path: Path) -> tuple[list[str], list[dict[str, str]]]:
    with path.open(encoding="utf-8", newline="") as handle:
        reader = csv.DictReader(handle, delimiter="\t")
        return list(reader.fieldnames or []), list(reader)


def write_rows(path: Path, fields: list[str], rows: list[dict[str, str]]) -> None:
    with path.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=fields, delimiter="\t", lineterminator="\n")
        writer.writeheader()
        writer.writerows(rows)


def prepare_tree(tmp: Path) -> None:
    for relative in (
        MATRIX_REL,
        CATALOG_REL,
        READINESS_REL,
        Path("docs/migration/plans/legacy-framework-capability-extraction-prd.md"),
        Path("scripts/test/test_legacy_framework_capability_extraction.py"),
    ):
        target = tmp / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(ROOT / relative, target)
    source_notes = ROOT / "docs/migration/salvage-notes"
    target_notes = tmp / "docs/migration/salvage-notes"
    target_notes.mkdir(parents=True, exist_ok=True)
    for note in source_notes.glob("legacy-turboism-*.md"):
        shutil.copy2(note, target_notes / note.name)


def run_mutation(name: str, mutate) -> None:
    with tempfile.TemporaryDirectory(prefix="legacy-capability-mutation-") as directory:
        tmp = Path(directory)
        prepare_tree(tmp)
        mutate(tmp)
        result = subprocess.run(
            [sys.executable, str(tmp / "scripts/test/test_legacy_framework_capability_extraction.py")],
            cwd=tmp,
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            check=False,
        )
        if result.returncode == 0:
            print(f"FAIL: mutation was accepted: {name}", file=sys.stderr)
            print(result.stdout, file=sys.stderr)
            raise SystemExit(1)


def mutate_matrix(tmp: Path, behavior: str, field: str, value: str) -> None:
    path = tmp / MATRIX_REL
    fields, rows = read_rows(path)
    row = next(item for item in rows if item["behavior"] == behavior)
    row[field] = value
    write_rows(path, fields, rows)


def mutate_catalog(tmp: Path, capability: str, field: str, value: str) -> None:
    path = tmp / CATALOG_REL
    fields, rows = read_rows(path)
    row = next(item for item in rows if item["capabilityId"] == capability)
    row[field] = value
    write_rows(path, fields, rows)


def remove_plugin(tmp: Path, plugin: str) -> None:
    path = tmp / MATRIX_REL
    fields, rows = read_rows(path)
    write_rows(path, fields, [row for row in rows if row["plugin"] != plugin])


def mutate_prd_row_count(tmp: Path, count: int) -> None:
    path = tmp / "docs/migration/plans/legacy-framework-capability-extraction-prd.md"
    text = path.read_text(encoding="utf-8")
    text = text.replace("54 reviewable behavior rows", f"{count} reviewable behavior rows")
    path.write_text(text, encoding="utf-8")


def mutate_readiness(tmp: Path, plugin: str, capability: str) -> None:
    path = tmp / READINESS_REL
    fields, rows = read_rows(path)
    row = next(item for item in rows if item["plugin"] == plugin)
    row["requiredCapabilities"] += ";" + capability
    write_rows(path, fields, rows)


def main() -> None:
    mutations = [
        ("absolute evidence", lambda tmp: mutate_matrix(tmp, "clip-mask.inspect", "legacyEvidence", "/workspace/legacy/plugin.java")),
        ("missing plugin", lambda tmp: remove_plugin(tmp, "turboism.texture-atlas")),
        ("invalid salvage enum", lambda tmp: mutate_matrix(tmp, "clip-mask.inspect", "salvageLevel", "L1")),
        ("duplicate behavior", lambda tmp: mutate_matrix(tmp, "clip-mask.analyze", "behavior", "clip-mask.inspect")),
        ("missing salvage note", lambda tmp: (tmp / "docs/migration/salvage-notes/legacy-turboism-clip-mask.md").unlink()),
        ("unknown owner", lambda tmp: mutate_matrix(tmp, "clip-mask.inspect", "targetOwner", "PLUGIN+BRIDGE")),
        ("B2 write risk", lambda tmp: mutate_matrix(tmp, "clip-mask.inspect", "riskClass", "WRITE")),
        ("B2 transaction gate", lambda tmp: mutate_matrix(tmp, "clip-mask.inspect", "evidenceGate", "SPEC+SDK_BOUNDARY+PERMISSION+FAKE_HOST+TRANSACTION+LIFECYCLE")),
        ("write without rollback", lambda tmp: mutate_matrix(tmp, "parameter.csv-import", "evidenceGate", "SPEC+SDK_BOUNDARY+PERMISSION+FAKE_HOST+TRANSACTION+LIFECYCLE+MANUAL_WRITE")),
        ("sidecar with model transaction", lambda tmp: mutate_matrix(tmp, "project-panel.preview-cleanup", "requiredCapabilities", "cubism.recent-preview.manage;plugin.storage;cubism.transaction.real-write-undo")),
        ("implemented foundation missing", lambda tmp: mutate_matrix(tmp, "clip-mask.inspect", "missingFoundation", "plugin.task.schedule")),
        ("clip async blocker removed", lambda tmp: mutate_matrix(tmp, "clip-mask.refresh-lifecycle", "missingFoundation", "")),
        ("parameter async blocker removed", lambda tmp: mutate_matrix(tmp, "parameter.edit-plan", "missingFoundation", "cubism.parameter-binding.read")),
        ("mesh async blocker removed", lambda tmp: mutate_matrix(tmp, "mesh.apply-to-children", "missingFoundation", "cubism.transaction.real-write-undo")),
        ("psd async blocker removed", lambda tmp: mutate_matrix(tmp, "psd.candidate-report", "missingFoundation", "cubism.psd.layer-relationship.read;cubism.psd.binding-candidate.read;cubism.psd.layer-bounds.read")),
        ("render async blocker removed", lambda tmp: mutate_matrix(tmp, "render.status-presentation", "missingFoundation", "")),
        ("atlas async blocker removed", lambda tmp: mutate_matrix(tmp, "texture-atlas.inspect", "missingFoundation", "")),
        ("unknown capability", lambda tmp: mutate_matrix(tmp, "clip-mask.inspect", "requiredCapabilities", "missing.capability")),
        ("invalid catalog category", lambda tmp: mutate_catalog(tmp, "runtime.host-read.async", "category", "bridge")),
        ("invalid threading budget", lambda tmp: mutate_catalog(tmp, "runtime.host-read.async", "threadingBudget", "anything")),
        ("async fixture evidence erased", lambda tmp: mutate_catalog(tmp, "runtime.host-read.async", "fakeHostFixture", "FakeHostRead")),
        ("async evidence overstated", lambda tmp: mutate_catalog(tmp, "runtime.host-read.async", "status", "adapter-ready")),
        ("async adapter owner drift", lambda tmp: mutate_catalog(tmp, "runtime.host-read.async", "adapterOwner", "dev.turboism.adapter.cubism")),
        ("readiness falsely consumes generic async foundation", lambda tmp: mutate_readiness(tmp, "turboism.clip-mask", "runtime.host-read.async")),
        ("PRD row-count drift", lambda tmp: mutate_prd_row_count(tmp, 53)),
        ("unconsumed foundation readiness", lambda tmp: mutate_readiness(tmp, "turboism.main-toolbar", "plugin.localization")),
    ]
    for name, mutate in mutations:
        run_mutation(name, mutate)
    print(f"PASS: legacy framework capability extraction mutation suite ({len(mutations)} cases)")


if __name__ == "__main__":
    main()
