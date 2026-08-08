"""Method and package promotion scenarios for SDK API tier tests."""
from __future__ import annotations

import copy

from sdk_api_tiers_test_commands import expect_failure, verify
from sdk_api_tiers_test_constants import INITIAL_TYPE_ROOTS
from sdk_api_tiers_test_context import TierTestContext
from sdk_api_tiers_test_fixtures import compile_fixture
from sdk_api_tiers_test_policy import owned_digest, replacement_policy_for, target_method, target_type, write_policy
from sdk_api_tiers_test_support import fail, report_tiers


def run(context: TierTestContext) -> None:
    method_promotion(context)
    type_promotion_package(context)
    historical_package_promotion(context)
    unpromoted_history_is_mutable(context)
    same_package_new_preview(context)
    initial_ledger_promotion(context)


def method_promotion(context: TierTestContext) -> None:
    preview = compile_fixture(context.root, "new-preview-method", True, "new-preview-method")
    root = target_method("dev/turboism/sdk/newpreview/MethodOwner", "previewMethod", "()Ljava/lang/String;")
    policy = admit_method_root(context, preview, root)
    promoted = compile_fixture(context.root, "new-preview-method-unmarked", True, "new-preview-method-unmarked")
    path = promote_method_root(context, promoted, policy, root)
    report = context.root / "new-preview-method-promoted-tiers.json"
    verify(promoted, context.historical, context.baseline_path, path, success=True, report=report)
    assert_method_promotion_tiers(report_tiers(report))


def admit_method_root(context: TierTestContext, preview, root) -> dict[str, object]:
    policy = copy.deepcopy(context.policy)
    policy["newPreview"] = [{"root": root, "admittedOwnedRecords": owned_digest(preview, root)}]
    policy = replacement_policy_for(preview, context.historical, policy)
    path = context.root / "new-preview-method-policy.json"
    write_policy(path, policy)
    verify(preview, context.historical, context.baseline_path, path, success=True)
    return policy


def promote_method_root(context: TierTestContext, promoted, policy, root):
    result = copy.deepcopy(policy)
    result["promotions"] = [root]
    result = replacement_policy_for(promoted, context.historical, result)
    path = context.root / "new-preview-method-promoted-policy.json"
    write_policy(path, result)
    return path


def assert_method_promotion_tiers(tiers: dict[str, str]) -> None:
    expected = {
        "method:dev/turboism/sdk/newpreview/MethodOwner#previewMethod()Ljava/lang/String;": "promoted method root was not stable",
        "class:dev/turboism/sdk/newpreview/MethodOwner": "method promotion incorrectly classified the class owner",
        "method:dev/turboism/sdk/newpreview/MethodOwner#stableMethod()Ljava/lang/String;": "method promotion incorrectly classified sibling method",
        "package:dev/turboism/sdk/newpreview": "method promotion package synthetic record was not stable",
    }
    for identity, message in expected.items():
        if tiers.get(identity) != "stable":
            fail(message)


def type_promotion_package(context: TierTestContext) -> None:
    unmarked = context.state["unmarked_new_preview"]
    path = context.state["promoted_new_path"]
    report = context.root / "new-preview-promoted-tiers.json"
    verify(unmarked, context.historical, context.baseline_path, path, success=True, report=report)
    if report_tiers(report).get("package:dev/turboism/sdk/newpreview") != "stable":
        fail("promoted newPreview package synthetic record was not stable")
    removed = compile_fixture(context.root, "new-preview-promoted-removed", True, "normal")
    expect_failure(
        verify(removed, context.historical, context.baseline_path, path, success=False),
        "newPreview promotion deletion", "promotion", "absent",
    )


