#!/usr/bin/env python3
"""Run the isolated Cat cache differential without modifying an Editor/model.

Requires local Cubism 5.3.02 libraries, ASM 9, and JDK 17+. Output must be a
new directory, so earlier failed/successful evidence is never overwritten.
"""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import zipfile
import runpy


def sha(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def run(command, label, output):
    (output / (label + "-command.json")).write_text(json.dumps(command, indent=2) + "\n")
    with (output / (label + ".log")).open("xb") as log:
        result = subprocess.run(command, stdout=log, stderr=subprocess.STDOUT)
    (output / (label + "-exit.json")).write_text(json.dumps({"exitCode": result.returncode}) + "\n")
    return result.returncode


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--lib", required=True, type=Path)
    parser.add_argument("--asm", required=True, type=Path)
    parser.add_argument("--model", required=True, type=Path)
    parser.add_argument("--out", required=True, type=Path)
    parser.add_argument("--optimized-setup", action="store_true")
    parser.add_argument("--production-runtime", action="store_true")
    parser.add_argument("--precompute-ellipse-weights", action="store_true",
                        help="Exercise the opt-in pinned ellipse coefficient candidate")
    parser.add_argument("--optimized-save-controller", type=Path,
                        help="Validate a separately admitted optimized Cat native save before measuring it")
    args = parser.parse_args()
    if args.precompute_ellipse_weights and not args.production_runtime:
        parser.error("--precompute-ellipse-weights requires --production-runtime")
    output = args.out.resolve()
    output.mkdir(parents=True, exist_ok=False)
    model = args.model.resolve()
    host = args.lib.resolve() / "Live2D_Cubism.jar"
    expected_model = "0bfdbbb6bd0521a07ed566241b19a2a5c69b1d7f36a9efed1d5b744a9bb7f8b4"
    save_review = None
    if args.optimized_save_controller:
        controller = args.optimized_save_controller.resolve(strict=True)
        auditor = Path(__file__).resolve().parents[1] / "analyze-cat-optimized-save.py"
        runpy.run_path(str(auditor))["analyze"](controller)
        save_review = json.loads((controller / "independent-optimized-save-review.json").read_text())
        expected_model = save_review["artifact"]["artifactSha256"]
    if sha(model) != expected_model or sha(host) != "988ef6a8b5fede84bd43c6dc3a9a045d9a6a974986c3f49fb6f567ccf8c84f21":
        raise SystemExit("Refused: model or official artifact does not match reviewed Cat fixture")
    sources = sorted(Path(__file__).resolve().parent.glob("*.java"))
    sources += sorted((Path(__file__).resolve().parents[3] / "runtime/src/main/java/dev/turboism/adapter/cubism/interpolation").glob("*.java"))
    libraries = sorted(args.lib.resolve().rglob("*.jar")) + [args.asm.resolve()]
    import os
    classpath = os.pathsep.join(str(path) for path in [output, *libraries])
    status = run(["javac", "-cp", classpath, "-d", str(output), *map(str, sources)], "compile", output)
    if status:
        raise SystemExit(status)
    agent = output / "cat-interpolation-diagnostic-agent.jar"
    with zipfile.ZipFile(agent, "w") as archive:
        premain = "CatRuntimeInterpolationAgent" if args.production_runtime else "CatInterpolationDiagnosticAgent"
        archive.writestr("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\nPremain-Class: dev.turboism.diagnostic." + premain + "\n\n")
        for path in sorted((output / "dev/turboism").rglob("*.class")):
            archive.write(path, str(path.relative_to(output)))
    status = run(["java", "-javaagent:" + str(agent), "-Xmx6g", "-Djava.awt.headless=true",
                  "-Dturboism.meshInterpolation.coefficients=" + str(args.precompute_ellipse_weights).lower(),
                  "-Dcat.diagnostic.optimizedSetup=" + str(args.optimized_setup).lower(), "-cp", classpath,
                  "-Dcat.diagnostic.optimizedModel=" + str(save_review is not None).lower(),
                  "-Dcat.diagnostic.productionRuntime=" + str(args.production_runtime).lower(),
                  "dev.turboism.diagnostic.CatInterpolationCacheProof", str(model)], "cache", output)
    unchanged = sha(model) == expected_model
    manifest = {
        "scope": "isolated official 2D interpolation cache; excludes Editor input/history and deserialize optimization",
        "editorDragFixed": False,
        "exitCode": status,
        "modelUnchanged": unchanged,
        "optimizedSaveReview": save_review,
        "inputs": [{"path": str(path), "sha256": sha(path)} for path in [model, host, args.asm.resolve(), *sources]],
        "outputs": [{"path": str(path.relative_to(output)), "sha256": sha(path)} for path in sorted(output.glob("*")) if path.is_file()],
    }
    (output / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
    if not unchanged:
        raise SystemExit("Unexpected model modification")
    raise SystemExit(status)


if __name__ == "__main__":
    main()
