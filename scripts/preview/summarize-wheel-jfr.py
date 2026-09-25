#!/usr/bin/env python3
"""Read a task-local JFR and summarize sample attribution; never modify the recording."""
from __future__ import annotations
import argparse
import collections
import json
from pathlib import Path
import subprocess
import shutil
import sys


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("recording", type=Path)
    args = parser.parse_args()
    if not args.recording.is_file():
        parser.error("recording does not exist")
    events = "jdk.ExecutionSample,jdk.NativeMethodSample,jdk.ThreadPark,jdk.JavaMonitorEnter,jdk.ThreadSleep,jdk.GarbageCollection,jdk.CPULoad"
    jfr = shutil.which("jfr")
    if jfr is None:
        java = shutil.which("java")
        if java is None:
            parser.error("Java/JFR tools are not installed")
        jfr = str(Path(java).resolve().with_name("jfr"))
    output = subprocess.run([jfr, "print", "--json", "--stack-depth", "64", "--events", events,
                             str(args.recording)], check=True, capture_output=True, text=True, timeout=90)
    payload = json.loads(output.stdout)
    counts: collections.Counter = collections.Counter()
    leaves: collections.Counter = collections.Counter()
    inclusive: collections.Counter = collections.Counter()
    stacks: collections.Counter = collections.Counter()
    waits: collections.Counter = collections.Counter()
    cpu = []
    for event in payload["recording"]["events"]:
        kind = event["type"]
        values = event["values"]
        thread = values.get("sampledThread") or values.get("eventThread") or {}
        name = thread.get("javaName", thread.get("osName", "unknown"))
        counts[(kind, name)] += 1
        frames = (values.get("stackTrace") or {}).get("frames", [])
        names = []
        for frame in frames:
            method = frame.get("method") or {}
            owner = (method.get("type") or {}).get("name", "?")
            names.append(owner.replace("/", ".") + "." + method.get("name", "?"))
        if kind in ("jdk.ExecutionSample", "jdk.NativeMethodSample") and name.startswith("AWT-EventQueue"):
            if names:
                leaves[(kind, names[0])] += 1
                stacks[tuple(names[:9])] += 1
                inclusive.update(set(names))
        elif name.startswith("AWT-EventQueue") and names:
            duration = values.get("duration", "unknown")
            waits[(kind, names[0], str(duration))] += 1
        if kind == "jdk.CPULoad":
            cpu.append({k: values.get(k) for k in ("jvmUser", "jvmSystem", "machineTotal")})
    print("eventCounts=" + json.dumps({str(k): v for k, v in counts.most_common()}, ensure_ascii=False))
    print("edtLeafSamples=" + json.dumps({str(k): v for k, v in leaves.most_common(25)}, ensure_ascii=False))
    print("edtInclusiveSamples=" + json.dumps(dict(inclusive.most_common(35)), ensure_ascii=False))
    print("edtTopStacks=" + json.dumps([{"count": n, "stack": list(s)} for s, n in stacks.most_common(12)], ensure_ascii=False))
    print("edtWaitEvents=" + json.dumps({str(k): v for k, v in waits.most_common(15)}, ensure_ascii=False))
    print("cpuLoads=" + json.dumps(cpu))
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (OSError, ValueError, KeyError, subprocess.SubprocessError) as exc:
        print(f"JFR summary failed: {exc}", file=sys.stderr)
        raise SystemExit(1)
