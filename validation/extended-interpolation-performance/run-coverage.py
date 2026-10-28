#!/usr/bin/env python3
"""Compile current production bridge sources and measure exact 5.3.04 coverage."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ref", required=True, type=Path)
    parser.add_argument("--out", required=True, type=Path)
    args = parser.parse_args()
    args.out = args.out.resolve()
    args.ref = args.ref.resolve()
    args.out.mkdir(parents=True, exist_ok=False)
    classes = args.out / "classes"
    classes.mkdir()
    main = ROOT / "runtime/src/main/java/dev/turboism"
    sources = [HERE / "BridgeCoverageProbe.java", HERE / "WarpCoverageProbe.java"]
    sources += [main / "adapter/cubism/interpolation" / (name + ".java") for name in ["NativeMeshInterpolationBridge", "InterpolationCurveCache", "LocalInterpolationCache", "LocalInterpolationState", "LocalFormState", "EllipseWeights", "HostArtifactDigestCache"]]
    sources += [main / "mapping/verification" / (name + ".java") for name in ["HostArtifactDigest", "ReviewedHostArtifacts", "ReviewedReleaseTable"]]
    sources += [main / "core/runtime/work/FatalErrors.java"]
    cp = str(classes) + ":" + str(args.ref / "5.3.04" / "*")
    command = ["javac", "-J-XX:-UsePerfData", "-cp", cp, "-d", str(classes), *map(str, sources)]
    result = subprocess.run(command, capture_output=True, text=True)
    (args.out / "compile.log").write_text(result.stdout + result.stderr)
    result.check_returncode()
    runs = []
    for probe in ["BridgeCoverageProbe", "WarpCoverageProbe"]:
        command = ["java", "-XX:-UsePerfData", "-Xms256m", "-Xmx1g", "-Djava.awt.headless=true", "-cp", cp, "dev.turboism.diagnostic.extended." + probe]
        result = subprocess.run(command, capture_output=True, text=True, cwd=args.out)
        (args.out / (probe + ".log")).write_text(result.stdout + result.stderr)
        runs.append(dict(probe=probe, command=command, exitCode=result.returncode))
        print(probe, result.returncode, flush=True)
    jar = args.ref / "5.3.04/Live2D_Cubism.jar"
    manifest = {"scope": "direct current bridge kernel; installer/bootstrap not exercised", "jarSha256": hashlib.sha256(jar.read_bytes()).hexdigest(), "sources": {str(p.relative_to(ROOT)): hashlib.sha256(p.read_bytes()).hexdigest() for p in sources}, "runs": runs}
    (args.out / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
    if any(r["exitCode"] for r in runs):
        raise SystemExit(1)


if __name__ == "__main__":
    main()
