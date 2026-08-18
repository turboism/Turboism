"""Fail-closed loading of SDK tier policy and immutable preview ledger."""
from __future__ import annotations

import hashlib
from pathlib import Path
from typing import Any

from sdk_api_baseline_common import BaselineError
from sdk_api_tiers_common import (
    COMMIT_RE,
    GENERATOR_VERSION,
    INITIAL_PREVIEW_LEDGER_FORMAT,
    NewPreviewAdmission,
    SCHEMA_VERSION,
    TIER_POLICY_FORMAT,
    Digest,
    closed_object,
    digest,
    strict_json,
    target_identity,
    target_object,
    unique_list,
    valid_internal_name,
)

INITIAL_PREVIEW_LEDGER_SHA256 = "21d2d371881bf46201e1e028272909f99ea86cfe1e1cf3f0d147806db83c7f95"
INITIAL_PREVIEW_LEDGER_LINE_COUNT = 1158
_LEDGER_KEYS = {"format", "schemaVersion", "generatorVersion", "reviewedBaseline", "roots"}
_POLICY_KEYS = {
    "format", "schemaVersion", "generatorVersion", "reviewedBaseline",
    "initialPreviewLedger", "stableAdditions", "promotions", "newPreview",
    "stableNegativeInventory",
}


def load_initial_preview_ledger(path: Path) -> tuple[dict[str, Any], set[str]]:
    return _load_initial_preview_ledger(path, Digest(INITIAL_PREVIEW_LEDGER_LINE_COUNT, INITIAL_PREVIEW_LEDGER_SHA256))


def load_initial_preview_ledger_for_test(
    path: Path, *, ledger_trust: Digest, expected_root_count: int = 28
) -> tuple[dict[str, Any], set[str]]:
    if not isinstance(ledger_trust, Digest):
        raise BaselineError("test initial preview ledger trust must be a Digest")
    return _load_initial_preview_ledger(path, ledger_trust, expected_root_count=expected_root_count)


def _load_initial_preview_ledger(path: Path, ledger_trust: Digest, *, expected_root_count: int = 274) -> tuple[dict[str, Any], set[str]]:
    value = strict_json(path, "initial preview ledger")
    _verify_file_trust(path, "initial preview ledger", ledger_trust)
    value = closed_object(value, _LEDGER_KEYS, "initial preview ledger")
    _verify_ledger_metadata(value)
    roots = unique_list(value["roots"], "initial preview ledger roots", target_identity)
    if len(roots) != expected_root_count:
        raise BaselineError(f"initial preview ledger must contain exactly {expected_root_count} roots")
    return value, {target_identity(root) for root in roots}


def _verify_file_trust(path: Path, label: str, expected: Digest) -> None:
    if not path.is_file():
        raise BaselineError(f"{label} is missing: {path}")
    try:
        raw = path.read_bytes()
        line_count = len(raw.decode("utf-8").splitlines())
    except (OSError, UnicodeDecodeError) as exc:
        raise BaselineError(f"{label} cannot be read as UTF-8: {path}") from exc
    actual = Digest(line_count, hashlib.sha256(raw).hexdigest())
    if actual != expected:
        raise BaselineError(_trust_mismatch(label, expected, actual))


def _trust_mismatch(label: str, expected: Digest, actual: Digest) -> str:
    return f"{label} trust-anchor mismatch: expected {expected.line_count}/{expected.sha256}, actual {actual.line_count}/{actual.sha256}"


def _verify_ledger_metadata(value: dict[str, Any]) -> None:
    if value["format"] != INITIAL_PREVIEW_LEDGER_FORMAT or value["schemaVersion"] != SCHEMA_VERSION or type(value["schemaVersion"]) is not int:
        raise BaselineError("initial preview ledger format/schema is unsupported")
    if type(value["generatorVersion"]) is not int or value["generatorVersion"] != GENERATOR_VERSION:
        raise BaselineError("initial preview ledger generator version is unsupported")
    binding = closed_object(value["reviewedBaseline"], {"commit", "canonicalDump"}, "initial preview ledger reviewedBaseline")
    if not isinstance(binding["commit"], str) or not COMMIT_RE.fullmatch(binding["commit"]):
        raise BaselineError("initial preview ledger reviewed baseline commit is invalid")
    digest(binding["canonicalDump"], "initial preview ledger reviewed canonical dump")


