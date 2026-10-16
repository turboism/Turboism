#!/usr/bin/env python3
"""Summarize an explicitly supplied schema-1 wall stack capture, without rerunning import.

Counts are thread observations, not CPU time. Each accepted observation has exactly
one importer caller and one editor boundary; these two tables overlap each other.
Schema 1 aggregates equal stacks and cannot correlate observations with stage times.
"""
import argparse
from collections import Counter
from pathlib import Path


def summarize(source: str, thread_name: str) -> str:
    states: Counter[str] = Counter()
    callers: Counter[str] = Counter()
    boundaries: Counter[str] = Counter()
    leaves: Counter[str] = Counter()
    for block in source.split("\nSAMPLES ")[1:]:
        lines = block.splitlines()
        count = int(lines[0])
        name, _, state = lines[1].rpartition(" [")
        if name != thread_name:
            continue
        states[state.rstrip("]")] += count
        if state != "RUNNABLE]":
            continue
        frames = [line.strip() for line in lines[2:] if line.startswith("  ")]
        callers[next((f for f in frames if "dev.turboism.validation.modelimport3d.host." in f), "<outside importer>")] += count
        boundaries[next((f for f in frames if "dev.turboism.adapter.cubism.editor." in f and ".transaction." not in f), "<outside editor boundary>")] += count
        leaves[frames[0] if frames else "<empty stack>"] += count
    out = [f"thread={thread_name}", f"states={dict(states)}", "Wall-clock observations, NOT CPU percentages or stage durations.", "Schema 1 has no per-observation timestamps; stage attribution is unavailable.", "Tables below overlap; rows within each table are mutually exclusive."]
    for title, counts in [("first importer caller", callers), ("first editor boundary", boundaries), ("leaf", leaves)]:
        assert sum(counts.values()) == states["RUNNABLE"]
        out.append(f"\n{title} (total={sum(counts.values())})")
        out.extend(f"{count:6} {frame}" for frame, count in counts.most_common())
    return "\n".join(out)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("capture", type=Path)
    parser.add_argument("--thread", default="AWT-EventQueue-0")
    args = parser.parse_args()
    print(summarize(args.capture.read_text(), args.thread))
