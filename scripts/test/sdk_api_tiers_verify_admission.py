"""Admission and marker-authority checks for SDK API tier verification."""
from __future__ import annotations

from sdk_api_baseline_common import BaselineError
from sdk_api_baseline_identity import canonical_identity
from sdk_api_tiers_classify import root_owned_records
from sdk_api_tiers_common import canonical_record_digest


def reject_invalid_markers(usages) -> None:
    invalid = sorted(set(usages))
    if invalid:
        raise BaselineError("illegal PreviewApi marker placement: " + ", ".join(invalid[:10]))


def record_indexes(reference_records, current_records):
    return (
        {canonical_identity(record): record for record in reference_records},
        {canonical_identity(record): record for record in current_records},
    )


def verify_history_roots(initial_roots, reference_by_id, admissions, current_by_id) -> None:
    if not initial_roots <= set(reference_by_id):
        raise BaselineError("initial preview ledger contains a root absent from reviewed baseline")
    admission_ids = {entry.identity for entry in admissions}
    if any(identity in reference_by_id for identity in admission_ids):
        raise BaselineError("newPreview history cannot name a historical stable identity")
    missing = sorted(admission_ids - set(current_by_id))
    if missing:
        raise BaselineError("newPreview history root is absent from current API: " + ", ".join(missing))


def verify_current_marker_authority(markers, initial_roots, admissions, reference_by_id) -> None:
    admission_ids = {entry.identity for entry in admissions}
    orphaned = {
        identity
        for identity, marked in markers.items()
        if marked
        and identity in reference_by_id
        and identity not in initial_roots
        and identity not in admission_ids
    }
    if orphaned:
        raise BaselineError(
            "historical stable API cannot become Preview without retained authority: "
            + ", ".join(sorted(orphaned))
        )


def verify_admission_shapes(records, admissions, promotions) -> None:
    for admission in admissions:
        owned = root_owned_records(records, admission.identity)
        if not owned:
            if admission.identity not in promotions:
                raise BaselineError(f"newPreview root is absent from current API: {admission.identity}")
            continue
        actual = canonical_record_digest(owned)
        if actual != admission.admitted_owned_records:
            label = "newPreview promotion admission" if admission.identity in promotions else "newPreview admission"
            raise digest_mismatch(label, admission.admitted_owned_records, actual, owned)


def verify_marker_state(
    markers, initial_roots, admissions, promotions, current_by_id, reference_by_id
):
    admission_ids = {entry.identity for entry in admissions}
    if not promotions <= initial_roots | admission_ids:
        raise BaselineError("tier policy promotion target is not an admitted preview root")
    new_direct_roots = {
        identity
        for identity, marked in markers.items()
        if marked and identity not in reference_by_id
    }
    active = (
        (initial_roots & set(current_by_id))
        | (admission_ids & set(current_by_id))
        | new_direct_roots
    ) - promotions
    actual = {identity for identity, marked in markers.items() if marked}
    if actual != active:
        missing, extra = sorted(active - actual), sorted(actual - active)
        raise BaselineError(
            f"current direct PreviewApi marker roots mismatch: missing={missing[:10]} extra={extra[:10]}"
        )
    verify_promoted_markers(promotions, current_by_id, markers)
    return active


def verify_promoted_markers(promotions, current_by_id, markers) -> None:
    for identity in promotions:
        if identity not in current_by_id:
            raise BaselineError(f"promotion target is absent from current API: {identity}")
        if markers.get(identity, False):
            raise BaselineError(f"promoted root must not retain a current PreviewApi marker: {identity}")


def digest_mismatch(label, expected, actual, records) -> BaselineError:
    sample = "; ".join(canonical_identity(record) for record in sorted(records)[:5]) or "<empty>"
    return BaselineError(f"{label} digest mismatch: expected {expected.line_count}/{expected.sha256}, actual {actual.line_count}/{actual.sha256}; sample={sample}")
