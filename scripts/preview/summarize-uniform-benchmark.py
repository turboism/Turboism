#!/usr/bin/env python3
"""Summarize a completed resource-accounted uniform suite; never modify evidence."""
from __future__ import annotations
import argparse
import ast
import json
import math
from pathlib import Path

MIB = 1024 ** 2


def summarize(text: str) -> dict:
    data = {}
    for line in text.splitlines():
        if "=" not in line:
            continue
        key, value = line.split("=", 1)
        if key in data:
            raise ValueError(f"duplicate evidence key: {key}")
        data[key] = value
    if data.get("status") != "PASS" or data.get("factor") != "uniformSuite":
        raise ValueError("requires completed PASS uniformSuite evidence")
    if data.get("uniformCache.twoZoomPixelParity") != "true":
        raise ValueError("native framebuffer parity is absent")
    expected = ["native", "locations", "locations-and-values", "locations-and-values", "locations", "native"]
    groups = {}
    for leg, variant in enumerate(expected):
        p = f"leg.{leg}."
        if data.get(p + "variant") != variant:
            raise ValueError(f"missing or out-of-order leg {leg}")
        values = ast.literal_eval(data[p + "rawNanos"])
        if not isinstance(values, list) or not values or any(type(n) is not int or n <= 0 for n in values):
            raise ValueError("invalid raw latency observations")
        if len(values) != int(data[p + "samples"]):
            raise ValueError("sample count disagrees with raw observations")
        if data.get("calibration") == "false" and len(values) != 200:
            raise ValueError("full acceptance requires 200 observations per leg")
        for name in ("glErrors", "failures", "shadowMismatches"):
            if int(data[p + "uniformCache." + name]) != 0:
                raise ValueError(f"native/cache failure in leg {leg}")
        count = int(data[p + "completedDisplayFrames"])
        if count < len(values):
            raise ValueError("fewer completed displays than measured interactions")
        groups.setdefault(variant, []).append((leg, p, values, count))
    report = {"schemaVersion": 1, "calibration": data.get("calibration") == "true",
              "latencyDefinition": data.get("latencyDefinition"),
              "fpsDefinition": "completed native display callbacks / measured wall time; not screen presentation",
              "memoryDefinition": "250ms sampled process working set and JVM heap; not GPU VRAM",
              "groups": []}
    for variant, legs in groups.items():
        flat = sorted(n for _, _, values, _ in legs for n in values)
        frames = sum(count for _, _, _, count in legs)
        def ints(suffix):
            return [int(data[p + suffix]) for _, p, _, _ in legs]
        def total(suffix):
            ns = ints(suffix)
            return sum(ns) if all(n >= 0 for n in ns) else None
        def peak_mib(suffix):
            ns = ints("resources." + suffix)
            return max(ns) / MIB if all(n >= 0 for n in ns) else None
        def sampled_mean_mib(suffix, counts):
            ns = ints("resources." + suffix)
            weights = ints("resources." + counts)
            return sum(n*w for n, w in zip(ns, weights)) / sum(weights) / MIB if all(n >= 0 for n in ns) and sum(weights) > 0 else None
        elapsed = total("elapsedNanos")
        cpu = total("resources.processCpuNanos")
        wall = total("resources.wallNanos")
        processors = ints("resources.availableProcessors")
        if len(set(processors)) != 1 or processors[0] <= 0:
            raise ValueError("CPU capacity changed between legs")
        allocated = total("resources.edtAllocatedBytes")
        row = {"variant": variant, "legs": [leg for leg, _, _, _ in legs],
               "samples": len(flat), "completedFrames": frames,
               "meanMillis": sum(flat) / len(flat) / 1e6,
               "p95Millis": flat[math.ceil(len(flat)*.95)-1] / 1e6,
               "p99Millis": flat[math.ceil(len(flat)*.99)-1] / 1e6,
               "elapsedSeconds": elapsed / 1e9, "renderFps": frames * 1e9 / elapsed,
               "cpuPercentOneCore": None if cpu is None else cpu * 100 / wall,
               "cpuPercentMachine": None if cpu is None else cpu * 100 / wall / processors[0],
               "cpuMillisPerFrame": None if cpu is None else cpu / frames / 1e6,
               "processors": processors[0],
               "workingSetMeanMiB": sampled_mean_mib("workingSetMeanBytes", "nativeSamples"),
               "workingSetPeakMiB": peak_mib("workingSetSamplePeakBytes"),
               "heapUsedMeanMiB": sampled_mean_mib("heapUsedMeanBytes", "samples"),
               "heapUsedPeakMiB": peak_mib("heapUsedSamplePeakBytes"),
               "heapCommittedPeakMiB": peak_mib("heapCommittedSamplePeakBytes"),
               "directBufferPeakMiB": peak_mib("directBufferSamplePeakBytes"),
               "edtAllocatedMiBPerFrame": None if allocated is None else allocated / frames / MIB,
               "gcCollections": total("resources.gcCollections"),
               "gcCollectionMillis": total("resources.gcCollectionMillis"),
               "samplerMillis": total("resources.samplerNanos") / 1e6,
               "sampleFailures": total("resources.sampleFailures"),
               "drawCalls": total("uniformCache.draws"),
               "nativeLocationQueries": total("uniformCache.nativeQueries"),
               "nativeUniformWrites": total("uniformCache.nativeUniformWrites")}
        report["groups"].append(row)
    baseline = report["groups"][0]
    for row in report["groups"]:
        row["latencySpeedupVsNative"] = baseline["meanMillis"] / row["meanMillis"]
        row["throughputSpeedupVsNative"] = row["renderFps"] / baseline["renderFps"]
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("report", type=Path)
    args = parser.parse_args()
    result = summarize(args.report.read_text(encoding="utf-8"))
    result["source"] = str(args.report)
    print(json.dumps(result, ensure_ascii=False, indent=2, allow_nan=False))


if __name__ == "__main__":
    main()
