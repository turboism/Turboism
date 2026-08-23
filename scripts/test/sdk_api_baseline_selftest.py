#!/usr/bin/env python3
"""Python body for the SDK API baseline shell selftest."""
from __future__ import annotations

import argparse
import json
import re
import subprocess
from pathlib import Path

from sdk_api_baseline_selftest_fixtures import compile_fixture


COMMIT = "0123456789abcdef0123456789abcdef01234567"
VARIANTS = ("baseline", "additive", "reordered-fields", "changed-constant", "changed-default", "changed-descriptor", "forbidden")


def main() -> None:
    args = parse_args()
    for variant in VARIANTS:
        compile_fixture(args.tmp, variant)
    verify_deterministic_dump(args)
    baseline = capture_baseline(args)
    verify_compatibility(args, baseline)
    verify_canonical_reference_binding(args, baseline)
    verify_failures(args, baseline)
    verify_boolean_metadata(args, baseline)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--tool", type=Path, required=True)
    parser.add_argument("--tmp", type=Path, required=True)
    return parser.parse_args()


def run(tool: Path, *arguments: str) -> subprocess.CompletedProcess[str]:
    return subprocess.run(["python3", str(tool), *arguments], text=True, check=True)


def run_failure(tool: Path, *arguments: str) -> str:
    result = subprocess.run(["python3", str(tool), *arguments], text=True, capture_output=True)
    if result.returncode == 0:
        fail(f"command unexpectedly passed: {' '.join(arguments)}")
    return result.stdout + result.stderr


def verify_deterministic_dump(args: argparse.Namespace) -> None:
    baseline = args.tmp / "baseline" / "sdk.jar"
    first, second = args.tmp / "dump-a.txt", args.tmp / "dump-b.txt"
    run(args.tool, "dump", "--input", str(baseline), "--output", str(first))
    run(args.tool, "dump", "--input", str(baseline), "--output", str(second))
    if first.read_bytes() != second.read_bytes():
        fail("canonical dump is not deterministic")
    assert_dump_features(first.read_text(encoding="utf-8"))


def assert_dump_features(dump: str) -> None:
    checks = (
        (r"^sdk-api-schema\t1$", "schema header missing"),
        (r"annotation-default:value:", "annotation defaults missing"),
        (r"^package\tname=sample/api\tannotations=list:[1-9]", "package annotations missing"),
        (r"constant=string:6:", "String ConstantValue encoding missing"),
        (r"permitted=", "sealed permitted subclasses missing"),
    )
    for pattern, message in checks:
        if not re.search(pattern, dump, re.MULTILINE):
            fail(message)


def capture_baseline(args: argparse.Namespace) -> Path:
    baseline, output = args.tmp / "baseline" / "sdk.jar", args.tmp / "baseline.json"
    run(args.tool, "capture", "--input", str(baseline), "--role", "pre-phase", "--commit", COMMIT, "--output", str(output))
    return output


def verify_compatibility(args: argparse.Namespace, baseline: Path) -> None:
    reference = args.tmp / "baseline" / "sdk.jar"
    for variant in ("baseline", "additive", "reordered-fields"):
        run(args.tool, "verify-compatible", "--input", str(args.tmp / variant / "sdk.jar"), "--reference-input", str(reference), "--baseline", str(baseline))


def verify_canonical_reference_binding(args: argparse.Namespace, baseline: Path) -> None:
    reference = args.tmp / "baseline" / "sdk.jar"
    artifact_mutation = args.tmp / "artifact-repacked.jar"
    artifact_mutation.write_bytes(reference.read_bytes() + b"reference-container-variation")
    output = run_failure(
        args.tool,
        "verify-compatible",
        "--input",
        str(reference),
        "--reference-input",
        str(artifact_mutation),
        "--baseline",
        str(baseline),
    )
    if "artifact binding mismatch" not in output:
        fail("artifact reference binding did not reject a repacked reference")
    run(
        args.tool,
        "verify-compatible",
        "--input",
        str(reference),
        "--reference-input",
        str(artifact_mutation),
        "--reference-binding",
        "canonical",
        "--baseline",
        str(baseline),
    )


def verify_failures(args: argparse.Namespace, baseline: Path) -> None:
    reference = args.tmp / "baseline" / "sdk.jar"
    assert_changed_constant(args, baseline, reference)
    for variant, command in failure_cases():
        output = run_failure(args.tool, command, "--input", str(args.tmp / variant / "sdk.jar"), "--reference-input", str(reference), "--baseline", str(baseline))
        if not output:
            continue
    missing = run_failure(args.tool, "verify-compatible", "--input", str(reference), "--reference-input", str(reference), "--baseline", str(args.tmp / "missing.json"))
    malformed = args.tmp / "malformed.json"
    malformed.write_text('{"format":"wrong"}\n', encoding="utf-8")
    run_failure(args.tool, "verify-compatible", "--input", str(reference), "--reference-input", str(reference), "--baseline", str(malformed))


def assert_changed_constant(args: argparse.Namespace, baseline: Path, reference: Path) -> None:
    output = run_failure(args.tool, "verify-compatible", "--input", str(args.tmp / "changed-constant" / "sdk.jar"), "--reference-input", str(reference), "--baseline", str(baseline))
    if not re.search(r"removed|changed|incompatible", output):
        fail("changed constant failed without reviewable diagnostic")


def failure_cases() -> tuple[tuple[str, str], ...]:
    return (
        ("changed-default", "verify-compatible"), ("changed-descriptor", "verify-compatible"),
        ("additive", "verify-exact"), ("forbidden", "verify-compatible"),
    )


def verify_boolean_metadata(args: argparse.Namespace, baseline: Path) -> None:
    write_boolean_mutations(args.tmp, baseline)
    reference = args.tmp / "baseline" / "sdk.jar"
    for mutation in sorted(args.tmp.glob("baseline-*-bool.json")):
        output = run_failure(args.tool, "verify-compatible", "--input", str(reference), "--reference-input", str(reference), "--baseline", str(mutation))
        if "sdk api baseline:" not in output.lower():
            fail(f"boolean metadata did not produce baseline diagnostic: {mutation.name}")


def write_boolean_mutations(tmp: Path, baseline: Path) -> None:
    source = json.loads(baseline.read_text(encoding="utf-8"))
    for name, path in boolean_paths().items():
        value = json.loads(json.dumps(source))
        target = value
        for key in path[:-1]:
            target = target[key]
        target[path[-1]] = True
        (tmp / f"{name}.json").write_text(json.dumps(value) + "\n", encoding="utf-8")


def boolean_paths() -> dict[str, tuple[str, ...]]:
    return {
        "baseline-schema-bool": ("schemaVersion",), "baseline-generator-bool": ("generatorVersion",),
        "baseline-line-count-bool": ("canonicalDump", "lineCount"), "baseline-artifact-size-bool": ("artifact", "size"),
    }


def fail(message: str) -> None:
    raise SystemExit(f"SDK API baseline selftest: {message}")


if __name__ == "__main__":
    main()
