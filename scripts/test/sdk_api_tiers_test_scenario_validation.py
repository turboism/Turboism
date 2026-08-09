"""Malformed-policy, marker, and boolean scenarios for SDK API tier tests."""
from __future__ import annotations

import copy
import json

from sdk_api_baseline import BaselineError, canonical_records_for_tiers
from sdk_api_baseline_cli import load_baseline
from sdk_api_tiers import verify_tier_compatible_for_test
from sdk_api_tiers_test_commands import expect_failure, verify
from sdk_api_tiers_test_constants import LEDGER, PREFIX
from sdk_api_tiers_test_context import TierTestContext
from sdk_api_tiers_test_fixtures import compile_fixture
from sdk_api_tiers_test_policy import test_policy_digest, write_policy, write_raw
from sdk_api_tiers_test_support import fail


def run(context: TierTestContext) -> None:
    malformed_policy_shapes(context)
    illegal_marker_placements(context)
    boolean_metadata(context)


def malformed_policy_shapes(context: TierTestContext) -> None:
    duplicate_json_keys(context)
    duplicate_inventory_entries(context)
    malformed_new_preview(context)


def duplicate_json_keys(context: TierTestContext) -> None:
    text = json.dumps(context.policy, sort_keys=True)
    top = context.root / "duplicate-top.json"
    write_raw(top, text.replace('"format": "turboism.sdk.api-tier-policy"', '"format": "turboism.sdk.api-tier-policy", "format": "turboism.sdk.api-tier-policy"', 1))
    expect_failure(verify(context.current, context.historical, context.baseline_path, top, success=False), "top-level duplicate key", "duplicate")
    nested = context.root / "duplicate-nested.json"
    write_raw(nested, text.replace('"lineCount":', '"lineCount": 1, "lineCount":', 1))
    expect_failure(verify(context.current, context.historical, context.baseline_path, nested, success=False), "nested duplicate key", "duplicate")


def duplicate_inventory_entries(context: TierTestContext) -> None:
    for inventory_field in ("types", "methods", "packagePrefixes"):
        policy = copy.deepcopy(context.policy)
        values = policy["stableNegativeInventory"][inventory_field]
        values.append(copy.deepcopy(values[0]))
        path = context.root / f"duplicate-{inventory_field}.json"
        write_policy(path, policy)
        expect_failure(
            verify(context.current, context.historical, context.baseline_path, path, success=False),
            f"duplicate stable negative inventory {inventory_field}", "duplicate", "inventory",
        )


def malformed_new_preview(context: TierTestContext) -> None:
    policy = copy.deepcopy(context.policy)
    policy["newPreview"] = [{"root": {"target": "type"}}]
    path = context.root / "malformed-nested-policy.json"
    write_policy(path, policy)
    expect_failure(
        verify(context.current, context.historical, context.baseline_path, path, success=False),
        "malformed nested newPreview policy", "newpreview", "target", "policy",
    )


def illegal_marker_placements(context: TierTestContext) -> None:
    for marker_kind in marker_kinds():
        illegal = compile_fixture(context.root, f"illegal-{marker_kind}", True, f"illegal-{marker_kind}")
        expect_failure(
            verify(illegal, context.historical, context.baseline_path, context.policy_path, success=False),
            f"illegal PreviewApi {marker_kind} placement", "previewapi", "illegal", "marker",
        )


def marker_kinds() -> tuple[str, ...]:
    return (
        "parameter", "type-use", "field", "record-component", "package", "constructor",
        "private-method", "package-method", "public-method-nonexported-owner", "package-class",
        "private-nested-class", "package-private-outer-public-nested-class",
        "package-private-outer-public-nested-method", "private-outer-public-nested-class",
        "private-outer-public-nested-method", "nested-annotation",
    )


def boolean_metadata(context: TierTestContext) -> None:
    boolean_policy_fields(context)
    boolean_ledger_fields(context)


def boolean_policy_fields(context: TierTestContext) -> None:
    for field_path in (("schemaVersion",), ("generatorVersion",), ("stableAdditions", "lineCount")):
        policy = copy.deepcopy(context.policy)
        set_nested_true(policy, field_path)
        path = context.root / ("bool-" + "-".join(field_path) + ".json")
        write_policy(path, policy)
        expect_failure(
            verify(context.current, context.historical, context.baseline_path, path, success=False),
            "tier policy boolean integer mutation", "invalid", "schema", "generator", "linecount",
        )


def set_nested_true(value: dict[str, object], field_path: tuple[str, ...]) -> None:
    target = value
    for key in field_path[:-1]:
        target = target[key]
    target[field_path[-1]] = True


def boolean_ledger_fields(context: TierTestContext) -> None:
    for field_path in (("schemaVersion",), ("generatorVersion",), ("roots",)):
        data = json.loads(LEDGER.read_text(encoding="utf-8"))
        mutate_ledger_boolean(data, field_path)
        path = context.root / ("ledger-bool-" + "-".join(field_path) + ".json")
        write_policy(path, data)
        assert_ledger_boolean_rejected(context, path, field_path)


def mutate_ledger_boolean(data: dict[str, object], field_path: tuple[str, ...]) -> None:
    if field_path == ("roots",):
        data["roots"][0] = True
    else:
        data[field_path[0]] = True


def assert_ledger_boolean_rejected(context: TierTestContext, ledger_path, field_path) -> None:
    try:
        verify_tier_compatible_for_test(
            policy_path=context.policy_path, initial_ledger_path=ledger_path,
            baseline=load_baseline(context.baseline_path),
            reference_records=canonical_records_for_tiers(context.historical, PREFIX)[0],
            current_records=canonical_records_for_tiers(context.current, PREFIX)[0],
            current_markers=canonical_records_for_tiers(context.current, PREFIX)[1].direct_markers,
            invalid_marker_usages=canonical_records_for_tiers(context.current, PREFIX)[1].invalid_usages,
            policy_trust=test_policy_digest(context.policy_path),
            initial_ledger_trust=test_policy_digest(ledger_path),
        )
    except BaselineError as exc:
        if "Traceback" in str(exc):
            fail(f"ledger boolean mutation leaked traceback: {exc}")
    else:
        fail(f"ledger boolean mutation unexpectedly passed: {field_path}")
