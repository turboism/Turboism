#!/usr/bin/env python3
"""CLI and reviewed-baseline envelope for the SDK API model."""
from __future__ import annotations

import argparse
import json
import re
from pathlib import Path
from typing import Any

from sdk_api_baseline import (
    BaselineError,
    GENERATOR_VERSION,
    HEADER,
    SCHEMA_VERSION,
    canonical_dump,
    sha256_bytes,
)

FORMAT = "turboism.sdk.api-baseline"
COMMIT_RE = re.compile(r"[0-9a-f]{40}")
SHA_RE = re.compile(r"[0-9a-f]{64}")


def die(message: str) -> None:
    raise SystemExit(f"SDK API baseline: {message}")


def canonical_json(value: Any) -> bytes:
    text = json.dumps(value, ensure_ascii=False, indent=2, sort_keys=True) + "\n"
    return text.encode("utf-8")


def write_output(path: Path, data: bytes) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(data)


def capture(args: argparse.Namespace) -> None:
    if not COMMIT_RE.fullmatch(args.commit):
        raise BaselineError("commit must be exactly 40 lowercase hexadecimal characters")
    dump, artifact_sha, artifact_size = canonical_dump(args.input, args.package_prefix)
    value = {
        "artifact": {"sha256": artifact_sha, "size": artifact_size},
        "canonicalDump": {
            "lineCount": len(dump.decode("utf-8").splitlines()),
            "sha256": sha256_bytes(dump),
        },
        "commit": args.commit,
        "format": FORMAT,
        "generatorVersion": GENERATOR_VERSION,
        "role": args.role,
        "schemaVersion": SCHEMA_VERSION,
    }
    write_output(args.output, canonical_json(value))


