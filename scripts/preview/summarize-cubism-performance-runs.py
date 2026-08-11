#!/usr/bin/env python3
import json
import pathlib
import statistics
import sys

if len(sys.argv) != 2:
    raise SystemExit("usage: summarize-cubism-performance-runs.py RUNS_DIR")
root = pathlib.Path(sys.argv[1])
result = {}
for variant in ("a0", "a1", "a2"):
    runs = []
    for path in sorted(root.glob(f"{variant}-r[123]/scenario-metrics-summary.json")):
        data = json.loads(path.read_text(encoding="utf-8"))
        runs.append({"run": path.parent.name, **data})
    cpu = [run["averageProcessCpuPercent"] for run in runs]
    rss = [run["rssBytes"]["median"] for run in runs]
    result[variant] = {
        "runs": runs,
        "medianProcessCpuPercent": statistics.median(cpu),
        "medianScenarioRssBytes": statistics.median(rss),
        "cpuRangePercent": max(cpu) - min(cpu),
    }
base = result["a0"]["medianProcessCpuPercent"]
for variant in ("a1", "a2"):
    value = result[variant]["medianProcessCpuPercent"]
    result[variant]["cpuDifferenceFromA0Percent"] = 100.0 * (value - base) / base
print(json.dumps(result, ensure_ascii=False, indent=2))
