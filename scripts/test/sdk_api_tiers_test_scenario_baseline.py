"""Baseline and stable-history scenarios for SDK API tier tests."""
from __future__ import annotations

import copy
import json

from sdk_api_tiers_test_commands import command, expect_failure, verify, verify_production
from sdk_api_tiers_test_constants import INITIAL_TYPE_ROOTS, ROOT
from sdk_api_tiers_test_context import TierTestContext
from sdk_api_tiers_test_fixtures import compile_fixture
from sdk_api_tiers_test_policy import owned_digest, stable_additions, target_type, write_policy
from sdk_api_tiers_test_support import fail, report_tiers


def run(context: TierTestContext) -> None:
    baseline_green(context)
    production_policy_mutation(context)
    historical_stable_reclassification(context)


def baseline_green(context: TierTestContext) -> None:
    report = context.root / "tiers.json"
    production_policy = ROOT / "docs/sdk/baselines/sdk-api-tier-policy-v1.json"
    expect_failure(
        verify_production(context.current, context.historical, context.baseline_path, context.policy_path, success=False),
        "untrusted synthetic production policy", "tier policy trust-anchor mismatch",
    )
    verify(context.current, context.historical, context.baseline_path, context.policy_path, success=True, report=report)
    assert_baseline_tiers(report_tiers(report))


def assert_baseline_tiers(tiers: dict[str, str]) -> None:
    if tiers.get("class:dev/turboism/sdk/cubism/transaction/TransactionManager") != "preview":
        fail("initial ledger class root was not preview")
    if tiers.get("class:dev/turboism/sdk/cubism/transaction/TransactionManager$NestedStable") != "stable":
        fail("nested class inherited a type marker")
    if tiers.get("package:dev/turboism/sdk/cubism/transaction") != "stable":
        fail("exact package tier ignored nested/member class records")


def production_policy_mutation(context: TierTestContext) -> None:
    production_current = compile_fixture(context.root, "production-current", True, "add-current-stable")
    production_baseline_path = context.root / "production-baseline.json"
    capture_baseline(production_current, production_baseline_path)
    mutation = production_policy_data(production_current, production_baseline_path)
    mutation_path = context.root / "production-policy-current-only-preview.json"
    write_policy(mutation_path, mutation)
    expect_failure(
        verify_production(production_current, production_current, production_baseline_path, mutation_path, success=False),
        "production policy current-only preview mutation", "tier policy trust-anchor mismatch",
    )


def capture_baseline(current, baseline_path) -> None:
    command(
        "capture", "--input", str(current), "--package-prefix", "dev.turboism.sdk",
        "--role", "pre-phase", "--commit", "0123456789abcdef0123456789abcdef01234567", "--output", str(baseline_path),
    )


def production_policy_data(current, baseline_path) -> dict[str, object]:
    policy_path = ROOT / "docs/sdk/baselines/sdk-api-tier-policy-v1.json"
    baseline = json.loads(baseline_path.read_text(encoding="utf-8"))
    policy = json.loads(policy_path.read_text(encoding="utf-8"))
    policy["reviewedBaseline"] = {"commit": baseline["commit"], "canonicalDump": baseline["canonicalDump"]}
    root = target_type("dev/turboism/sdk/stable/CurrentStable")
    policy["newPreview"] = [{"root": root, "admittedOwnedRecords": owned_digest(current, root)}]
    policy["stableAdditions"] = stable_additions(current, current, production_roots())
    return policy


def production_roots() -> set[str]:
    return {
        *(f"class:{name}" for name in INITIAL_TYPE_ROOTS),
        "method:dev/turboism/sdk/cubism/CubismFacade#transactionManager()Ldev/turboism/sdk/cubism/transaction/TransactionManager;",
        "class:dev/turboism/sdk/stable/CurrentStable",
    }


def historical_stable_reclassification(context: TierTestContext) -> None:
    current = compile_fixture(context.root, "b1-current", True, "historical-stable-marker-change")
    reject_mutable_preview_roots(context, current)
    reject_schema_valid_reclassification(context, current)


def reject_mutable_preview_roots(context: TierTestContext, current) -> None:
    policy = copy.deepcopy(context.policy)
    policy["previewRoots"] = [target_type("dev/turboism/sdk/PackageMarker")]
    path = context.root / "b1-policy.json"
    write_policy(path, policy)
    expect_failure(
        verify(current, context.historical, context.baseline_path, path, success=False),
        "B1 mutable previewRoots reclassification", "tier policy", "preview",
    )


def reject_schema_valid_reclassification(context: TierTestContext, current) -> None:
    policy = copy.deepcopy(context.policy)
    root = target_type("dev/turboism/sdk/PackageMarker")
    policy["newPreview"] = [{"root": root, "admittedOwnedRecords": owned_digest(current, root)}]
    path = context.root / "b1-schema-valid-policy.json"
    write_policy(path, policy)
    expect_failure(
        verify(current, context.historical, context.baseline_path, path, success=False),
        "B1 schema-valid historical stable reclassification", "historical", "marker", "preview",
    )