def load_baseline(path: Path) -> dict[str, Any]:
    if not path.is_file():
        raise BaselineError(f"baseline is missing: {path}")
    try:
        raw = path.read_bytes()
        if raw.startswith(b"\xef\xbb\xbf"):
            raise BaselineError("baseline must be UTF-8 without BOM")
        value = json.loads(raw.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise BaselineError(f"baseline is malformed: {exc}") from exc
    expected_keys = {
        "artifact", "canonicalDump", "commit", "format",
        "generatorVersion", "role", "schemaVersion",
    }
    if not isinstance(value, dict) or set(value) != expected_keys:
        raise BaselineError("baseline has an invalid top-level shape")
    if value["format"] != FORMAT or value["schemaVersion"] != SCHEMA_VERSION:
        raise BaselineError("baseline format/schema is unsupported")
    if value["generatorVersion"] != GENERATOR_VERSION:
        raise BaselineError("baseline generator version is unsupported")
    if value["role"] not in ("pre-phase", "exact"):
        raise BaselineError("baseline role is invalid")
    if not isinstance(value["commit"], str) or not COMMIT_RE.fullmatch(value["commit"]):
        raise BaselineError("baseline commit is invalid")
    artifact = value["artifact"]
    canonical = value["canonicalDump"]
    if not isinstance(artifact, dict) or set(artifact) != {"sha256", "size"}:
        raise BaselineError("baseline artifact metadata is invalid")
    if not isinstance(artifact["sha256"], str) or not SHA_RE.fullmatch(artifact["sha256"]):
        raise BaselineError("baseline artifact SHA-256 is invalid")
    if not isinstance(artifact["size"], int) or artifact["size"] <= 0:
        raise BaselineError("baseline artifact size is invalid")
    if not isinstance(canonical, dict) or set(canonical) != {"lineCount", "sha256"}:
        raise BaselineError("baseline canonical dump metadata is invalid")
    if not isinstance(canonical["sha256"], str) or not SHA_RE.fullmatch(canonical["sha256"]):
        raise BaselineError("baseline canonical dump SHA-256 is invalid")
    if not isinstance(canonical["lineCount"], int) or canonical["lineCount"] < 3:
        raise BaselineError("baseline canonical dump line count is invalid")
    return value


def verify(args: argparse.Namespace, exact: bool) -> None:
    baseline = load_baseline(args.baseline)
    if args.expected_commit and baseline["commit"] != args.expected_commit:
        raise BaselineError(
            f"baseline is bound to {baseline['commit']}, expected {args.expected_commit}"
        )
    reference_dump, reference_artifact_sha, reference_artifact_size = canonical_dump(
        args.reference_input,
        args.package_prefix
    )
    expected_artifact = baseline["artifact"]
    expected_dump = baseline["canonicalDump"]
    if (
        reference_artifact_sha != expected_artifact["sha256"]
        or reference_artifact_size != expected_artifact["size"]
    ):
        raise BaselineError(
            "reviewed reference artifact binding mismatch: "
            f"expected {expected_artifact['sha256']}/{expected_artifact['size']}, "
            f"found {reference_artifact_sha}/{reference_artifact_size}"
        )
    if (
        sha256_bytes(reference_dump) != expected_dump["sha256"]
        or len(reference_dump.decode("utf-8").splitlines()) != expected_dump["lineCount"]
    ):
        raise BaselineError("reviewed reference canonical dump binding mismatch")
    current_dump, artifact_sha, artifact_size = canonical_dump(args.input, args.package_prefix)
    current_lines = current_dump.decode("utf-8").splitlines()[2:]
    baseline_lines = reference_dump.decode("utf-8").splitlines()[2:]
    current_set = set(current_lines)
    baseline_set = set(baseline_lines)
    removed = sorted(baseline_set - current_set)
    added = sorted(current_set - baseline_set)
    if removed or (exact and added):
        mode = "exact" if exact else "compatible"
        details = [f"{mode} API verification failed"]
        if removed:
            details.append(f"removed or changed baseline records ({len(removed)}):")
            details.extend("- " + item for item in removed[:20])
        if exact and added:
            details.append(f"added records not present in exact baseline ({len(added)}):")
            details.extend("+ " + item for item in added[:20])
        raise BaselineError("\n".join(details))
    if exact:
        expected = baseline["artifact"]
        if artifact_sha != expected["sha256"] or artifact_size != expected["size"]:
            raise BaselineError(
                "SDK artifact binding mismatch: "
                f"expected {expected['sha256']}/{expected['size']}, "
                f"found {artifact_sha}/{artifact_size}"
            )
    mode = "exact" if exact else "compatible"
    print(
        f"SDK API baseline {mode} verification passed: "
        f"baseline={len(baseline_lines)} current={len(current_lines)} additions={len(added)}"
    )


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser()
    sub = parser.add_subparsers(dest="command", required=True)
    dump_parser = sub.add_parser("dump")
    dump_parser.add_argument("--input", required=True, type=Path)
    dump_parser.add_argument("--output", required=True, type=Path)
    dump_parser.add_argument("--package-prefix")
    capture_parser = sub.add_parser("capture")
    capture_parser.add_argument("--input", required=True, type=Path)
    capture_parser.add_argument("--output", required=True, type=Path)
    capture_parser.add_argument("--package-prefix")
    capture_parser.add_argument("--role", choices=("pre-phase", "exact"), required=True)
    capture_parser.add_argument("--commit", required=True)
    for command in ("verify-compatible", "verify-exact"):
        verify_parser = sub.add_parser(command)
        verify_parser.add_argument("--input", required=True, type=Path)
        verify_parser.add_argument("--baseline", required=True, type=Path)
        verify_parser.add_argument("--reference-input", required=True, type=Path)
        verify_parser.add_argument("--package-prefix")
        verify_parser.add_argument("--expected-commit")
    return parser


def main() -> None:
    args = build_parser().parse_args()
    try:
        if args.command == "dump":
            dump, _sha, _size = canonical_dump(args.input, args.package_prefix)
            write_output(args.output, dump)
        elif args.command == "capture":
            capture(args)
        else:
            verify(args, args.command == "verify-exact")
    except BaselineError as exc:
        die(str(exc))


if __name__ == "__main__":
    main()
