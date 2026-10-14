#!/usr/bin/env python3
"""Verifies the auto-atlas host probe output report JSON and result.properties."""
import json
import sys
from pathlib import Path

def require(condition, message):
    if not condition:
        raise ValueError(f"AUTO_ATLAS_VERIFY: {message}")

def verify_report(state_dir: Path):
    if state_dir.is_file():
        report_file = state_dir
        props_file = state_dir.parent / "result.properties"
    else:
        report_file = state_dir / "auto-atlas-probe-report.json"
        props_file = state_dir / "result.properties"

    require(report_file.exists(), f"report JSON missing: {report_file}")
    data = json.loads(report_file.read_text(encoding="utf-8"))

    # Verify properties file if present
    if props_file.exists():
        lines = props_file.read_text(encoding="utf-8").splitlines()
        props = dict(line.split("=", 1) for line in lines if "=" in line and not line.startswith("#"))
        require(props.get("status") == "PASS", f"result.properties status not PASS: {props.get('status')}")

    require(data.get("schemaVersion") == "auto-atlas-probe-v1", "schemaVersion mismatch")
    require(data.get("status") == "PASS", f"probe status not PASS: {data.get('status')}, failure: {data.get('failure')}")
    require(data.get("supportsAppendPreCheck") is True, "capability pre-check failed")
    require(data.get("zeroAtlasAtStart") is True, "zero atlas start requirement violated")
    require(bool(data.get("page1Id")), "missing page1Id")
    require(bool(data.get("page2Id")), "missing page2Id")
    require(data.get("page1Id") != data.get("page2Id"), "page1Id and page2Id must be distinct")
    require(data.get("page1GuidPreserved") is True, "page 1 GUID was not preserved across second append")
    require(data.get("mesh1UvsPreserved") is True, "mesh 1 UVs were mutated by second append")
    require(data.get("singleRootHistoryEntry") is True, "second append transaction produced multiple history entries")
    require(data.get("undoRemovedPage2") is True, "undo failed to remove page 2")
    require(data.get("mesh2RemovedOnUndo") is True, "undo failed to remove mesh 2")
    require(data.get("redoRestoredPage2") is True, "redo failed to restore page 2")
    require(data.get("nativeBakeSamplingVerified") is True, "native bake sampling verification failed")
    require(data.get("transactionRolledBack") is True, "transaction failed to roll back")
    require(data.get("historyPositionUnchangedOnRollback") is True, "history position mutated on rollback")
    require(data.get("rollbackPreservedAtlasCount") is True, "rollback failed to discard 3rd page")
    require(data.get("mesh3DiscardedOnRollback") is True, "mesh 3 survived rollback")
    require(data.get("rawImagesDiscardedOnRollback") is True, "raw image survived rollback")
    require(data.get("modelImagesDiscardedOnRollback") is True, "model image survived rollback")

    print(f"PASS: all auto-atlas invariants verified from {report_file}")

if __name__ == "__main__":
    if len(sys.argv) < 2:
        print("usage: verify-auto-atlas.py <state-dir-or-report-json>", file=sys.stderr)
        sys.exit(1)
    verify_report(Path(sys.argv[1]))
