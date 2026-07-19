"""CLI and test-seam verification helpers for SDK API tier scenarios."""
from __future__ import annotations

import subprocess
from pathlib import Path

from sdk_api_baseline import BaselineError, canonical_records_for_tiers
from sdk_api_baseline_cli import load_baseline, tier_report
from sdk_api_tiers import verify_tier_compatible_for_test
from sdk_api_tiers_test_constants import LEDGER, PREFIX, TOOL
from sdk_api_tiers_test_policy import test_policy_digest
from sdk_api_tiers_test_support import fail


def command(*arguments: str, success: bool = True) -> str:
    result = subprocess.run(["python3", str(TOOL), *arguments], text=True, capture_output=True)
    output = result.stdout + result.stderr
    if success and result.returncode != 0:
        fail(f"command failed unexpectedly ({result.returncode}):\n{output}")
    if not success and result.returncode == 0:
        fail(f"command unexpectedly passed: {' '.join(arguments)}")
    return output


def expect_failure(output: str, description: str, *needles: str) -> None:
    if "Traceback (most recent call last)" in output:
        fail(f"{description} leaked a traceback instead of BaselineError output:\n{output}")
    lowered = output.lower()
    if "sdk api baseline:" not in lowered:
        fail(f"{description} failed without a baseline diagnostic:\n{output}")
    if needles and not any(needle.lower() in lowered for needle in needles):
        fail(f"{description} failed without an expected diagnostic {needles}:\n{output}")


def verify_production(current: Path, reference: Path, baseline: Path, policy: Path, *, success: bool, report: Path | None = None) -> str:
    arguments = compatible_arguments(current, reference, baseline, policy)
    if report is not None:
        arguments.extend(["--tier-report", str(report)])
    return command(*arguments, success=success)


def compatible_arguments(current: Path, reference: Path, baseline: Path, policy: Path) -> list[str]:
    return [
        "verify-compatible", "--input", str(current), "--reference-input", str(reference),
        "--baseline", str(baseline), "--package-prefix", PREFIX, "--tier-policy", str(policy),
        "--initial-preview-ledger", str(LEDGER),
    ]


def verify(current: Path, historical: Path, baseline_path: Path, policy_path: Path, *, success: bool, report: Path | None = None) -> str:
    """Test-only policy trust injection; production CLI has no equivalent."""
    try:
        tiers, current_records = verify_with_test_seam(current, historical, baseline_path, policy_path)
        if report is not None:
            tier_report(report, current_records, tiers)
    except BaselineError as exc:
        output = f"SDK API baseline: {exc}\n"
        if success:
            fail(f"synthetic policy verification failed unexpectedly:\n{output}")
        return output
    if not success:
        raise AssertionError("synthetic policy verification unexpectedly passed")
    return "SDK API baseline tier-compatible verification passed\n"


def verify_with_test_seam(current: Path, historical: Path, baseline_path: Path, policy_path: Path):
    baseline = load_baseline(baseline_path)
    reference_records, _reference_facts, _sha, _size = canonical_records_for_tiers(historical, PREFIX)
    current_records, current_facts, _sha, _size = canonical_records_for_tiers(current, PREFIX)
    tiers = verify_tier_compatible_for_test(
        policy_path=policy_path, initial_ledger_path=LEDGER, baseline=baseline,
        reference_records=reference_records, current_records=current_records,
        current_markers=current_facts.direct_markers, invalid_marker_usages=current_facts.invalid_usages,
        policy_trust=test_policy_digest(policy_path),
    )
    return tiers, current_records