def historical_package_promotion(context: TierTestContext) -> None:
    roots = historical_write_roots()
    promoted = compile_fixture(context.root, "historical-package-promoted", True, "historical-package-promotion-unmarked")
    policy = copy.deepcopy(context.policy)
    policy["promotions"] = roots
    policy = replacement_policy_for(promoted, context.historical, policy)
    path = context.root / "historical-package-promotion-policy.json"
    write_policy(path, policy)
    verify(promoted, context.historical, context.baseline_path, path, success=True)
    annotation = compile_fixture(context.root, "historical-package-annotation", True, "historical-package-promotion-annotation")
    output = verify(annotation, context.historical, context.baseline_path, path, success=False)
    expect_failure(output, "historical promoted package annotation mutation", "promotion", "unchanged", "shape")


def historical_write_roots() -> list[dict[str, str]]:
    return [target_type(name) for name in INITIAL_TYPE_ROOTS if name.rpartition("/")[0] == "dev/turboism/sdk/cubism/write"]


def unpromoted_history_is_mutable(context: TierTestContext) -> None:
    for variant, description in mutable_history_variants():
        verify(compile_fixture(context.root, variant, True, variant), context.historical, context.baseline_path, context.policy_path, success=True)


def mutable_history_variants() -> tuple[tuple[str, str], ...]:
    return (
        ("historical-preview-member-removed", "historical preview member deletion"),
        ("historical-preview-member-descriptor-change", "historical preview method descriptor change"),
        ("historical-preview-root-removed", "historical preview root deletion"),
        ("historical-preview-package-removed", "historical preview package deletion"),
    )


def same_package_new_preview(context: TierTestContext) -> None:
    root = target_type("dev/turboism/sdk/cubism/write/NewWriteThing")
    admitted = compile_fixture(context.root, "historical-package-promotion-with-new-preview-admitted", True, "historical-package-promotion-with-new-preview-admitted")
    policy = admitted_same_package_policy(context, admitted, root)
    promoted = compile_fixture(context.root, "historical-package-promotion-with-new-preview", True, "historical-package-promotion-with-new-preview")
    path = promoted_same_package_policy(context, promoted, policy, root)
    report = context.root / "historical-package-promotion-with-new-preview-tiers.json"
    verify(promoted, context.historical, context.baseline_path, path, success=True, report=report)
    if report_tiers(report).get("package:dev/turboism/sdk/cubism/write") != "stable":
        fail("historical package promotion with same-package newPreview was not stable")


def admitted_same_package_policy(context: TierTestContext, admitted, root):
    policy = copy.deepcopy(context.policy)
    policy["newPreview"] = [{"root": root, "admittedOwnedRecords": owned_digest(admitted, root)}]
    policy = replacement_policy_for(admitted, context.historical, policy)
    path = context.root / "historical-package-promotion-with-new-preview-admitted-policy.json"
    write_policy(path, policy)
    verify(admitted, context.historical, context.baseline_path, path, success=True)
    return policy


def promoted_same_package_policy(context: TierTestContext, promoted, policy, root):
    result = copy.deepcopy(policy)
    result["promotions"] = historical_write_roots() + [root]
    result = replacement_policy_for(promoted, context.historical, result)
    path = context.root / "historical-package-promotion-with-new-preview-policy.json"
    write_policy(path, result)
    return path


def initial_ledger_promotion(context: TierTestContext) -> None:
    root = target_type("dev/turboism/sdk/cubism/transaction/TransactionManager")
    compile_fixture(context.root, "initial-promoted", True, "normal")
    promoted = compile_fixture(context.root, "initial-promoted-unmarked", True, "initial-promotion-unmarked")
    policy = copy.deepcopy(context.policy)
    policy["promotions"] = [root]
    policy = replacement_policy_for(promoted, context.historical, policy)
    path = context.root / "initial-promotion-policy.json"
    write_policy(path, policy)
    verify(promoted, context.historical, context.baseline_path, path, success=True)
    changed = compile_fixture(context.root, "initial-promoted-changed", True, "initial-promotion-change")
    expect_failure(
        verify(changed, context.historical, context.baseline_path, path, success=False),
        "initial ledger promotion shape change", "promotion", "unchanged",
    )
