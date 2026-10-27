#!/usr/bin/env python3
"""Compile current production sources and exercise licensed official Cubism kernels.

The target is always verified before the requested version matrix. Installed mode
uses the real installer/transformer; its oracle disables the bridge before every
native call, so the optimized code can never serve as its own oracle.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import zipfile

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ref", required=True, type=Path)
    parser.add_argument("--out", required=True, type=Path)
    parser.add_argument("--versions", nargs="+", default=["5.3.04"])
    parser.add_argument("--asm", type=Path, help="ASM 9 JAR, required for installed mode")
    parser.add_argument("--installed", action="store_true")
    measurement = parser.add_mutually_exclusive_group()
    measurement.add_argument("--benchmark", action="store_true")
    measurement.add_argument("--structure-benchmark", action="store_true")
    measurement.add_argument("--cache-lifecycle", action="store_true")
    measurement.add_argument("--curve-cache", action="store_true")
    measurement.add_argument("--local-refresh", action="store_true")
    args = parser.parse_args()
    if args.installed and not args.asm:
        parser.error("--installed requires --asm")
    if args.structure_benchmark and not args.installed:
        parser.error("--structure-benchmark requires --installed")
    if args.local_refresh and not args.installed:
        parser.error("--local-refresh requires --installed")
    if args.curve_cache and not args.installed:
        parser.error("--curve-cache requires --installed")
    if args.cache_lifecycle and not args.installed:
        parser.error("--cache-lifecycle requires --installed")
    args.out = args.out.resolve()
    args.ref = args.ref.resolve()
    args.out.mkdir(parents=True, exist_ok=False)
    classes = args.out / "classes"
    classes.mkdir()
    main = ROOT / "runtime/src/main/java/dev/turboism"
    sources = [HERE / "RuntimeInterpolationProof.java", HERE / "StructureRuntimeProof.java"]
    if args.cache_lifecycle:
        sources += [HERE / "CacheLifecycleProof.java"]
    if args.curve_cache:
        sources += [HERE / "CurveCacheRuntimeProof.java"]
    if args.local_refresh:
        sources += [HERE / "p3-local-refresh" / (name + ".java") for name in ["LocalRefreshProof", "LocalRefreshAgent", "LocalRuntimeProof"]]
        sources += [HERE / "p3-dependencies/src/dev/turboism/validation/p3/P3DependencyProbe.java"]
    if args.installed:
        sources += [HERE / "InterpolationProofAgent.java", main / "adapter/cubism/optimization/ClassPinTable.java"]
        sources += sorted((main / "adapter/cubism/interpolation").glob("*.java"))
    else:
        sources += [main / "adapter/cubism/interpolation" / (name + ".java") for name in ["NativeMeshInterpolationBridge", "NativeFormLookupBridge", "InterpolationCurveCache", "LocalInterpolationCache", "LocalInterpolationState", "LocalFormState", "EllipseWeights", "HostArtifactDigestCache"]]
    sources += [main / "mapping/verification" / (name + ".java") for name in ["HostArtifactDigest", "ReviewedHostArtifacts", "ReviewedReleaseTable"]]
    sources += [main / "core/runtime/work/FatalErrors.java"]
    if args.asm:
        args.asm = args.asm.resolve(strict=True)
    extra = [str(args.asm)] if args.asm else []
    cp = os.pathsep.join([str(classes), str(args.ref / "5.3.04" / "*"), *extra])
    command = ["javac", "-J-XX:-UsePerfData", "-cp", cp, "-d", str(classes), *map(str, sources)]
    source_hashes = {str(p.relative_to(ROOT)): hashlib.sha256(p.read_bytes()).hexdigest() for p in sources}
    result = subprocess.run(command, capture_output=True, text=True)
    (args.out / "compile.log").write_text(result.stdout + result.stderr)
    result.check_returncode()
    agent = args.out / "proof-agent.jar"
    if args.installed:
        with zipfile.ZipFile(agent, "w") as archive:
            archive.writestr("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\nPremain-Class: dev.turboism.diagnostic.extended.InterpolationProofAgent\n\n")
            for path in classes.rglob("*.class"):
                archive.write(path, str(path.relative_to(classes)))
            pins = ROOT / "runtime/src/main/resources/dev/turboism/adapter/cubism/class-pins/mesh-interpolation.json"
            archive.write(pins, "dev/turboism/adapter/cubism/class-pins/mesh-interpolation.json")
            source_hashes[str(pins.relative_to(ROOT))] = hashlib.sha256(pins.read_bytes()).hexdigest()
    observer = args.out / "setter-observer.jar"
    if args.local_refresh:
        with zipfile.ZipFile(observer, "w") as archive:
            archive.writestr("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\nPremain-Class: dev.turboism.validation.local.LocalRefreshAgent\n\n")
            for path in classes.rglob("*.class"):
                archive.write(path, str(path.relative_to(classes)))
    versions = list(dict.fromkeys(["5.3.04", *args.versions]))
    runs = []
    for version in versions:
        version_out = args.out / version
        version_out.mkdir()
        cp = os.pathsep.join([str(agent if args.installed else classes), str(args.ref / version / "*"), *extra])
        command = ["java", "-XX:-UsePerfData", "-Xms256m", "-Xmx1g", "-Djava.awt.headless=true"]
        if args.local_refresh:
            command += ["-javaagent:" + str(observer) + "=setter-only"]
        if args.installed:
            command += ["-javaagent:" + str(agent)]
        proof_main = "CurveCacheRuntimeProof" if args.curve_cache else "CacheLifecycleProof" if args.cache_lifecycle else "RuntimeInterpolationProof"
        command += ["-cp", cp, "dev.turboism.validation.local.LocalRuntimeProof" if args.local_refresh else "dev.turboism.diagnostic.extended." + proof_main]
        if args.benchmark:
            command += ["benchmark"]
        if args.structure_benchmark:
            command += ["structure-benchmark"]
        with (version_out / "proof.log").open("w") as log:
            result = subprocess.run(command, stdout=log, stderr=subprocess.STDOUT, cwd=version_out, timeout=600)
        jar = args.ref / version / "Live2D_Cubism.jar"
        runs.append(dict(version=version, command=command, exitCode=result.returncode, jarSha256=hashlib.sha256(jar.read_bytes()).hexdigest()))
        manifest = {"scope": "isolated real host kernel; no Editor interaction claim", "installed": args.installed, "benchmark": args.benchmark, "benchmarkWorkload": os.environ.get("EXTENDED_PROOF_WORKLOAD","repeat"), "structureBenchmark": args.structure_benchmark, "cacheLifecycle": args.cache_lifecycle, "curveCache": args.curve_cache, "localRefresh": args.local_refresh, "sources": source_hashes, "runs": runs}
        (args.out / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
        print(version, result.returncode, flush=True)
        if result.returncode:
            raise SystemExit(result.returncode)


if __name__ == "__main__":
    main()
