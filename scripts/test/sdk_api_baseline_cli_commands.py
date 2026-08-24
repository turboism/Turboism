"""SDK API baseline CLI command implementations."""
from __future__ import annotations

import argparse
import json
from pathlib import Path

from sdk_api_baseline import BaselineError, GENERATOR_VERSION, SCHEMA_VERSION, canonical_dump, sha256_bytes
from sdk_api_baseline_cli_io import COMMIT_RE, FORMAT, load_baseline, write_output


def capture(args: argparse.Namespace) -> None:
    if not COMMIT_RE.fullmatch(args.commit):
        raise BaselineError("commit must be exactly 40 lowercase hexadecimal characters")
    dump, artifact_sha, artifact_size = canonical_dump(args.input, args.package_prefix)
    write_output(args.output, canonical_json(_baseline_value(args, dump, artifact_sha, artifact_size)))


def canonical_json(value: object) -> bytes:
    return (json.dumps(value, ensure_ascii=False, indent=2, sort_keys=True) + "\n").encode("utf-8")


def _baseline_value(args, dump, artifact_sha, artifact_size):
    return {
        "artifact": {"sha256": artifact_sha, "size": artifact_size},
        "canonicalDump": {"lineCount": len(dump.decode("utf-8").splitlines()), "sha256": sha256_bytes(dump)},
        "commit": args.commit,
        "format": FORMAT,
        "generatorVersion": GENERATOR_VERSION,
        "role": args.role,
        "schemaVersion": SCHEMA_VERSION,
    }


def verify(args: argparse.Namespace, exact: bool) -> None:
    baseline = load_baseline(args.baseline)
    _verify_expected_commit(args, baseline)
    reference_dump = _verify_reference_binding(args, baseline)
    _verify_records(args, baseline, reference_dump, exact)


def _verify_expected_commit(args, baseline):
    if args.expected_commit and baseline["commit"] != args.expected_commit:
        raise BaselineError(f"baseline is bound to {baseline['commit']}, expected {args.expected_commit}")


def _verify_reference_binding(args, baseline):
    dump, artifact_sha, artifact_size = canonical_dump(args.reference_input, args.package_prefix)
    artifact = baseline["artifact"]
    if artifact_sha != artifact["sha256"] or artifact_size != artifact["size"]:
        raise BaselineError(_artifact_binding_mismatch(artifact, artifact_sha, artifact_size))
    canonical = baseline["canonicalDump"]
    if sha256_bytes(dump) != canonical["sha256"] or len(dump.decode("utf-8").splitlines()) != canonical["lineCount"]:
        raise BaselineError("reviewed reference canonical dump binding mismatch")
    return dump


def _artifact_binding_mismatch(expected, actual_sha, actual_size):
    return f"reviewed reference artifact binding mismatch: expected {expected['sha256']}/{expected['size']}, found {actual_sha}/{actual_size}"


def _verify_records(args, baseline, reference_dump, exact):
    current_dump, artifact_sha, artifact_size = canonical_dump(args.input, args.package_prefix)
    baseline_lines, current_lines = _record_lines(reference_dump, current_dump)
    removed, added = sorted(set(baseline_lines) - set(current_lines)), sorted(set(current_lines) - set(baseline_lines))
    if removed or (exact and added):
        raise BaselineError(_record_failure(removed, added, exact))
    if exact:
        _verify_exact_artifact(baseline, artifact_sha, artifact_size)
    _print_success(exact, baseline_lines, current_lines, added)


def _record_lines(reference_dump, current_dump):
    return reference_dump.decode("utf-8").splitlines()[2:], current_dump.decode("utf-8").splitlines()[2:]


def _record_failure(removed, added, exact):
    mode = "exact" if exact else "compatible"
    details = [f"{mode} API verification failed"]
    if removed:
        details += [f"removed or changed baseline records ({len(removed)}):", *["- " + item for item in removed[:20]]]
    if exact and added:
        details += [f"added records not present in exact baseline ({len(added)}):", *["+ " + item for item in added[:20]]]
    return "\n".join(details)


def _verify_exact_artifact(baseline, sha, size):
    expected = baseline["artifact"]
    if sha != expected["sha256"] or size != expected["size"]:
        raise BaselineError(f"SDK artifact binding mismatch: expected {expected['sha256']}/{expected['size']}, found {sha}/{size}")


def _print_success(exact, baseline_lines, current_lines, added):
    mode = "exact" if exact else "compatible"
    print(f"SDK API baseline {mode} verification passed: baseline={len(baseline_lines)} current={len(current_lines)} additions={len(added)}")
