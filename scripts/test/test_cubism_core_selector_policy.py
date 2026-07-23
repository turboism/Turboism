#!/usr/bin/env python3
"""Offline gate for generated Cubism Core selector contracts."""

from __future__ import annotations

import copy
import sys
from pathlib import Path
from typing import Any, Callable

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts"))

from cubism_core_api import InventoryError  # noqa: E402
from cubism_core_selector_policy import (  # noqa: E402
    classify_selector_roster,
    compact_json,
    load_json,
    load_packs,
    normalize_policy,
    render_java,
    validate_policy,
)

POLICY = ROOT / "cubism-ref/core-api/policy/cubism-core-selector-policy.json"
PACK_52 = ROOT / "cubism-ref/mapping-packs/draft/cubism-5.2-core-model-read.json"
PACK_53 = ROOT / "cubism-ref/mapping-packs/draft/cubism-5.3.02-core-model-read.json"


def require(condition: bool, message: str) -> None:
    if not condition:
        raise AssertionError(message)


def expect_invalid(fragment: str, operation: Callable[[], Any]) -> None:
    try:
        operation()
    except InventoryError as exc:
        require(
            fragment in str(exc),
            f"expected error containing {fragment!r}, got {exc!r}",
        )
        return
    raise AssertionError(f"expected InventoryError containing {fragment!r}")


def selector(roster: list[dict[str, Any]], constant: str) -> dict[str, Any]:
    matches = [entry for entry in roster if entry["constant"] == constant]
    require(len(matches) == 1, f"expected one selector {constant}")
    return matches[0]


def main() -> int:
    policy = load_json(POLICY)
    packs = load_packs([PACK_52, PACK_53])
    validated, roster = validate_policy(policy, packs)

    require(len(roster) == 71, "selector count drifted")
    require(
        validated["summary"]["selectorRosterSha256"]
        == "c78708fe6953a9ce32928b95678d4abbeeb3e2aff2d42fafc5fce8ad02cd1579",
        "selector roster digest drifted",
    )
    require(
        validated["summary"]["versions"]
        == {
            "5.2": {"entryCount": 69},
            "5.3.02": {"entryCount": 70},
        },
        "selector profile counts drifted",
    )

    repeat = selector(roster, "PARAMETERS_GET_REPEATS")
    require(
        repeat["alias"] == "cubism.core.parameters.repeats"
        and repeat["profiles"] == ["5.3.02"]
        and repeat["descriptor"] == "()[Z",
        "5.3-only repeat selector drifted",
    )
    version = selector(roster, "GET_VERSION")
    require(
        version["role"] == "VERSION_PROBE"
        and version["profiles"] == ["5.2", "5.3.02"],
        "version probe selector drifted",
    )
    require(
        sum(entry["role"] == "VERSION_PROBE" for entry in roster) == 6,
        "version probe selector count drifted",
    )

    normalized = normalize_policy(policy, packs)
    require(
        POLICY.read_text(encoding="utf-8") == compact_json(normalized),
        "tracked selector policy has drifted from deterministic normalization",
    )

    reversed_packs = load_packs([PACK_53, PACK_52])
    reversed_policy, reversed_roster = validate_policy(policy, reversed_packs)
    generated = render_java(validated, roster)
    require(
        generated == render_java(reversed_policy, reversed_roster),
        "selector contract generation depends on pack argument ordering",
    )
    require(
        "public final class CorePublicApiSelectorContract" in generated,
        "generated selector contract lost its public class",
    )
    require(
        "public static final String PARAMETERS_GET_REPEATS" in generated,
        "generated selector contract lost repeat selector",
    )
    require(
        "structuralMethodAliasesFor" in generated
        and "STRUCTURAL_METHOD_ALIASES_5_2" in generated
        and "STRUCTURAL_METHOD_ALIASES_5_3_02" in generated,
        "generated selector contract lost structural call-site metadata",
    )
    require(
        validated["summary"]["selectorRosterSha256"] in generated,
        "generated selector contract lost roster binding",
    )
    require(
        "import com.live2d." not in generated
        and "java.lang.reflect" not in generated
        and "MethodHandle" not in generated,
        "generated selector contract leaked Core/reflection types",
    )

    mutation = copy.deepcopy(policy)
    mutation["summary"]["selectorRosterSha256"] = "0" * 64
    expect_invalid(
        "summary/roster digest mismatch",
        lambda: validate_policy(mutation, packs),
    )

    mutation = copy.deepcopy(policy)
    mutation["selectors"] = mutation["selectors"][:-1]
    expect_invalid(
        "alias mismatch",
        lambda: validate_policy(mutation, packs),
    )

    mutation = copy.deepcopy(policy)
    mutation["selectors"][0]["constant"] = mutation["selectors"][1]["constant"]
    expect_invalid(
        "duplicate selector constant",
        lambda: validate_policy(mutation, packs),
    )

    mutation = copy.deepcopy(policy)
    target = next(
        entry
        for entry in mutation["selectors"]
        if entry["constant"] == "PARAMETERS_GET_REPEATS"
    )
    target["profiles"] = ["5.2", "5.3.02"]
    expect_invalid(
        "profile coverage mismatch",
        lambda: validate_policy(mutation, packs),
    )

    pack_mutation = copy.deepcopy(load_json(PACK_52))
    pack_mutation["entries"] = pack_mutation["entries"][:-1]
    mutated_packs = {
        "5.2": classify_pack(pack_mutation),
        "5.3.02": packs["5.3.02"],
    }
    expect_invalid(
        "profile coverage mismatch",
        lambda: classify_selector_roster(policy, mutated_packs),
    )

    print(
        "PASS: Cubism Core selector policy "
        "(71 selectors, 69/70 profile entries, roster c78708fe)"
    )
    return 0


def classify_pack(document: dict[str, Any]) -> dict[str, dict[str, Any]]:
    aliases: dict[str, dict[str, Any]] = {}
    for entry in document["entries"]:
        aliases[entry["name"]] = entry
    return aliases


if __name__ == "__main__":
    raise SystemExit(main())
