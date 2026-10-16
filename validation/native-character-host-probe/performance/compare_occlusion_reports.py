#!/usr/bin/env python3
"""Compare explicit geometry diagnostic logs; never a native visual acceptance gate."""
import argparse
import re
from pathlib import Path


def read(path: Path):
    text = path.read_text()
    identity = re.search(r"^inputSha256=(\w+)$", text, re.M)
    geometry = re.search(r"^partitions=\d+ triangles=\d+ slots=\d+$", text, re.M)
    if not identity or not geometry:
        raise ValueError(f"Missing input hash/geometry in {path}")
    measurements = {}
    for match in re.finditer(r"^yaw=(-?\d+) stored=Measurement\[([^]]+)\] exactPosePartitionOrder=Measurement\[([^]]+)\]$", text, re.M):
        for mode, body in zip(("stored", "exact"), match.groups()[1:]):
            values = dict(field.split("=") for field in body.split(", "))
            measurements[(int(match[1]), mode)] = {k: float(v) for k, v in values.items()}
    expected = {(yaw, mode) for yaw in (0, -30, 30, -90, 90, 180) for mode in ("stored", "exact")}
    if measurements.keys() != expected:
        raise ValueError(f"Incomplete six-pose measurements in {path}")
    return (identity[1], geometry[0]), measurements


def compare(baseline: Path, candidate: Path) -> bool:
    identity, before = read(baseline)
    other, after = read(candidate)
    if identity != other:
        raise ValueError("Input SHA-256 or geometry differs; comparison refused")
    print(f"inputSha256={identity[0]}\n{identity[1]}")
    print("Geometry-only comparison; exact source projection, thresholded alpha. NOT native RGBA acceptance.")
    failures = []
    for key, old in before.items():
        new = after[key]
        if old["visible"] != new["visible"]:
            failures.append(f"{key}: changed reference coverage")
        for field in ("wrong", "missing", "maxGap"):
            if new[field] > old[field]:
                failures.append(f"{key}: {field} regressed {old[field]:g} -> {new[field]:g}")
        print(f"yaw={key[0]:4} {key[1]:6} wrong={int(old['wrong']):5}->{int(new['wrong']):5} "
              f"missing={int(old['missing'])}->{int(new['missing'])} gap={old['maxGap']:.9g}->{new['maxGap']:.9g}")
    for failure in failures:
        print(f"REJECT {failure}")
    print("SAMPLED_GEOMETRY_NONREGRESSION=" + ("FAIL" if failures else "PASS"))
    return not failures


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("baseline", type=Path)
    parser.add_argument("candidate", type=Path)
    args = parser.parse_args()
    raise SystemExit(0 if compare(args.baseline, args.candidate) else 1)
