"""SDK API baseline CLI command implementations."""
from __future__ import annotations

import argparse
from pathlib import Path

from sdk_api_baseline import BaselineError, GENERATOR_VERSION, SCHEMA_VERSION, canonical_dump, canonical_records_for_tiers, sha256_bytes
from sdk_api_baseline_cli_io import COMMIT_RE, FORMAT, load_baseline, write_output, write_tier_report
from sdk_api_tiers import canonical_json, verify_tier_compatible


def capture(args: argparse.Namespace) -> None:
    if not COMMIT_RE.fullmatch(args.commit):
        raise BaselineError("commit must be exactly 40 lowercase hexadecimal characters")
    dump, artifact_sha, artifact_size = canonical_dump(args.input, args.package_prefix)
    write_output(args.output, canonical_json(_baseline_value(args, dump, artifact_sha, artifact_size)))


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
    _verify_tier_options(args, exact)
    if not exact and args.tier_policy:
        _verify_tiers(args, baseline)
        return
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


def _verify_tier_options(args, exact):
    if exact and (args.tier_policy or args.tier_report or args.initial_preview_ledger):
        raise BaselineError("tier policy/report is only supported by verify-compatible")
    if not exact and (args.tier_report or args.initial_preview_ledger) and not args.tier_policy:
        raise BaselineError("tier report/ledger requires --tier-policy")


def _verify_tiers(args, baseline):
    if args.initial_preview_ledger is None:
        raise BaselineError("tier policy requires --initial-preview-ledger")
    reference_records, _facts, _sha, _size = canonical_records_for_tiers(args.reference_input, args.package_prefix)
    current_records, facts, _sha, _size = canonical_records_for_tiers(args.input, args.package_prefix)
    tiers = verify_tier_compatible(
        policy_path=args.tier_policy,
        initial_ledger_path=args.initial_preview_ledger,
        baseline=baseline,
        reference_records=reference_records,
        current_records=current_records,
        current_markers=facts.direct_markers,
        invalid_marker_usages=facts.invalid_usages,
    )
    if args.tier_report:
        write_tier_report(args.tier_report, current_records, tiers)
    print(f"SDK API baseline tier-compatible verification passed: baseline={len(reference_records)} current={len(current_records)}")


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
