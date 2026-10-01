#!/usr/bin/env python3
"""Analyze explicit driver windows; never decides production/performance acceptance."""
import argparse
import csv
import datetime
import hashlib
import json
from pathlib import Path
import re
import statistics


def require(condition, message):
    if not condition:
        raise ValueError(message)


def jfr_events(path):
    """Read the standard jfr-print envelope one event at a time, bounded by event size."""
    decoder = json.JSONDecoder()
    with path.open() as stream:
        buffer = stream.read(65536)
        header = re.match(r'\s*\{\s*"recording"\s*:\s*\{\s*"events"\s*:\s*\[', buffer)
        require(header is not None, "unsupported JFR JSON envelope")
        buffer = buffer[header.end():]
        first = True
        while True:
            buffer = buffer.lstrip()
            while not buffer:
                buffer = stream.read(65536).lstrip()
                require(bool(buffer), "truncated JFR events")
            if buffer.startswith("]"):
                tail = buffer[1:] + stream.read()
                require(re.fullmatch(r'\s*\}\s*\}\s*', tail) is not None, "invalid JFR JSON tail")
                return
            if not first:
                require(buffer.startswith(","), "missing JFR event separator")
                buffer = buffer[1:].lstrip()
            while True:
                try:
                    event, end = decoder.raw_decode(buffer)
                    break
                except json.JSONDecodeError:
                    chunk = stream.read(65536)
                    require(bool(chunk), "truncated or invalid JFR event")
                    buffer = (buffer + chunk).lstrip()
            require(isinstance(event, dict), "invalid JFR event object")
            yield event
            buffer = buffer[end:]
            first = False


