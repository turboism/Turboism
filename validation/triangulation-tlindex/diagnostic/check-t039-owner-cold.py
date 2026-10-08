#!/usr/bin/env python3
"""Bounded, owned-only regression for T039 and the actual definition gateway."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sidecar", required=True, type=Path)
    parser.add_argument("--sidecar-sha256", required=True)
    parser.add_argument("--production", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--expect-missing-api", action="store_true")
    parser.add_argument("--expect-missing-abort-api", action="store_true")
    args = parser.parse_args()
    production_sha = "ddb1ba0c66d953be81d4fe99047075d3c4905e4e225887f7a9c380ffe016d6f2"
    if sha(args.production) != production_sha or sha(args.sidecar) != args.sidecar_sha256:
        parser.error("immutable production/sidecar pin mismatch")
    out = args.output.absolute()
    out.mkdir(parents=True, exist_ok=False)
    inputs = out / "input"
    inputs.mkdir()
    for name, source in {"production.jar": args.production, "t039-shadow-agent.jar": args.sidecar,
                         "T039OwnerColdRemovalSelfCheck.java": Path(__file__).with_name("T039OwnerColdRemovalSelfCheck.java"),
                         "DefinitionFingerprint.java": Path(__file__).with_name("DefinitionFingerprint.java"),
                         "check-t039-owner-cold.py": Path(__file__)}.items():
        shutil.copyfile(source, inputs / name)
    asm = Path.home() / ".gradle/caches/modules-2/files-2.1/org.ow2.asm/asm/9.7.1/f0ed132a49244b042cd0e15702ab9f2ce3cc8436/asm-9.7.1.jar"
    if sha(asm) != "8cadd43ac5eb6d09de05faecca38b917a040bb9139c7edeb4cc81c740b713281":
        raise ValueError("ASM pin mismatch")
    shutil.copyfile(asm, inputs / "asm.jar")
    pins = {str(p): sha(p) for p in inputs.iterdir()}
    (out / "input-pins.json").write_text(json.dumps(pins, indent=2) + "\n")
    commands = []

    def run(label, command, expected=0):
        result = subprocess.run(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, timeout=60)
        (out / (label + ".log")).write_text(result.stdout)
        commands.append({"label": label, "command": command, "exitCode": result.returncode})
        (out / "commands.json").write_text(json.dumps(commands, indent=2) + "\n")
        if result.returncode != expected:
            raise AssertionError(f"{label}: exit {result.returncode}, expected {expected}; see {out / (label + '.log')}")
        return result.stdout

    cp = ":".join(str(inputs / name) for name in ("production.jar", "t039-shadow-agent.jar", "asm.jar"))
    classes = out / "classes"
    classes.mkdir()
    run("compile", ["javac", "--release", "17", "-proc:none", "-Xlint:all", "-Werror", "-cp", cp, "-d", str(classes),
                    str(inputs / "T039OwnerColdRemovalSelfCheck.java"), str(inputs / "DefinitionFingerprint.java")])
    manifest = out / "manifest.mf"
    entry = "dev.turboism.validation.tlindex.diagnostic.T039OwnerColdRemovalSelfCheck"
    manifest.write_text("Manifest-Version: 1.0\nPremain-Class: " + entry + "\nCan-Retransform-Classes: true\n\n")
    harness = out / "owned-test-agent.jar"
    run("package", ["jar", "--create", "--file", str(harness), "--manifest", str(manifest), "-C", str(classes), "."])
    fixture = out / "owned-target.jar"
    metadata = out / "owned-target.properties"
    run("fixture", ["java", "-Xverify:all", "-cp", cp,
                    "dev.turboism.validation.atlasimage.t039.T039FixtureArtifact", str(fixture), str(metadata)])
    results = []
    if args.expect_missing_api and args.expect_missing_abort_api:
        parser.error("select one expected-red regression")
    modes = (["cold", "abort-before-target"] if args.expect_missing_abort_api else
             ["default", "cold"] if args.expect_missing_api else
             ["default", "cold", "raw", "missing-token", "false", "throws", "late", "wrong-hash", "abort-before-target"])
    for mode in modes:
        command = ["java", "-Xverify:all", "-XX:+DisableAttachMechanism", "-XX:-CreateCoredumpOnCrash",
                   "-Djava.awt.headless=true", "-Dt039.ownerColdTest.mode=" + mode,
                   "-javaagent:" + str(harness) + "=" + str(metadata), "-cp", str(harness) + ":" + cp, entry]
        expected = 1 if (args.expect_missing_api and mode == "cold") or (args.expect_missing_abort_api and mode == "abort-before-target") else 0
        log = run(mode, command, expected)
        if expected:
            missing = "abortOwnerColdRemoval" if args.expect_missing_abort_api else "premainForOwnerColdRemoval"
            if "NoSuchMethodException" not in log or missing not in log:
                raise AssertionError("red regression failed for an unrelated reason")
        elif "T039_OWNER_COLD_GATEWAY_PASS mode=" + mode not in log:
            raise AssertionError("missing acceptance marker: " + mode)
        results.append({"mode": mode, "status": "EXPECTED_RED" if expected else "PASS"})
    mismatches = [p for p, digest in pins.items() if sha(Path(p)) != digest]
    if mismatches:
        raise AssertionError("input changed: " + str(mismatches))
    report = {"status": "MISSING_ABORT_API_RED_REPRODUCED" if args.expect_missing_abort_api else
                        "OLD_CALLBACK_FAILURE_AND_NEW_API_RED_REPRODUCED" if args.expect_missing_api else "OWNED_COLD_LIFECYCLE_PASS",
              "cases": results, "inputPins": pins, "postWriterInputMismatches": 0,
              "officialInitialized": False, "geometryExecuted": False, "EditorStarted": False,
              "actualTurboismPremain": False, "native5303Acceptance": "NOT_VERIFIED", "productionAcceptance": "NOT_PASSED"}
    (out / "review.json").write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps({"status": report["status"], "report": str(out / "review.json"), "cases": results}))


if __name__ == "__main__":
    main()
