"""SDK API tier compatibility verification."""
from __future__ import annotations

from pathlib import Path
from typing import Any, Iterable

from sdk_api_baseline_common import BaselineError
from sdk_api_tiers_classify import record_tiers
from sdk_api_tiers_common import Digest
from sdk_api_tiers_policy import load_tier_policy
from sdk_api_tiers_verify_additions import verify_inventory, verify_new_records, verify_stable_additions
from sdk_api_tiers_verify_admission import (
    record_indexes,
    reject_invalid_markers,
    verify_admission_shapes,
    verify_current_marker_authority,
    verify_history_roots,
    verify_marker_state,
)
from sdk_api_tiers_trust import PRODUCTION_TIER_POLICY_LINE_COUNT, PRODUCTION_TIER_POLICY_SHA256
from sdk_api_tiers_verify_history import verify_historical_records


def verify_tier_compatible(*, policy_path: Path, initial_ledger_path: Path, baseline: dict[str, Any], reference_records: list[str], current_records: list[str], current_markers: dict[str, bool], invalid_marker_usages: Iterable[str]) -> dict[str, str]:
    trust = Digest(PRODUCTION_TIER_POLICY_LINE_COUNT, PRODUCTION_TIER_POLICY_SHA256)
    return _verify_with_trust(
        policy_path, initial_ledger_path, baseline, reference_records, current_records,
        current_markers, invalid_marker_usages, trust,
    )


def verify_tier_compatible_for_test(*, policy_path: Path, initial_ledger_path: Path, baseline: dict[str, Any], reference_records: list[str], current_records: list[str], current_markers: dict[str, bool], invalid_marker_usages: Iterable[str], policy_trust: Digest, initial_ledger_trust: Digest | None = None) -> dict[str, str]:
    _verify_test_trust(policy_trust, initial_ledger_trust)
    return _verify_with_trust(
        policy_path, initial_ledger_path, baseline, reference_records, current_records,
        current_markers, invalid_marker_usages, policy_trust, initial_ledger_trust,
    )


def _verify_test_trust(policy_trust, initial_ledger_trust) -> None:
    if not isinstance(policy_trust, Digest):
        raise BaselineError("test tier policy trust must be a Digest")
    if initial_ledger_trust is not None and not isinstance(initial_ledger_trust, Digest):
        raise BaselineError("test initial preview ledger trust must be a Digest")


def _verify_with_trust(policy_path, initial_ledger_path, baseline, reference_records, current_records, current_markers, invalid_marker_usages, policy_trust, initial_ledger_trust=None):
    reject_invalid_markers(invalid_marker_usages)
    policy, initial_roots, admissions, promotions = load_tier_policy(
        policy_path, baseline, initial_ledger_path,
        policy_trust=policy_trust, initial_ledger_trust=initial_ledger_trust,
    )
    reference_by_id, current_by_id = record_indexes(reference_records, current_records)
    verify_history_roots(initial_roots, reference_by_id, admissions, current_by_id)
    verify_current_marker_authority(current_markers, initial_roots, admissions)
    verify_admission_shapes(current_records, admissions, promotions)
    active_roots = verify_marker_state(current_markers, initial_roots, admissions, promotions, current_by_id)
    tiers = record_tiers(current_records, current_markers, active_roots)
    verify_historical_records(reference_by_id, current_by_id, reference_records, initial_roots, promotions, tiers)
    verify_new_records(current_by_id, reference_by_id, tiers, admissions, active_roots, promotions)
    verify_stable_additions(policy, current_by_id, reference_by_id, tiers)
    verify_inventory(policy, set(current_by_id), tiers)
    return tiers