def load_tier_policy(
    path: Path,
    baseline: dict[str, Any],
    initial_ledger_path: Path,
    *,
    policy_trust: Digest,
    initial_ledger_trust: Digest | None = None,
    initial_ledger_root_count: int = 274,
) -> tuple[dict[str, Any], set[str], list[NewPreviewAdmission], set[str]]:
    _verify_file_trust(path, "tier policy", policy_trust)
    ledger, initial_roots = _load_ledger(
        initial_ledger_path, initial_ledger_trust, expected_root_count=initial_ledger_root_count
    )
    value = strict_json(path, "tier policy")
    value = closed_object(value, _POLICY_KEYS, "tier policy")
    _verify_policy_metadata(value, baseline, initial_ledger_trust)
    promotions = _promotions(value)
    admissions = _admissions(value)
    _verify_policy_relationships(value, initial_roots, admissions, promotions)
    _parse_inventory(value["stableNegativeInventory"])
    return value, initial_roots, admissions, promotions


def _load_ledger(path: Path, trust: Digest | None, *, expected_root_count: int):
    return (
        load_initial_preview_ledger(path)
        if trust is None
        else load_initial_preview_ledger_for_test(
            path, ledger_trust=trust, expected_root_count=expected_root_count
        )
    )


def _verify_policy_metadata(value, baseline, initial_ledger_trust: Digest | None = None):
    if value["format"] != TIER_POLICY_FORMAT or value["schemaVersion"] != SCHEMA_VERSION or type(value["schemaVersion"]) is not int:
        raise BaselineError("tier policy format/schema is unsupported")
    if type(value["generatorVersion"]) is not int or value["generatorVersion"] != GENERATOR_VERSION:
        raise BaselineError("tier policy generator version is unsupported")
    binding = closed_object(value["reviewedBaseline"], {"commit", "canonicalDump"}, "tier policy reviewedBaseline")
    if binding != {"commit": baseline["commit"], "canonicalDump": baseline["canonicalDump"]}:
        raise BaselineError("tier policy reviewed baseline binding mismatch")
    expected = (initial_ledger_trust or Digest(INITIAL_PREVIEW_LEDGER_LINE_COUNT, INITIAL_PREVIEW_LEDGER_SHA256)).as_json()
    if value["initialPreviewLedger"] != expected:
        raise BaselineError("tier policy initial preview ledger binding mismatch")
    digest(value["stableAdditions"], "tier policy stableAdditions")


def _promotions(value):
    raw = unique_list(value["promotions"], "tier policy promotions", target_identity)
    return {target_identity(item) for item in raw}


def _admissions(value):
    raw = unique_list(value["newPreview"], "tier policy newPreview", _admission_identity)
    return [_admission(item, index) for index, item in enumerate(raw)]


def _admission_identity(item: Any, label: str) -> str:
    item = closed_object(item, {"root", "admittedOwnedRecords"}, label)
    return target_identity(item["root"], label + ".root")


def _admission(item: Any, index: int) -> NewPreviewAdmission:
    label = f"tier policy newPreview[{index}]"
    item = closed_object(item, {"root", "admittedOwnedRecords"}, label)
    root = target_object(item["root"], label + ".root")
    return NewPreviewAdmission(target_identity(root), root, digest(item["admittedOwnedRecords"], label + ".admittedOwnedRecords"))


def _verify_policy_relationships(value, initial_roots, admissions, promotions):
    new_preview = {entry.identity for entry in admissions}
    if not promotions <= initial_roots | new_preview:
        raise BaselineError("tier policy promotion target is not in initial ledger or newPreview history")


def _parse_inventory(value: Any) -> dict[str, list[Any]]:
    inventory = closed_object(value, {"types", "methods", "packagePrefixes"}, "tier policy stableNegativeInventory")
    return {
        "types": _inventory_types(inventory["types"]),
        "methods": _inventory_methods(inventory["methods"]),
        "packagePrefixes": _inventory_prefixes(inventory["packagePrefixes"]),
    }


def _inventory_types(values):
    return unique_list(values, "tier policy stableNegativeInventory.types", _type_item_identity)


def _type_item_identity(item: Any, label: str) -> str:
    if not isinstance(item, str):
        raise BaselineError(f"{label} must be a non-empty internal class name")
    return target_identity({"target": "type", "name": item}, label)


def _inventory_methods(values):
    return unique_list(values, "tier policy stableNegativeInventory.methods", _method_item_identity)


def _method_item_identity(item: Any, label: str) -> str:
    if not isinstance(item, dict):
        raise BaselineError(f"{label} must be an object")
    return target_identity({"target": "method", **item}, label)


def _inventory_prefixes(values):
    return unique_list(values, "tier policy stableNegativeInventory.packagePrefixes", _prefix_identity)


def _prefix_identity(item: Any, label: str) -> str:
    valid = isinstance(item, str) and item.endswith("/") and valid_internal_name(item[:-1])
    if not valid:
        raise BaselineError(f"{label} must be a non-empty internal package prefix ending in '/'")
    return item
