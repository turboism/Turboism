"""Attribute retained JFR edge samples; never launches or accepts a host run."""
import argparse
from collections import Counter
import datetime
import hashlib
import json
from pathlib import Path
import runpy
import subprocess


EDGE = "com.live2d.graphics3d.editableMesh.triangulation.j"
HOST = "com.live2d.graphics3d.editableMesh.triangulation.h"
READ_EVENTS = runpy.run_path(str(Path(__file__).resolve().parents[1]
                                / "analyze-resource-windows.py"))["jfr_events"]


def require(condition, message):
    if not condition:
        raise ValueError(message)


def sha(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def frames(value):
    result = []
    for frame in (value.get("stackTrace") or {}).get("frames", []):
        method = frame.get("method") or {}
        owner = (method.get("type") or {}).get("name")
        result.append((owner.replace("/", ".") if isinstance(owner, str) else None,
                       method.get("name"), method.get("descriptor"),
                       frame.get("bytecodeIndex")))
    return result


def caller(stack):
    # Skip only edge constructors. An enclosing h.c frame is not automatically
    # the allocating method: h.c can call another allocating native method.
    return next((f for f in stack if f[:2] != (EDGE, "<init>")),
                (None, None, None, None))


class Pipe:
    def __init__(self, stream):
        self.stream = stream

    def open(self):
        return self.stream


def ranked(counter, total):
    return [{"frame": list(key), "estimatedWeightBytes": weight,
             "percentOfEdgeWeight": 100 * weight / total if total else 0}
            for key, weight in counter.most_common()]


def analyze(events, start, end, raw):
    counts = Counter()
    weights = Counter()
    callers = Counter()
    leaves = Counter()
    host_frames = Counter()
    examples = {}
    for event in events:
        require(event["type"] == "jdk.ObjectAllocationSample", "wrong event type")
        counts["allAllocationEvents"] += 1
        value = event["values"]
        epoch = datetime.datetime.fromisoformat(value["startTime"]).timestamp() * 1000
        if not start <= epoch <= end:
            continue
        counts["firstAllocationEvents"] += 1
        weight = value["weight"]
        require(type(weight) is int and weight >= 0, "invalid allocation weight")
        weights["allClassesEstimatedWeightBytes"] += weight
        owner = (value.get("objectClass") or {}).get("name")
        if not isinstance(owner, str) or owner.replace("/", ".") != EDGE:
            continue
        counts["edgeAllocationEvents"] += 1
        weights["edgeEstimatedWeightBytes"] += weight
        stack = frames(value)
        leaf = stack[0] if stack else (None, None, None, None)
        site = caller(stack)
        leaves[leaf] += weight
        callers[site] += weight
        examples.setdefault(site, stack)
        host_c = next((f for f in stack if f[:3] == (HOST, "c", "()V")), None)
        if host_c is not None:
            counts["edgeEventsWithInclusiveHostC"] += 1
            weights["edgeInclusiveHostCEstimatedWeightBytes"] += weight
            host_frames[host_c] += weight
        if site[:3] == (HOST, "c", "()V"):
            weights["edgeDirectHostCEstimatedWeightBytes"] += weight
        if site[0] is None:
            weights["edgeUnresolvedCallerEstimatedWeightBytes"] += weight
        raw.write(json.dumps({"epochMillis": epoch, "estimatedWeightBytes": weight,
                              "stack": stack,
                              "stackTruncated": (value.get("stackTrace") or {}).get("truncated")}) + "\n")
    total = weights["edgeEstimatedWeightBytes"]
    require(sum(callers.values()) == sum(leaves.values()) == total, "site partition mismatch")
    return {"counts": dict(counts), "weights": dict(weights),
            "edgeDirectHostCWeightPercent": 100 * weights["edgeDirectHostCEstimatedWeightBytes"] / total
            if total else 0,
            "allocationCallerSites": ranked(callers, total),
            "allocationLeafSites": ranked(leaves, total),
            "inclusiveHostCSites": ranked(host_frames, total),
            "topCallerExamples": [{"caller": list(site), "stack": examples[site]}
                                  for site, _ in callers.most_common(8)]}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--evidence-dir", type=Path, required=True)
    parser.add_argument("--reference-sha256", required=True)
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()
    evidence = args.evidence_dir.resolve()
    reference_path = evidence / "hotspot-review.json"
    require(sha(reference_path) == args.reference_sha256, "reference report drift")
    reference = json.loads(reference_path.read_text())
    runs_path = evidence / "final-ab-runs.json"
    runs = json.loads(runs_path.read_text())
    args.out.mkdir(parents=True)
    report = {"status": "RETAINED_EDGE_ALLOCATION_CALLERS_ATTRIBUTED",
              "productionAcceptance": "HOLD_PRODUCTION_ACCEPTANCE",
              "newHostTasksSubmitted": 0, "officialClassesExecuted": False,
              "inputs": {str(p): sha(p) for p in
                         (reference_path, runs_path, Path(__file__),
                          Path(__file__).resolve().parents[1] / "analyze-resource-windows.py")},
              "legs": {},
              "limitations": [
                  "Sample weights are estimates, not exact allocation counts, live bytes or causal CPU cost.",
                  "Caller and inclusive frames are separate; inlining, absent or truncated frames limit attribution.",
                  "BCIs must be matched to actual defined bytecode before assigning a constructor site.",
                  "No production transformation, host benefit or theoretical bottleneck is proved."]}
    for leg in ("on", "off"):
        key = "tlprod-5302-" + leg
        prior = reference["legs"][leg]
        window_path = evidence / (key + "-window-analysis.json")
        require(sha(window_path) == prior["inputs"][str(window_path)], "window drift")
        window = json.loads(window_path.read_text())
        first = next(w for w in window["windows"] if w["phase"] == "operation" and w["operation"] == 1)
        bounds = [first["startEpochMillis"], first["endEpochMillis"]]
        require(bounds == prior["firstOperationEpochMillis"], "operation bounds drift")
        recording = Path(runs[key]["taskDir"]) / "turboism-home/atlas-profiling.jfr"
        require(sha(recording) == prior["inputs"][str(recording)], "recording drift")
        raw_path = args.out / (leg + "-edge-samples.jsonl")
        proc = subprocess.Popen(["jfr", "print", "--json", "--stack-depth", "32", "--events",
                                 "jdk.ObjectAllocationSample", str(recording)],
                                stdout=subprocess.PIPE, text=True)
        try:
            with raw_path.open("x") as raw:
                result = analyze(READ_EVENTS(Pipe(proc.stdout)), *bounds, raw)
            require(proc.wait(timeout=10) == 0, "JFR export failed")
        finally:
            if proc.poll() is None:
                proc.terminate()
                proc.wait(timeout=10)
        expected_edge = dict(prior["firstAllocatedClassEstimatedWeights"])[EDGE]
        require(result["counts"]["allAllocationEvents"] == prior["allocations"]["allEvents"],
                "full event count disagrees with prior report")
        require(result["counts"]["firstAllocationEvents"] == prior["firstAllocationEvents"],
                "operation event count disagrees with prior report")
        require(result["weights"]["allClassesEstimatedWeightBytes"] ==
                prior["allocations"]["firstEstimatedWeightBytes"], "all-class weight disagrees")
        require(result["weights"]["edgeEstimatedWeightBytes"] == expected_edge, "edge weight disagrees")
        require(sha(recording) == prior["inputs"][str(recording)], "recording changed during analysis")
        require(sha(window_path) == prior["inputs"][str(window_path)], "window changed during analysis")
        result["inputs"] = {str(p): sha(p) for p in (window_path, recording, raw_path)}
        result["firstOperationEpochMillis"] = bounds
        result["priorAggregateAgreement"] = "PASS"
        report["legs"][leg] = result
        print(leg, json.dumps({k: result[k] for k in
              ("counts", "weights", "edgeDirectHostCWeightPercent", "allocationCallerSites")}), flush=True)
    with (args.out / "allocation-site-review.json").open("x") as output:
        json.dump(report, output, indent=2)
        output.write("\n")


if __name__ == "__main__":
    main()
