#!/usr/bin/env python3
import collections
import datetime as dt
import json
import subprocess
import sys

if len(sys.argv) != 4:
    raise SystemExit("usage: analyze-cubism-jfr.py RECORDING.jfr START_EPOCH_MS END_EPOCH_MS")
recording, start_ms, end_ms = sys.argv[1], int(sys.argv[2]), int(sys.argv[3])
start = start_ms / 1000.0
end = end_ms / 1000.0

def epoch(value: str) -> float:
    return dt.datetime.fromisoformat(value).timestamp()

def events(kind: str):
    raw = subprocess.check_output(["jfr", "print", "--json", "--events", kind, recording], text=True)
    return json.loads(raw)["recording"]["events"]

def frame_name(frame):
    method = frame.get("method", {})
    owner = method.get("type", {}).get("name", "?").replace("/", ".")
    return f"{owner}.{method.get('name', '?')}"

samples = collections.Counter()
stacks = collections.Counter()
threads = collections.Counter()
for kind in ("jdk.ExecutionSample", "jdk.NativeMethodSample"):
    for event in events(kind):
        values = event["values"]
        when = epoch(values["startTime"])
        if not start <= when <= end:
            continue
        frames = values.get("stackTrace", {}).get("frames", [])
        if frames:
            samples[frame_name(frames[0])] += 1
            stacks[" <- ".join(frame_name(frame) for frame in frames[:8])] += 1
        thread = values.get("sampledThread", {})
        threads[thread.get("javaName") or thread.get("osName") or "?"] += 1

pauses = []
for event in events("jdk.GarbageCollection"):
    values = event["values"]
    when = epoch(values["startTime"])
    if start <= when <= end:
        duration = values.get("duration", "PT0S")
        # ISO-8601 durations emitted by JFR are seconds-only here, e.g. PT0.0123S.
        pauses.append(float(duration[2:-1]) * 1000.0)

result = {
    "sampleCount": sum(samples.values()),
    "topLeafFrames": samples.most_common(30),
    "topStacks": stacks.most_common(30),
    "topSampledThreads": threads.most_common(20),
    "garbageCollections": len(pauses),
    "gcPauseMillisecondsTotal": sum(pauses),
    "gcPauseMillisecondsMax": max(pauses, default=0.0),
}
print(json.dumps(result, ensure_ascii=False, indent=2))
