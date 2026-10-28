#!/usr/bin/env python3
"""Run official interpolation in isolated JVMs, target version before matrix."""
import argparse
import csv
import hashlib
import json
from pathlib import Path
import re
import statistics
import subprocess

ROOT = Path(__file__).resolve().parents[2]
HERE = Path(__file__).resolve().parent
OWNER = "com.live2d.cubism.doc.model.interpolator.extendedInterpolation.CExtendedInterpolationExtension"
METHODS = ("updateInterpolatedForms_common", "updateInterpolatedForms_exe", "updateInterpolatedForms_Normal", "setupFormStructure_Normal", "calcCombination$recCalculateCombination")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ref", required=True, type=Path)
    parser.add_argument("--out", required=True, type=Path)
    parser.add_argument("--versions", nargs="+", default=["5.3.04"])
    parser.add_argument("--vertices", type=int, default=256)
    parser.add_argument("--repeats", type=int, default=100)
    args = parser.parse_args()
    args.ref = args.ref.resolve()
    args.out = args.out.resolve()
    args.out.mkdir(parents=True, exist_ok=False)
    classes = args.out / "classes"
    classes.mkdir()
    cmd = ["javac", "-J-XX:-UsePerfData", "-cp", str(args.ref / "5.3.04" / "*"), "-d", str(classes), str(HERE / "NativePointScalingProbe.java")]
    result = subprocess.run(cmd, capture_output=True, text=True)
    (args.out / "compile.log").write_text(result.stdout + result.stderr)
    result.check_returncode()
    results = []
    for version in args.versions:
        jar = args.ref / version / "Live2D_Cubism.jar"
        cp = str(classes) + ":" + str(args.ref / version / "*")
        command = ["java", "-XX:-UsePerfData", "-Xms256m", "-Xmx1g", "-Djava.awt.headless=true", "-cp", cp, "dev.turboism.diagnostic.extended.NativePointScalingProbe", str(args.vertices), str(args.repeats)]
        result = subprocess.run(command, capture_output=True, text=True, cwd=args.out.resolve())
        (args.out / (version + ".log")).write_text(result.stdout + result.stderr)
        record = {"version": version, "jarSha256": hashlib.file_digest(jar.open("rb"), "sha256").hexdigest(), "exitCode": result.returncode, "command": command}
        if result.returncode == 0:
            lines = result.stdout.splitlines()
            start = next(i for i, s in enumerate(lines) if s.startswith("type,vertices,"))
            rows = list(csv.DictReader(lines[start:]))
            groups = {}
            for row in rows:
                key = (row["type"], int(row["insertedPerInterval"]))
                groups.setdefault(key, []).append(row)
            record["measurements"] = [dict(type=key[0], insertedPerInterval=key[1], forms=int(rows[0]["forms"]), medianNs=statistics.median(float(r["nsPerCall"]) for r in rows), medianBytes=statistics.median(float(r["bytesPerCall"]) for r in rows)) for key, rows in groups.items()]
        bytecode = subprocess.run(["javap", "-J-XX:-UsePerfData", "-classpath", str(jar), "-c", "-p", OWNER], capture_output=True, text=True, check=True).stdout
        # Exclude metadata and CP indices; retain resolved instructions, signatures,
        # local slots and branch offsets. This is structural evidence, not an admission pin.
        sections = re.split(r"(?m)^  (?=\S)", bytecode)
        record["methodBytecodeSha256"] = {}
        for method in METHODS:
            body = next(s for s in sections if re.search(r"\b" + re.escape(method) + r"\(", s.splitlines()[0]))
            normalized = "\n".join(" ".join(line.split()) for line in re.sub(r"#\d+", "#CP", body).strip().splitlines())
            record["methodBytecodeSha256"][method] = hashlib.sha256(normalized.encode()).hexdigest()
        results.append(record)
        (args.out / "results.json").write_text(json.dumps(results, indent=2) + "\n")
        print(version, "PASS" if result.returncode == 0 else "FAIL", flush=True)
    manifest = {"scope": "isolated official ArtMesh kernel; no Editor drag or end-to-end latency claim", "sourceSha256": hashlib.sha256((HERE / "NativePointScalingProbe.java").read_bytes()).hexdigest(), "runnerSha256": hashlib.sha256(Path(__file__).read_bytes()).hexdigest(), "java": subprocess.run(["java", "-XX:-UsePerfData", "-version"], capture_output=True, text=True).stderr, "versions": args.versions}
    (args.out / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
    if any(r["exitCode"] for r in results):
        raise SystemExit(1)


if __name__ == "__main__":
    main()
