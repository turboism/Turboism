"""Summarize accepted native cases without copying local paths or model/license data."""
from __future__ import annotations

import argparse
import csv
import hashlib
import json
from pathlib import Path
import statistics

from run_native import properties, save

ORDER = [(arm, round_id) for round_id, arms in enumerate(
    (("native", "legacy", "direct"), ("direct", "native", "legacy"), ("legacy", "direct", "native")), 1)
    for arm in arms]


def case(root: Path) -> dict:
    envelope = json.loads((root / "envelope.json").read_text(encoding="utf-8"))
    assert not envelope.get("profiling", False), "profiled run is never a performance sample"
    assert envelope.get("experiment", "none") == "none" and not envelope.get("experimentShadow", False), "separate experiment matrix required"
    assert envelope["status"] == "PASS" and envelope["cleanup"] == "safe", root.name
    assert envelope["normalExitCode"] == 0, root.name
    assert all(envelope[key] for key in ("inputsUnchanged", "copyUnchanged", "stagedArtifactsUnchanged")), root.name
    load = envelope["load"]
    assert load["shadowComparison"] == "false", "shadow is never a performance sample"
    result = envelope["validation"]
    assert result["status"] == "PASS", root.name
    if envelope["arm"] != "native":
        assert result["liveDisableAndRestoration"] == "PASS", root.name
        assert load["preparation.failures"] == "0" and load["observer.callsBeforeAttach"] == "0", root.name
    state = root / "home/state/texture-upload"
    ready = int(properties(state / "memory/ready.properties")["epochMillis"]) * 1_000_000
    end = int(properties(state / "memory/end.properties")["epochMillis"]) * 1_000_000
    process = [json.loads(line) for line in (root / "process.jsonl").read_text().splitlines()]
    loading = [p for p in process if p["epochNs"] <= ready]
    idle = [p for p in process if ready + 20_000_000_000 <= p["epochNs"] <= end]
    assert loading and idle, root.name
    with (state / "memory/jvm-loading.csv").open() as stream:
        jvm = list(csv.DictReader(stream))
    return {"runId": root.name, "arm": envelope["arm"], "status": envelope["status"],
            "loadSeconds": int(load["agentToDocumentReadyNs"]) / 1e9,
            "processCpuSeconds": int(load["processCpuToDocumentReadyNs"]) / 1e9,
            "preparationMs": int(load.get("preparation.preparationWallNs", "0")) / 1e6,
            "observedAllocatedGB": int(load["observedThreadAllocatedBytes"]) / 1e9,
            "heapAtReadyGiB": int(load["heapUsedAtReadyBytes"]) / 2**30,
            "loadingPeakWorkingSetGiB": max(p["peakWorkingSetBytes"] for p in loading) / 2**30,
            "loadingPeakCommitGiB": max(p["peakCommitBytes"] for p in loading) / 2**30,
            "idleTailWorkingSetGiB": statistics.median(p["workingSetBytes"] for p in idle) / 2**30,
            "loadingGcCount": int(jvm[-1]["gcCount"]) - int(jvm[0]["gcCount"]),
            "loadingGcMs": int(jvm[-1]["gcCollectionTimeMs"]) - int(jvm[0]["gcCollectionTimeMs"])}


def summarize(root: Path, destination: Path) -> dict:
    rows = [case(root / f"{arm}-r{round_id}") for arm, round_id in ORDER]
    destination.mkdir(parents=True, exist_ok=True)
    with (destination / "native-loads.csv").open("w", newline="", encoding="utf-8") as stream:
        writer = csv.DictWriter(stream, fieldnames=list(rows[0]))
        writer.writeheader()
        writer.writerows(rows)
    envelopes = [json.loads((root / row["runId"] / "envelope.json").read_text()) for row in rows]
    for key in ("sourceRevision", "sourceDiffSha256", "runnerSha256", "version", "fixtureBytes"):
        assert len({e[key] for e in envelopes}) == 1, f"mixed inputs: {key}"
    for key in ("fixture", "hostJar", "officialBat", "installedBat", "jogl", "auxiliary"):
        assert len({e["before"][key] for e in envelopes}) == 1, f"mixed artifact: {key}"
    metrics = [key for key in rows[0] if key not in ("runId", "arm", "status")]
    medians = {arm: {key: statistics.median(row[key] for row in rows if row["arm"] == arm)
                     for key in metrics} for arm in ("native", "legacy", "direct")}
    receipt = {"schemaVersion": 1, "runs": [row["runId"] for row in rows], "medians": medians,
               "fixtureBytes": envelopes[0]["fixtureBytes"], "sourceRevision": envelopes[0]["sourceRevision"],
               "sourceDiffSha256": envelopes[0]["sourceDiffSha256"],
               "runnerSha256": envelopes[0]["runnerSha256"],
               "sharedHashes": {key: envelopes[0]["before"][key] for key in
                   ("fixture", "hostJar", "officialBat", "installedBat", "jogl", "auxiliary")},
               "agentHashes": {arm: next(e["before"]["agent"] for e in envelopes if e["arm"] == arm)
                               for arm in ("native", "legacy", "direct")},
               "envelopeHashes": {row["runId"]: hashlib.sha256(
                   (root / row["runId"] / "envelope.json").read_bytes()).hexdigest() for row in rows}}
    save(destination / "native-receipt.json", receipt)
    return receipt


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("root", type=Path)
    parser.add_argument("destination", type=Path)
    args = parser.parse_args()
    print(json.dumps(summarize(args.root, args.destination), indent=2))