def file_sha256(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def analyze(marker_path, sample_path, jfr_path=None):
    with marker_path.open() as stream:
        markers = list(csv.DictReader(stream, delimiter="\t"))
    expected = [("baseline-start", 0), ("baseline-end", 0)]
    for operation in range(1, 4):
        expected.extend((phase, operation) for phase in
                        ("operation-start", "operation-end", "retained-start", "retained-end"))
    require(len(markers) == len(expected), "incomplete resource protocol")
    for marker, (phase, operation) in zip(markers, expected):
        require(marker["phase"] == phase and int(marker["operation"]) == operation,
                "unexpected resource phase/operation")
        for key in ("epochMillis", "monotonicNanos", "heapUsedBytes"):
            marker[key] = int(marker[key])
        require(marker["heapUsedBytes"] >= 0, "invalid heap sample")
    for before, after in zip(markers, markers[1:]):
        require(after["monotonicNanos"] > before["monotonicNanos"], "unordered monotonic markers")
        require(after["epochMillis"] >= before["epochMillis"], "wall clock moved backwards")

    samples = [json.loads(line) for line in sample_path.read_text().splitlines()]
    require(len(samples) >= 2, "insufficient process samples")
    cgroups = {(r["cgroup"], r["cgroupInode"]) for r in samples}
    require(len(cgroups) == 1, "task cgroup identity changed")
    ticks = {r["ticksPerSecond"] for r in samples}
    require(len(ticks) == 1 and next(iter(ticks)) > 0, "invalid tick rate")
    hz = next(iter(ticks))
    java_ids = {(r["pid"], r["startTicks"]) for sample in samples
                for r in sample["records"] if r["role"] == "host-java"}
    require(len(java_ids) == 1, "Java process identity changed or absent")
    require(all(b["epochMs"] > a["epochMs"] for a, b in zip(samples, samples[1:])),
            "unordered process samples")

    execution_samples = []
    if jfr_path is not None:
        for event in jfr_events(jfr_path):
            if event["type"] not in ("jdk.ExecutionSample", "jdk.NativeMethodSample"):
                continue
            value = event["values"]
            epoch = datetime.datetime.fromisoformat(value["startTime"]).timestamp() * 1000
            frames = (value.get("stackTrace") or {}).get("frames", [])
            classes = [f["method"]["type"]["name"].replace("/", ".") for f in frames]
            execution_samples.append((epoch,
                any(c.startswith("com.live2d.graphics3d.editableMesh.triangulation.") for c in classes),
                any(c == "dev.turboism.adapter.cubism.mesh.TriangulationEdgeIndex" for c in classes)))
    windows = []
    for start, end in zip(markers[::2], markers[1::2]):
        duration = (end["monotonicNanos"] - start["monotonicNanos"]) / 1e9
        phase = start["phase"].removesuffix("-start")
        if phase != "operation":
            require(duration >= 30, "production idle window shorter than thirty seconds")
        rows = [r for r in samples if start["epochMillis"] <= r["epochMs"] <= end["epochMillis"]]
        require(len(rows) >= 2, "window lacks two process observations")
        wall = (rows[-1]["epochMs"] - rows[0]["epochMs"]) / 1000
        item = {"phase": phase, "operation": int(start["operation"]),
                "startEpochMillis": start["epochMillis"], "endEpochMillis": end["epochMillis"],
                "markerDurationSeconds": duration, "observedDurationSeconds": wall,
                "startUnsampledMillis": rows[0]["epochMs"] - start["epochMillis"],
                "endUnsampledMillis": end["epochMillis"] - rows[-1]["epochMs"],
                "maxSampleGapMillis": max(b["epochMs"] - a["epochMs"] for a, b in zip(rows, rows[1:])),
                "epochMinusMonotonicDurationMillis": end["epochMillis"] - start["epochMillis"] - duration * 1000,
                "boundaryHeapUsedBytes": [start["heapUsedBytes"], end["heapUsedBytes"]], "roles": {}}
        window_samples = [s for s in execution_samples if start["epochMillis"] <= s[0] <= end["epochMillis"]]
        item["targetExecution"] = {
            "status": "NOT_PROVIDED" if jfr_path is None else
                "OBSERVED" if any(s[1] for s in window_samples) else "NOT_OBSERVED",
            "triangulationSamples": sum(s[1] for s in window_samples),
            "productionIndexSamples": sum(s[2] for s in window_samples)}
        for role in ("host-java", "task-auxiliary"):
            snapshots = [{(r["pid"], r["startTicks"]): r for r in row["records"]
                          if r["role"] == role} for row in rows]
            require(all(len(s) == 1 for s in snapshots) if role == "host-java" else True,
                    "missing Java sample in window")
            user = system = 0
            transitions = 0
            peak_cpu = 0
            for i, (a, b) in enumerate(zip(snapshots, snapshots[1:])):
                transitions += len(a.keys() ^ b.keys())
                du = sum(b[k]["userTicks"] - a[k]["userTicks"] for k in a.keys() & b.keys())
                ds = sum(b[k]["systemTicks"] - a[k]["systemTicks"] for k in a.keys() & b.keys())
                require(du >= 0 and ds >= 0, "CPU counter decreased")
                user += du
                system += ds
                elapsed = (rows[i + 1]["epochMs"] - rows[i]["epochMs"]) / 1000
                peak_cpu = max(peak_cpu, (du + ds) / hz / elapsed * 100)
            rss = [sum(r["rssBytes"] for r in snapshot.values()) for snapshot in snapshots]
            missing_pss = sum("pssBytes" not in r for s in snapshots for r in s.values())
            pss = None if missing_pss else [sum(r["pssBytes"] for r in s.values()) for s in snapshots]
            item["roles"][role] = {
                "userSeconds": user / hz, "systemSeconds": system / hz,
                "cpuSeconds": (user + system) / hz,
                "averageCpuPercentOneCore": (user + system) / hz / wall * 100,
                "peakIntervalCpuPercentOneCore": peak_cpu,
                "identityTransitions": transitions, "pssMissingRecords": missing_pss,
                "rssPeakBytes": max(rss), "rssMedianBytes": statistics.median(rss),
                "rssFirstBytes": rss[0], "rssLastBytes": rss[-1],
                "pssPeakBytes": max(pss) if pss is not None else None,
                "pssMedianBytes": statistics.median(pss) if pss is not None else None}
        windows.append(item)
    retained = [w for w in windows if w["phase"] == "retained"]
    return {"schemaVersion": 1, "performanceAcceptance": "NOT_DECIDED",
            "inputs": {str(p): file_sha256(p)
                       for p in (marker_path, sample_path, jfr_path) if p is not None},
            "javaIdentity": list(next(iter(java_ids))), "windows": windows,
            "lastMinusFirstRetainedJavaMedianRssBytes":
                retained[-1]["roles"]["host-java"]["rssMedianBytes"] - retained[0]["roles"]["host-java"]["rssMedianBytes"],
            "limitations": ["CPU deltas omit unsampled boundary intervals and unobserved process lifetimes.",
                            "RSS/PSS and heap are sampled observations, not continuous peaks or proof of a leak.",
                            "Epoch time aligns process observations; clock discrepancy is reported per window.",
                            "Absence of JFR samples does not prove absence of execution; repeated-target retention is unproven when not observed.",
                            "Independent canonical/edge/class checks remain mandatory; the capture agent covers only its first four calls."]}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("markers", type=Path)
    parser.add_argument("samples", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("--jfr-json", type=Path, help="jfr print --json execution/native samples from this run")
    args = parser.parse_args()
    report = analyze(args.markers, args.samples, args.jfr_json)
    with args.output.open("x") as stream:
        json.dump(report, stream, indent=2)
        stream.write("\n")
