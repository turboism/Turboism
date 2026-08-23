#!/usr/bin/env python3
"""Offline gate for the complete Cubism Core member policy."""

from __future__ import annotations

import copy
import sys
from pathlib import Path
from typing import Any, Callable

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts"))

from cubism_core_api import InventoryError, load_inventory  # noqa: E402
from cubism_core_policy import (  # noqa: E402
    bootstrap,
    compact_json,
    load_policy,
    render_java_catalog,
    render_report,
    validate_policy,
)

API_52 = ROOT / "cubism-ref/core-api/observed/cubism-core-5.2.03.json"
API_53 = ROOT / "cubism-ref/core-api/observed/cubism-core-5.3.02.json"
POLICY = ROOT / "cubism-ref/core-api/policy/cubism-core-member-policy.json"


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


def member(
    classified: list[dict[str, Any]],
    owner_suffix: str,
    name: str,
    descriptor: str,
) -> dict[str, Any]:
    matches = [
        entry
        for entry in classified
        if entry["owner"].endswith(owner_suffix)
        and entry["name"] == name
        and entry["descriptor"] == descriptor
    ]
    require(
        len(matches) == 1,
        f"expected one {owner_suffix}#{name}{descriptor}, found {len(matches)}",
    )
    return matches[0]


def main() -> int:
    api_52 = load_inventory(API_52)
    api_53 = load_inventory(API_53)
    inventories = [api_52, api_53]
    policy = load_policy(POLICY)
    validated, classified = validate_policy(policy, inventories)

    require(len(classified) == 240, "unique classified member count drifted")
    require(
        validated["summary"]["classifiedRosterSha256"]
        == "622ecdbf2d2776beb1130c02d0d3394385394928cfa3fd3d5a33c03e74a91a1c",
        "classified roster digest drifted",
    )
    require(
        validated["summary"]["versions"]
        == {
            "5.2.03": {
                "classCount": 22,
                "publicCallableCount": 158,
                "publicFieldCount": 19,
            },
            "5.3.02": {
                "classCount": 27,
                "publicCallableCount": 194,
                "publicFieldCount": 43,
            },
        },
        "per-version member policy summary drifted",
    )

    set_value = member(classified, "CubismParameterView", "setValue", "(F)V")
    require(set_value["category"] == "MODEL_WRITE", "setValue category drifted")
    require(set_value["exposure"] == "MODEL", "setValue exposure drifted")
    require(
        set_value["lifecycle"] == "BEFORE_ON_AFTER",
        "setValue lifecycle drifted",
    )

    set_opacity = member(classified, "CubismPartView", "setOpacity", "(F)V")
    require(
        set_opacity["category"] == "MODEL_WRITE"
        and set_opacity["lifecycle"] == "BEFORE_ON_AFTER",
        "setOpacity policy drifted",
    )

    update = member(classified, "CubismModel", "update", "()V")
    require(
        update["category"] == "EVALUATION"
        and update["lifecycle"] == "BEFORE_ON_AFTER",
        "model update policy drifted",
    )

    drawable_vertices = member(
        classified,
        "CubismDrawableView",
        "getVertexPositions",
        "()[F",
    )
    require(
        drawable_vertices["category"] == "MODEL_READ"
        and drawable_vertices["exposure"] == "MODEL",
        "drawable geometry policy drifted",
    )

    offscreen = member(
        classified,
        "CubismModel",
        "getOffscreenRendering",
        "()Lcom/live2d/sdk/cubism/core/CubismOffscreenRendering;",
    )
    require(
        offscreen["versions"] == ["5.3.02"],
        "offscreen version coverage drifted",
    )

    for owner_suffix, name, descriptor in (
        ("CubismModel", "close", "()V"),
        ("CubismModel", "getNativeHandle", "()J"),
        (
            "Live2DCubismCore",
            "setLogger",
            "(Lcom/live2d/sdk/cubism/core/ICubismLogger;)V",
        ),
    ):
        restricted = member(classified, owner_suffix, name, descriptor)
        require(
            restricted["category"] == "RUNTIME_INTERNAL"
            and restricted["exposure"] == "INTERNAL",
            f"restricted policy drifted for {owner_suffix}#{name}",
        )

    generated = bootstrap(inventories)
    reversed_generated = bootstrap(list(reversed(inventories)))
    require(
        generated == reversed_generated,
        "policy bootstrap depends on inventory argument ordering",
    )
    require(
        POLICY.read_text(encoding="utf-8") == compact_json(generated),
        "tracked member policy has drifted from deterministic bootstrap",
    )
    generated_report = render_report(validated, classified)
    reversed_policy, reversed_classified = validate_policy(
        policy,
        list(reversed(inventories)),
    )
    require(
        generated_report == render_report(reversed_policy, reversed_classified),
        "generated member policy report depends on inventory argument ordering",
    )

    generated_java = render_java_catalog(validated, classified)
    require(
        generated_java
        == render_java_catalog(reversed_policy, reversed_classified),
        "generated Java catalog depends on inventory argument ordering",
    )
    require(
        "import com.live2d." not in generated_java
        and "java.lang.reflect" not in generated_java
        and "MethodHandle" not in generated_java,
        "generated Java catalog leaked a Core or reflection type",
    )
    require(
        validated["summary"]["classifiedRosterSha256"] in generated_java,
        "generated Java catalog lost the classified roster binding",
    )

    mutation = copy.deepcopy(policy)
    mutation["summary"]["classifiedRosterSha256"] = "0" * 64
    expect_invalid(
        "summary/roster digest does not match",
        lambda: validate_policy(mutation, inventories),
    )

    mutation = copy.deepcopy(policy)
    mutation["rules"] = mutation["rules"][:-1]
    expect_invalid(
        "unclassified public member",
        lambda: validate_policy(mutation, inventories),
    )

    mutation = copy.deepcopy(policy)
    mutation["rules"][0]["category"] = "UNKNOWN"
    expect_invalid(
        "category is invalid",
        lambda: validate_policy(mutation, inventories),
    )

    mutation = copy.deepcopy(policy)
    mutation["rules"].append(
        {
            "id": "unused",
            "match": {"name": "doesNotExist"},
            "category": "MODEL_READ",
            "exposure": "MODEL",
        }
    )
    expect_invalid(
        "unused rules",
        lambda: validate_policy(mutation, inventories),
    )

    print(
        "PASS: Cubism Core member policy "
        "(240 unique members, 15 rules, roster 74c87aa7)"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
