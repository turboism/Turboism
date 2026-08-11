"""Synthetic policy construction helpers for SDK API tier scenarios."""
from __future__ import annotations

import copy
import hashlib
import json
from pathlib import Path

from sdk_api_baseline import canonical_identity, canonical_records_for_tiers
from sdk_api_tiers import Digest, record_tiers
from sdk_api_tiers_test_constants import INITIAL_TYPE_ROOTS, LEDGER, PREFIX
from sdk_api_tiers_test_support import fail


def target_type(name: str) -> dict[str, str]:
    return {"target": "type", "name": name}


def target_method(owner: str, name: str, descriptor: str) -> dict[str, str]:
    return {"target": "method", "owner": owner, "name": name, "descriptor": descriptor}


def digest(records: list[str]) -> dict[str, object]:
    ordered = sorted(records)
    payload = "".join(record + "\n" for record in ordered).encode("utf-8")
    return {"lineCount": len(ordered), "sha256": hashlib.sha256(payload).hexdigest()}


def tier_records(path: Path) -> list[str]:
    return canonical_records_for_tiers(path, PREFIX)[0]


def stable_additions(current: Path, reference: Path, preview_roots: set[str]) -> dict[str, object]:
    current_records, current_facts, _sha, _size = canonical_records_for_tiers(current, PREFIX)
    reference_ids = {canonical_identity(record) for record in tier_records(reference)}
    tiers = record_tiers(current_records, current_facts.direct_markers, preview_roots)
    return digest(
        record for record in current_records
        if canonical_identity(record) not in reference_ids and tiers[canonical_identity(record)] == "stable"
    )


def owned_digest(current: Path, root: dict[str, str]) -> dict[str, object]:
    owned = type_owned_records(current, root) if root["target"] == "type" else method_owned_records(current, root)
    if not owned:
        fail(f"cannot admit empty owned record set for {root}")
    return digest(owned)


def type_owned_records(current: Path, root: dict[str, str]) -> list[str]:
    owner = root["name"]
    prefixes = (f"field:{owner}#", f"record-component:{owner}#", f"method:{owner}#")
    return [record for record in tier_records(current) if canonical_identity(record) == f"class:{owner}" or canonical_identity(record).startswith(prefixes)]


def method_owned_records(current: Path, root: dict[str, str]) -> list[str]:
    identity = f"method:{root['owner']}#{root['name']}{root['descriptor']}"
    return [record for record in tier_records(current) if canonical_identity(record) == identity]


def ledger_binding() -> dict[str, object]:
    raw = LEDGER.read_bytes()
    return {"lineCount": len(raw.decode("utf-8").splitlines()), "sha256": hashlib.sha256(raw).hexdigest()}


def make_policy(baseline: dict[str, object], current: Path, historical: Path) -> dict[str, object]:
    initial_roots = initial_roots_with_method()
    return {
        "format": "turboism.sdk.api-tier-policy", "schemaVersion": 1, "generatorVersion": 1,
        "reviewedBaseline": {"commit": baseline["commit"], "canonicalDump": baseline["canonicalDump"]},
        "initialPreviewLedger": ledger_binding(), "stableAdditions": stable_additions(current, historical, initial_roots),
        "promotions": [], "newPreview": [], "stableNegativeInventory": stable_inventory(),
    }


def initial_roots_with_method() -> set[str]:
    roots = {f"class:{name}" for name in INITIAL_TYPE_ROOTS}
    roots.add("method:dev/turboism/sdk/cubism/CubismFacade#transactionManager()Ldev/turboism/sdk/cubism/transaction/TransactionManager;")
    return roots


def stable_inventory() -> dict[str, object]:
    return {
        "types": ["dev/turboism/sdk/PreviewApi", "dev/turboism/sdk/stable/StableService", "dev/turboism/sdk/PackageMarker"],
        "methods": [{"owner": "dev/turboism/sdk/cubism/CubismFacade", "name": "stableFacade", "descriptor": "()Ljava/lang/String;"}],
        "packagePrefixes": ["dev/turboism/sdk/stable/"],
    }


def write_policy(path: Path, policy: dict[str, object]) -> None:
    path.write_text(json.dumps(policy, indent=2, sort_keys=True) + "\n", encoding="utf-8")


def write_raw(path: Path, text: str) -> None:
    path.write_text(text, encoding="utf-8")


def test_policy_digest(policy_path: Path) -> Digest:
    raw = policy_path.read_bytes()
    return Digest(len(raw.decode("utf-8").splitlines()), hashlib.sha256(raw).hexdigest())


def replacement_policy_for(current: Path, historical: Path, policy: dict[str, object]) -> dict[str, object]:
    result = copy.deepcopy(policy)
    roots = initial_roots_with_method()
    add_admissions(roots, result["newPreview"])
    remove_promotions(roots, result["promotions"])
    result["stableAdditions"] = stable_additions(current, historical, roots)
    return result


def add_admissions(roots: set[str], admissions) -> None:
    for entry in admissions:
        roots.add(root_identity(entry["root"]))


def remove_promotions(roots: set[str], promotions) -> None:
    for promotion in promotions:
        roots.discard(root_identity(promotion))


def root_identity(root: dict[str, str]) -> str:
    if root["target"] == "type":
        return f"class:{root['name']}"
    return f"method:{root['owner']}#{root['name']}{root['descriptor']}"
