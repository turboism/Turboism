#!/usr/bin/env python3
import csv
import json
import os
import statistics
import sys

if len(sys.argv) not in (2, 4):
    raise SystemExit("usage: summarize-cubism-process-metrics.py METRICS.csv [START_EPOCH_MS END_EPOCH_MS]")

with open(sys.argv[1], newline="", encoding="utf-8") as source:
    rows = [{key: int(value) for key, value in row.items()} for row in csv.DictReader(source)]
if len(sys.argv) == 4:
    start_epoch_ms, end_epoch_ms = map(int, sys.argv[2:])
    rows = [row for row in rows if start_epoch_ms <= row["epoch_ms"] <= end_epoch_ms]
if len(rows) < 2:
    raise SystemExit("need at least two metric samples")

clock_ticks = os.sysconf(os.sysconf_names["SC_CLK_TCK"])
elapsed_seconds = (rows[-1]["epoch_ms"] - rows[0]["epoch_ms"]) / 1000.0
cpu_seconds = (rows[-1]["cpu_ticks"] - rows[0]["cpu_ticks"]) / clock_ticks
rss = [row["rss_bytes"] for row in rows]
threads = [row["threads"] for row in rows]

summary = {
    "samples": len(rows),
    "startEpochMs": rows[0]["epoch_ms"],
    "endEpochMs": rows[-1]["epoch_ms"],
    "elapsedSeconds": elapsed_seconds,
    "cpuSeconds": cpu_seconds,
    "averageProcessCpuPercent": 100.0 * cpu_seconds / elapsed_seconds if elapsed_seconds > 0 else 0.0,
    "rssBytes": {
        "min": min(rss),
        "median": statistics.median(rss),
        "max": max(rss),
        "last": rss[-1],
    },
    "threads": {
        "min": min(threads),
        "median": statistics.median(threads),
        "max": max(threads),
        "last": threads[-1],
    },
    "readBytesDelta": rows[-1]["read_bytes"] - rows[0]["read_bytes"],
    "writeBytesDelta": rows[-1]["write_bytes"] - rows[0]["write_bytes"],
}
print(json.dumps(summary, ensure_ascii=False, indent=2))
