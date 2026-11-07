"""Separate hotspot screening/confirmation cohorts; never pool different probe builds."""
import argparse
import csv
import hashlib
import json
from pathlib import Path
import statistics


def props(path):
    return dict(line.split("=", 1) for line in path.read_text(encoding="latin-1").splitlines()
                if line and not line.startswith("#") and "=" in line)


def row(path):
    e = json.loads((path / "envelope.json").read_text(encoding="utf-8"))
    base = {"runId": path.name, "status": e["status"], "experiment": e.get("experiment", "none"),
            "shadow": e.get("experimentShadow", False), "probeSha256": e["before"].get("experimentAgent", ""),
            "runnerSha256": e["runnerSha256"],
            "role": "failure" if e["status"] != "PASS" else ("shadow" if e.get("experimentShadow", False)
                    else ("preflight" if path.name == "control-s3" else "performance"))}
    if e["status"] != "PASS":
        return base
    if e["cleanup"] != "safe" or e["normalExitCode"] != 0 or not all(e[k] for k in
            ("inputsUnchanged", "copyUnchanged", "stagedArtifactsUnchanged")):
        raise AssertionError("acceptance mismatch: " + path.name)
    witness = e["experimentVerify"]
    if witness.get("status") != "PASS" or witness.get("restoration") != "PASS":
        raise AssertionError("witness failed: " + path.name)
    state = path / "home/state/texture-upload"
    ready = int(props(state / "memory/ready.properties")["epochMillis"]) * 1_000_000
    end = int(props(state / "memory/end.properties")["epochMillis"]) * 1_000_000
    samples = [json.loads(line) for line in (path / "process.jsonl").read_text().splitlines()]
    loading = [s for s in samples if s["epochNs"] <= ready]
    idle = [s for s in samples if ready + 20_000_000_000 <= s["epochNs"] <= end]
    load, counters = e["load"], e["experimentLoad"]
    base.update({"loadSeconds": int(load["agentToDocumentReadyNs"]) / 1e9,
                 "cpuSeconds": int(load["processCpuToDocumentReadyNs"]) / 1e9,
                 "observedAllocatedGB": int(load["observedThreadAllocatedBytes"]) / 1e9,
                 "peakWorkingSetGiB": max(s["peakWorkingSetBytes"] for s in loading) / 2**30,
                 "peakCommitGiB": max(s["peakCommitBytes"] for s in loading) / 2**30,
                 "idleTailWorkingSetGiB": statistics.median(s["workingSetBytes"] for s in idle) / 2**30,
                 "imageReadSeconds": int(counters["imageNs"]) / 1e9,
                 "archiveReadSeconds": int(counters["archiveNs"]) / 1e9,
                 "texturePrepareSeconds": int(load["preparation.preparationWallNs"]) / 1e9,
                 "thumbnailSha256": witness["thumbnailSha256"], "archiveSha256": witness["archiveSha256"]})
    return base


def summarize(root, output):
    rows = [row(p.parent) for p in sorted(root.glob("*/envelope.json"))]
    passed = [r for r in rows if r["status"] == "PASS"]
    for key in ("thumbnailSha256", "archiveSha256"):
        if len({r[key] for r in passed}) != 1: raise AssertionError("cross-run " + key)
    fields = list(dict.fromkeys(k for r in rows for k in r))
    output.mkdir(parents=True, exist_ok=True)
    with (output / "all-attempts.csv").open("w", newline="", encoding="utf-8") as stream:
        writer = csv.DictWriter(stream, fieldnames=fields); writer.writeheader(); writer.writerows(rows)
    by_name = {r["runId"]: r for r in rows}
    cohorts = {
        "large-screen": (("control-c1", "control-c2"), ("large-r1", "large-r2")),
        "large-confirm": (("control-h1", "control-h2"), ("large-r3", "large-r4")),
        "png-screen": (("control-c2", "control-c3"), ("png-r1", "png-r2")),
        "archive-screen": (("control-c3", "control-c4"), ("archive-r1", "archive-r2")),
        "warp-screen": (("control-c4", "control-c5"), ("warp-r1", "warp-r2")),
        "float-screen": (("control-g1", "control-g2"), ("float-r1", "float-r2")),
    }
    metrics = ("loadSeconds", "cpuSeconds", "observedAllocatedGB", "peakWorkingSetGiB", "peakCommitGiB",
               "idleTailWorkingSetGiB", "imageReadSeconds", "archiveReadSeconds", "texturePrepareSeconds")
    report = {}
    for name, (controls, candidates) in cohorts.items():
        selected = [by_name[n] for n in controls + candidates]
        if any(r["status"] != "PASS" or r["shadow"] for r in selected): raise AssertionError("bad timing sample")
        if len({r["probeSha256"] for r in selected}) != 1: raise AssertionError("mixed probe cohort")
        if len({r["runnerSha256"] for r in selected}) != 1: raise AssertionError("mixed runner cohort")
        report[name] = {"controls": controls, "candidates": candidates,
                        "controlMedian": {k: statistics.median(by_name[n][k] for n in controls) for k in metrics},
                        "candidateMedian": {k: statistics.median(by_name[n][k] for n in candidates) for k in metrics}}
    # Scalar-only receipts, no fixture paths/license state.
    envelopes = {}
    for p in sorted(root.glob("*/envelope.json")):
        e = json.loads(p.read_text(encoding="utf-8"))
        envelopes[p.parent.name] = {"envelopeSha256": hashlib.sha256(p.read_bytes()).hexdigest(),
                                    "status": e["status"], "artifactHashes": e["before"],
                                    "runnerSha256": e["runnerSha256"]}
    receipt = {"cohorts": report, "acceptedAttempts": len(passed), "failedAttempts": len(rows) - len(passed),
               "sharedThumbnailSha256": passed[0]["thumbnailSha256"], "sharedArchiveSha256": passed[0]["archiveSha256"],
               "attempts": envelopes, "warning": "Two samples/arm/cohort, noisy working host; no stable speedup or retained-memory guarantee."}
    (output / "screening.json").write_text(json.dumps(receipt, indent=2), encoding="utf-8")
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("root", type=Path); parser.add_argument("output", type=Path)
    args = parser.parse_args(); summarize(args.root, args.output)
