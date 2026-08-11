"""Historical record checks for SDK API tier verification."""
from __future__ import annotations

from sdk_api_baseline_common import BaselineError
from sdk_api_tiers_classify import historical_identity_is_promoted, record_tiers


def verify_historical_records(reference_by_id, current_by_id, reference_records, initial_roots, promotions, tiers) -> None:
    reference_tiers = record_tiers(reference_records, {}, initial_roots)
    context = reference_records, initial_roots, promotions, tiers
    for identity, historical in sorted(reference_by_id.items()):
        verify_historical_record(identity, historical, current_by_id.get(identity), reference_tiers, context)


def verify_historical_record(identity, historical, current, reference_tiers, context) -> None:
    records, roots, promotions, tiers = context
    if reference_tiers.get(identity) == "preview":
        verify_historical_preview(identity, historical, current, records, roots, promotions, tiers)
    else:
        verify_historical_stable(identity, historical, current, tiers)


def verify_historical_preview(identity, historical, current, records, roots, promotions, tiers) -> None:
    promoted = historical_identity_is_promoted(identity, promotions, records, roots)
    if current is None:
        if promoted:
            raise BaselineError(f"promotion target is absent from current API: {identity}")
        return
    if tiers.get(identity) != "preview":
        if not promoted:
            raise BaselineError(f"historical preview API cannot be implicitly promoted to stable: {identity}")
        if current != historical:
            raise BaselineError(f"preview promotion requires unchanged normalized shape: {identity}")


def verify_historical_stable(identity, historical, current, tiers) -> None:
    if tiers.get(identity) == "preview":
        raise BaselineError(f"historical stable API cannot be downgraded to preview: {identity}")
    if current is None:
        raise BaselineError(f"stable API record was removed: {identity}")
    if current != historical:
        raise BaselineError(f"stable API record changed: {identity}")
