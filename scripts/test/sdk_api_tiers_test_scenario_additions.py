"""Stable-addition and newPreview scenarios for SDK API tier tests."""
from __future__ import annotations

import copy

from sdk_api_tiers_test_commands import expect_failure, verify, verify_production
from sdk_api_tiers_test_context import TierTestContext
from sdk_api_tiers_test_fixtures import compile_fixture, delete_jar_entry
from sdk_api_tiers_test_policy import owned_digest, replacement_policy_for, target_type, write_policy


def run(context: TierTestContext) -> None:
    stable_addition_digest(context)
    preview_api_mutations(context)
    new_preview_promotion(context)


def stable_addition_digest(context: TierTestContext) -> None:
    added = compile_fixture(context.root, "added-stable", True, "add-current-stable")
    expect_failure(
        verify(added, context.historical, context.baseline_path, context.policy_path, success=False),
        "unreviewed current-only stable addition", "stable additions", "digest",
    )
    policy = replacement_policy_for(added, context.historical, context.policy)
    path = context.root / "added-stable-policy.json"
    write_policy(path, policy)
    verify(added, context.historical, context.baseline_path, path, success=True)
    expect_failure(
        verify(context.current, context.historical, context.baseline_path, path, success=False),
        "reviewed current-only stable deletion", "stable additions", "digest",
    )
    changed = compile_fixture(context.root, "changed-stable", True, "add-current-stable-change")
    expect_failure(
        verify(changed, context.historical, context.baseline_path, path, success=False),
        "reviewed current-only stable shape change", "stable additions", "digest",
    )


def preview_api_mutations(context: TierTestContext) -> None:
    without = context.root / "without-preview-api.jar"
    delete_jar_entry(context.current, without, "dev/turboism/sdk/PreviewApi.class")
    expect_failure(
        verify(without, context.historical, context.baseline_path, context.policy_path, success=False),
        "PreviewApi class deletion", "stable additions", "digest",
    )
    for variant, description in preview_api_variants():
        mutated = compile_fixture(context.root, variant, True, variant)
        expect_failure(
            verify(mutated, context.historical, context.baseline_path, context.policy_path, success=False),
            description, "stable additions", "digest",
        )


def preview_api_variants() -> tuple[tuple[str, str], ...]:
    return (
        ("preview-api-retention-change", "PreviewApi Retention change"),
        ("preview-api-target-change", "PreviewApi Target change"),
        ("preview-api-shape-change", "PreviewApi type shape change"),
    )


def new_preview_promotion(context: TierTestContext) -> None:
    new_preview, policy, path = admitted_new_preview(context)
    unmarked = compile_fixture(context.root, "new-preview-unmarked", True, "new-preview-unmarked")
    reject_removed_history(context, unmarked, policy)
    promoted_policy, promoted_path = promote_new_preview(context, unmarked, policy)
    assert_promoted_shape(context, unmarked, promoted_path)
    context.state.update({
        "new_preview": new_preview,
        "new_policy": policy,
        "new_policy_path": path,
        "unmarked_new_preview": unmarked,
        "promoted_new_policy": promoted_policy,
        "promoted_new_path": promoted_path,
    })


def admitted_new_preview(context: TierTestContext):
    fixture = compile_fixture(context.root, "new-preview", True, "new-preview")
    root = target_type("dev/turboism/sdk/newpreview/NewThing")
    policy = copy.deepcopy(context.policy)
    policy["newPreview"] = [{"root": root, "admittedOwnedRecords": owned_digest(fixture, root)}]
    policy = replacement_policy_for(fixture, context.historical, policy)
    path = context.root / "new-preview-policy.json"
    write_policy(path, policy)
    verify(fixture, context.historical, context.baseline_path, path, success=True)
    return fixture, policy, path


def reject_removed_history(context: TierTestContext, unmarked, policy) -> None:
    removed = copy.deepcopy(policy)
    removed["newPreview"] = []
    removed = replacement_policy_for(unmarked, context.historical, removed)
    path = context.root / "new-preview-history-removed.json"
    write_policy(path, removed)
    expect_failure(
        verify_production(unmarked, context.historical, context.baseline_path, path, success=False),
        "newPreview entry and marker removal implicit promotion", "tier policy trust-anchor mismatch",
    )


def promote_new_preview(context: TierTestContext, unmarked, policy):
    promoted = copy.deepcopy(policy)
    promoted["promotions"] = [target_type("dev/turboism/sdk/newpreview/NewThing")]
    promoted = replacement_policy_for(unmarked, context.historical, promoted)
    path = context.root / "new-preview-promoted-policy.json"
    write_policy(path, promoted)
    verify(unmarked, context.historical, context.baseline_path, path, success=True)
    return promoted, path


def assert_promoted_shape(context: TierTestContext, unmarked, path) -> None:
    changed = compile_fixture(context.root, "new-preview-promoted-change", True, "new-preview-promotion-change")
    expect_failure(
        verify(changed, context.historical, context.baseline_path, path, success=False),
        "newPreview promotion admitted-shape change", "promotion", "admission", "digest",
    )
