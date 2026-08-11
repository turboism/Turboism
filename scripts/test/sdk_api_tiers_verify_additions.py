"""Current-only additions and inventory checks for SDK API tiers."""
from __future__ import annotations

from sdk_api_baseline_common import BaselineError
from sdk_api_baseline_identity import canonical_identity, split_canonical_record
from sdk_api_tiers_classify import identity_is_owned_by_root, owner_from_identity, package_from_owner
from sdk_api_tiers_common import canonical_record_digest, digest, target_identity
from sdk_api_tiers_verify_admission import digest_mismatch


def verify_new_records(current_by_id, reference_by_id, tiers, admissions, active_roots, promotions) -> None:
    promoted = promotions & {entry.identity for entry in admissions}
    for identity in sorted(set(current_by_id) - set(reference_by_id)):
        if promoted_new_preview_record(identity, promoted, tiers):
            continue
        preview_allowed = new_preview_allowed(identity, admissions, active_roots, current_by_id)
        if tiers.get(identity) == "preview" and not preview_allowed:
            raise BaselineError(f"new preview API requires explicit newPreview admission: {identity}")


def promoted_new_preview_record(identity, promoted, tiers) -> bool:
    if not any(identity_is_owned_by_root(identity, root) for root in promoted):
        return False
    if tiers.get(identity) != "stable":
        raise BaselineError(f"promoted newPreview record is not stable: {identity}")
    return True


def new_preview_allowed(identity, admissions, active_roots, current_by_id) -> bool:
    admission_ids = {entry.identity for entry in admissions}
    if identity.startswith("package:"):
        roots = package_roots(identity, current_by_id)
        return bool(roots) and roots <= admission_ids | active_roots
    owner = owner_from_identity(identity)
    owner_root = f"class:{owner}" if owner else None
    return identity in admission_ids or owner_root in admission_ids or owner_root in active_roots


def package_roots(identity, current_by_id) -> set[str]:
    package = identity[len("package:"):]
    return {
        f"class:{split_canonical_record(record)[1]['name']}"
        for record in current_by_id.values()
        if split_canonical_record(record)[0] == "class"
        and package_from_owner(split_canonical_record(record)[1]["name"]) == package
    }


def verify_stable_additions(policy, current_by_id, reference_by_id, tiers) -> None:
    records = [
        record
        for identity, record in current_by_id.items()
        if identity not in reference_by_id and tiers.get(identity) == "stable"
    ]
    expected = digest(policy["stableAdditions"], "tier policy stableAdditions")
    actual = canonical_record_digest(records)
    if actual != expected:
        raise digest_mismatch("stable additions", expected, actual, records)


def verify_inventory(policy, current_ids, tiers) -> None:
    for violation in inventory_violations(policy, current_ids, tiers):
        raise BaselineError(violation)


def inventory_violations(policy, current_ids, tiers) -> list[str]:
    from sdk_api_tiers_policy import _parse_inventory
    inventory = _parse_inventory(policy["stableNegativeInventory"])
    return [
        *type_inventory_violations(inventory["types"], current_ids, tiers),
        *method_inventory_violations(inventory["methods"], current_ids, tiers),
        *prefix_inventory_violations(inventory["packagePrefixes"], current_ids, tiers),
    ]


def type_inventory_violations(names, current_ids, tiers) -> list[str]:
    return [
        f"stable negative inventory requires stable type {name}"
        for name in names
        if f"class:{name}" not in current_ids or tiers.get(f"class:{name}") != "stable"
    ]


def method_inventory_violations(methods, current_ids, tiers) -> list[str]:
    violations = []
    for method in methods:
        identity = target_identity({"target": "method", **method})
        if identity not in current_ids or tiers.get(identity) != "stable":
            violations.append(f"stable negative inventory requires stable method {identity}")
    return violations


def prefix_inventory_violations(prefixes, current_ids, tiers) -> list[str]:
    violations = []
    for prefix in prefixes:
        matching = [item for item in current_ids if item.startswith("class:") and item[len("class:"):].startswith(prefix)]
        if not matching or any(tiers.get(item) != "stable" for item in matching):
            violations.append(f"stable negative inventory requires stable package prefix {prefix}")
    return violations
