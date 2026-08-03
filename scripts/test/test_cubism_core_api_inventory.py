#!/usr/bin/env python3
"""Offline gate for Cubism Core public-API inventories and deterministic rendering."""

from __future__ import annotations

import copy
import sys
from pathlib import Path
from typing import Any, Callable

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts"))

from cubism_core_api import (  # noqa: E402
    InventoryError,
    canonical_json,
    decode_json,
    load_inventory,
    parse_javap_class,
    render_reference,
    validate_document,
)

API_52 = ROOT / "cubism-ref/core-api/observed/cubism-core-5.2.json"
API_53 = ROOT / "cubism-ref/core-api/observed/cubism-core-5.3.02.json"
CORE_PREFIX = "com.live2d.sdk.cubism.core."
CORE_MODEL_READ_PACKS = {
    "5.2": ROOT
    / "cubism-ref/mapping-packs/draft/cubism-5.2-core-model-read.json",
    "5.3.02": ROOT
    / "cubism-ref/mapping-packs/draft/cubism-5.3.02-core-model-read.json",
}
CORE_PROFILES = {
    "5.2": ROOT / "cubism-ref/profiles/draft/cubism-5.2.json",
    "5.3.02": ROOT / "cubism-ref/profiles/draft/cubism-5.3.02.json",
}


def fail(message: str) -> None:
    raise AssertionError(message)


def require(condition: bool, message: str) -> None:
    if not condition:
        fail(message)


def expect_invalid(
    fragment: str,
    operation: Callable[[], Any],
) -> None:
    try:
        operation()
    except InventoryError as exc:
        require(
            fragment in str(exc),
            f"expected error containing {fragment!r}, got {exc!r}",
        )
        return
    fail(f"expected InventoryError containing {fragment!r}")


def classes(document: dict[str, Any]) -> dict[str, dict[str, Any]]:
    return {entry["name"]: entry for entry in document["classes"]}


def find_member(
    document: dict[str, Any],
    class_suffix: str,
    name: str,
    descriptor: str | None = None,
) -> dict[str, Any]:
    class_name = CORE_PREFIX + class_suffix
    try:
        class_entry = classes(document)[class_name]
    except KeyError as exc:
        raise AssertionError(f"missing class {class_name}") from exc
    candidates = [
        member
        for member in class_entry["members"]
        if member["name"] == name
        and (descriptor is None or member["descriptor"] == descriptor)
    ]
    require(
        len(candidates) == 1,
        f"expected one {class_name}#{name} {descriptor or ''}, "
        f"found {len(candidates)}",
    )
    return candidates[0]


def test_parser_contract() -> None:
    sample = """Compiled from "Sample.java"
public final class com.live2d.sdk.cubism.core.Sample {
  public static final byte FLAG = 1;
    descriptor: B

  public com.live2d.sdk.cubism.core.Sample();
    descriptor: ()V

  public int[] values(java.lang.String);
    descriptor: (Ljava/lang/String;)[I
}
"""
    parsed = parse_javap_class(CORE_PREFIX + "Sample", sample)
    require(parsed is not None, "public javap sample was skipped")
    assert parsed is not None
    require(
        parsed["declaration"]
        == "public final class com.live2d.sdk.cubism.core.Sample",
        "class declaration was not normalized",
    )
    require(
        [member["kind"] for member in parsed["members"]]
        == ["field", "constructor", "method"],
        "member kind classification/sort changed",
    )
    require(
        parsed["members"][0]["constantValue"] == "1",
        "compile-time constant was not captured",
    )
    require(
        parsed["members"][2]["descriptor"] == "(Ljava/lang/String;)[I",
        "JVM descriptor was not preserved",
    )

    package_private = """Compiled from "Hidden.java"
class com.live2d.sdk.cubism.core.Hidden {
  public static void call();
    descriptor: ()V
}
"""
    require(
        parse_javap_class(CORE_PREFIX + "Hidden", package_private) is None,
        "package-private class leaked into the public inventory",
    )


def test_inventory_facts(
    api_52: dict[str, Any],
    api_53: dict[str, Any],
) -> None:
    expected = {
        "5.2": {
            "classCount": 22,
            "publicCallableCount": 158,
            "publicFieldCount": 19,
            "sha256": (
                "85959a0572be02ee45d128cfdaf9046631241310b741d6b149d295a0dec7451e"
            ),
            "sizeBytes": 36237,
        },
        "5.3.02": {
            "classCount": 27,
            "publicCallableCount": 194,
            "publicFieldCount": 43,
            "sha256": (
                "98f4dac9a9508a6e255f6f3862608409a83e29c9009a7f0fcf517e06658164e4"
            ),
            "sizeBytes": 42471,
        },
    }
    for document in (api_52, api_53):
        version = document["cubismVersion"]
        facts = expected[version]
        require(document["status"] == "OBSERVED", f"{version}: bad status")
        require(
            document["authorizesRuntime"] is False,
            f"{version}: observation unexpectedly authorizes runtime",
        )
        require(
            document["summary"]
            == {
                "classCount": facts["classCount"],
                "publicCallableCount": facts["publicCallableCount"],
                "publicFieldCount": facts["publicFieldCount"],
            },
            f"{version}: count drift",
        )
        require(
            document["artifact"]["sha256"] == facts["sha256"],
            f"{version}: exact artifact digest drift",
        )
        require(
            document["artifact"]["sizeBytes"] == facts["sizeBytes"],
            f"{version}: exact artifact size drift",
        )
        require(
            document["artifact"]["fileName"] == "Live2DCubismCore.jar",
            f"{version}: artifact path/name is not canonical",
        )
        serialized = canonical_json(document)
        require("/workspace/" not in serialized, f"{version}: absolute path leak")
        require("turboism-legacy" not in serialized, f"{version}: legacy path leak")
        require(
            "Live2DCubismCoreJNI" not in serialized,
            f"{version}: package-private JNI class leaked",
        )

    names_52 = set(classes(api_52))
    names_53 = set(classes(api_53))
    require(not names_52 - names_53, "5.2 unexpectedly has unique public classes")
    require(
        names_53 - names_52
        == {
            CORE_PREFIX + "CubismAlphaBlendType",
            CORE_PREFIX + "CubismColorBlendType",
            CORE_PREFIX + "CubismOffscreenRendering",
            CORE_PREFIX + "CubismOffscreenRenderingView",
            CORE_PREFIX + "CubismRenderOrders",
        },
        "5.3.02-only public class set drifted",
    )

    version_probe = find_member(
        api_52,
        "Live2DCubismCore",
        "getVersion",
        "()Lcom/live2d/sdk/cubism/core/CubismCoreVersion;",
    )
    require(
        version_probe["access"] == ["public", "static"],
        "Core version probe access drifted",
    )
    find_member(api_53, "CubismParameterView", "isRepeat", "()Z")
    find_member(api_53, "CubismParameters", "getParameterRepeats", "()[Z")
    repeat_members_52 = classes(api_52)[
        CORE_PREFIX + "CubismParameterView"
    ]["members"]
    require(
        not any(
            member["name"] == "isRepeat" and member["descriptor"] == "()Z"
            for member in repeat_members_52
        ),
        "5.2 unexpectedly exposes parameter repeat",
    )


def test_exact_descriptor_and_constant_facts(
    api_52: dict[str, Any],
    api_53: dict[str, Any],
) -> None:
    offscreen_part = find_member(
        api_53,
        "CubismPartView",
        "getOffscreenIndices",
        "()I",
    )
    offscreen_reference = find_member(
        api_53,
        "CubismOffscreenRenderingView",
        "getReferenceObjectIndices",
        "()I",
    )
    require(
        offscreen_part["declaration"]
        == "public int getOffscreenIndices();",
        "part offscreen observation drifted",
    )
    require(
        offscreen_reference["declaration"]
        == "public int getReferenceObjectIndices();",
        "offscreen reference observation drifted",
    )

    for document in (api_52, api_53):
        additive = find_member(
            document,
            "CubismDrawableFlag$ConstantFlag",
            "BLEND_ADDITIVE",
            "B",
        )
        visible = find_member(
            document,
            "CubismDrawableFlag$DynamicFlag",
            "IS_VISIBLE",
            "B",
        )
        changed = find_member(
            document,
            "CubismDrawableFlag$DynamicFlag",
            "VERTEX_POSITIONS_DID_CHANGE",
            "B",
        )
        require(additive.get("constantValue") == "1", "additive flag drift")
        require(visible.get("constantValue") == "1", "visible flag drift")
        require(changed.get("constantValue") == "32", "vertex flag drift")


def test_core_model_read_mapping_packs(
    api_52: dict[str, Any],
    api_53: dict[str, Any],
) -> None:
    expected = {
        "cubism.core.live2d.class": {
            "kind": "class",
            "owner": "com/live2d/sdk/cubism/core/Live2DCubismCore",
            "runtime": "com/live2d/sdk/cubism/core/Live2DCubismCore",
            "descriptor": None,
            "required": 1,
            "forbidden": 0,
            "access": {"public"},
        },
        "cubism.core.version.class": {
            "kind": "class",
            "owner": "com/live2d/sdk/cubism/core/CubismCoreVersion",
            "runtime": "com/live2d/sdk/cubism/core/CubismCoreVersion",
            "descriptor": None,
            "required": 1,
            "forbidden": 0,
            "access": {"public"},
        },
        "cubism.core.live2d.get-version": {
            "kind": "method",
            "owner": "com/live2d/sdk/cubism/core/Live2DCubismCore",
            "runtime": "getVersion",
            "descriptor": "()Lcom/live2d/sdk/cubism/core/CubismCoreVersion;",
            "required": 9,
            "forbidden": 0,
            "access": {"public", "static"},
        },
        "cubism.core.version.major": {
            "kind": "method",
            "owner": "com/live2d/sdk/cubism/core/CubismCoreVersion",
            "runtime": "getMajor",
            "descriptor": "()I",
            "required": 1,
            "forbidden": 8,
            "access": {"public"},
        },
        "cubism.core.version.minor": {
            "kind": "method",
            "owner": "com/live2d/sdk/cubism/core/CubismCoreVersion",
            "runtime": "getMinor",
            "descriptor": "()I",
            "required": 1,
            "forbidden": 8,
            "access": {"public"},
        },
        "cubism.core.version.patch": {
            "kind": "method",
            "owner": "com/live2d/sdk/cubism/core/CubismCoreVersion",
            "runtime": "getPatch",
            "descriptor": "()I",
            "required": 1,
            "forbidden": 8,
            "access": {"public"},
        },
    }

    def class_selector(owner: str) -> dict[str, Any]:
        return {
            "kind": "class",
            "owner": owner,
            "runtime": owner,
            "descriptor": None,
            "required": 1,
            "forbidden": 0,
            "access": {"public"},
        }

    def instance_selector(
        owner: str,
        runtime: str,
        descriptor: str,
    ) -> dict[str, Any]:
        return {
            "kind": "method",
            "owner": owner,
            "runtime": runtime,
            "descriptor": descriptor,
            "required": 1,
            "forbidden": 8,
            "access": {"public"},
        }

    model_owner = "com/live2d/sdk/cubism/core/CubismModel"
    canvas_owner = "com/live2d/sdk/cubism/core/CubismCanvasInfo"
    parameters_owner = "com/live2d/sdk/cubism/core/CubismParameters"
    parameter_type_owner = parameters_owner + "$ParameterType"
    expected.update(
        {
            "cubism.core.model.class": class_selector(model_owner),
            "cubism.core.canvas-info.class": class_selector(canvas_owner),
            "cubism.core.parameters.class": class_selector(parameters_owner),
            "cubism.core.parameter-type.class": class_selector(
                parameter_type_owner
            ),
            "cubism.core.model.get-canvas-info": instance_selector(
                model_owner,
                "getCanvasInfo",
                "()Lcom/live2d/sdk/cubism/core/CubismCanvasInfo;",
            ),
            "cubism.core.model.get-parameters": instance_selector(
                model_owner,
                "getParameters",
                "()Lcom/live2d/sdk/cubism/core/CubismParameters;",
            ),
            "cubism.core.canvas-info.size-in-pixels": instance_selector(
                canvas_owner,
                "getSizeInPixels",
                "()[F",
            ),
            "cubism.core.canvas-info.origin-in-pixels": instance_selector(
                canvas_owner,
                "getOriginInPixels",
                "()[F",
            ),
            "cubism.core.canvas-info.pixels-per-unit": instance_selector(
                canvas_owner,
                "getPixelsPerUnit",
                "()F",
            ),
            "cubism.core.parameters.count": instance_selector(
                parameters_owner,
                "getCount",
                "()I",
            ),
            "cubism.core.parameters.default-values": instance_selector(
                parameters_owner,
                "getDefaultValues",
                "()[F",
            ),
            "cubism.core.parameters.ids": instance_selector(
                parameters_owner,
                "getIds",
                "()[Ljava/lang/String;",
            ),
            "cubism.core.parameters.key-counts": instance_selector(
                parameters_owner,
                "getKeyCounts",
                "()[I",
            ),
            "cubism.core.parameters.key-values": instance_selector(
                parameters_owner,
                "getKeyValues",
                "()[[F",
            ),
            "cubism.core.parameters.maximum-values": instance_selector(
                parameters_owner,
                "getMaximumValues",
                "()[F",
            ),
            "cubism.core.parameters.minimum-values": instance_selector(
                parameters_owner,
                "getMinimumValues",
                "()[F",
            ),
            "cubism.core.parameters.types": instance_selector(
                parameters_owner,
                "getTypes",
                "()[Lcom/live2d/sdk/cubism/core/CubismParameters$ParameterType;",
            ),
            "cubism.core.parameters.values": instance_selector(
                parameters_owner,
                "getValues",
                "()[F",
            ),
            "cubism.core.parameter-type.number": instance_selector(
                parameter_type_owner,
                "getNumber",
                "()I",
            ),
        }
    )
    repeat_selector = instance_selector(
        parameters_owner,
        "getParameterRepeats",
        "()[Z",
    )
    families = {
        "parts": {
            "class": "CubismParts",
            "methods": {
                "count": ("getCount", "()I"),
                "ids": ("getIds", "()[Ljava/lang/String;"),
                "opacities": ("getOpacities", "()[F"),
                "parent-part-indices": ("getParentPartIndices", "()[I"),
            },
        },
        "drawables": {
            "class": "CubismDrawables",
            "methods": {
                "count": ("getCount", "()I"),
                "ids": ("getIds", "()[Ljava/lang/String;"),
                "constant-flags": ("getConstantFlags", "()[B"),
                "dynamic-flags": ("getDynamicFlags", "()[B"),
                "texture-indices": ("getTextureIndices", "()[I"),
                "draw-orders": ("getDrawOrders", "()[I"),
                "opacities": ("getOpacities", "()[F"),
                "mask-counts": ("getMaskCounts", "()[I"),
                "masks": ("getMasks", "()[[I"),
                "vertex-counts": ("getVertexCounts", "()[I"),
                "vertex-positions": ("getVertexPositions", "()[[F"),
                "vertex-uvs": ("getVertexUvs", "()[[F"),
                "index-counts": ("getIndexCounts", "()[I"),
                "indices": ("getIndices", "()[[S"),
                "multiply-colors": ("getMultiplyColors", "()[[F"),
                "screen-colors": ("getScreenColors", "()[[F"),
                "parent-part-indices": ("getParentPartIndices", "()[I"),
                "parent-deformer-indices": ("getParentDeformsers", "()[I"),
                "parameter-counts": ("getParameterCounts", "()[I"),
                "parameters": ("getParameters", "()[[I"),
            },
        },
        "deformers": {
            "class": "CubismDeformers",
            "methods": {
                "count": ("getCount", "()I"),
                "ids": ("getIds", "()[Ljava/lang/String;"),
                "parent-deformer-indices": ("getParentDeformsers", "()[I"),
                "parameter-counts": ("getParameterCounts", "()[I"),
                "parameters": ("getParameters", "()[[I"),
            },
        },
        "glues": {
            "class": "CubismGlues",
            "methods": {
                "count": ("getCount", "()I"),
                "ids": ("getIds", "()[Ljava/lang/String;"),
                "drawables-a": ("getDrawablesA", "()[I"),
                "drawables-b": ("getDrawablesB", "()[I"),
                "parameter-counts": ("getParameterCounts", "()[I"),
                "parameters": ("getParameters", "()[[I"),
            },
        },
    }
    for family, facts in families.items():
        owner = "com/live2d/sdk/cubism/core/" + facts["class"]
        expected[f"cubism.core.{family}.class"] = class_selector(owner)
        expected[f"cubism.core.model.get-{family}"] = instance_selector(
            model_owner,
            "get" + facts["class"][6:],
            f"()L{owner};",
        )
        for suffix, (runtime, descriptor) in facts["methods"].items():
            expected[f"cubism.core.{family}.{suffix}"] = instance_selector(
                owner, runtime, descriptor
            )

    for document in (api_52, api_53):
        version = document["cubismVersion"]
        expected_for_version = dict(expected)
        if version == "5.3.02":
            expected_for_version["cubism.core.parameters.repeats"] = repeat_selector
            expected_for_version["cubism.core.drawables.blend-modes"] = instance_selector(
                "com/live2d/sdk/cubism/core/CubismDrawables", "getBlendModes", "()[I"
            )
        else:
            expected_for_version["cubism.core.drawables.render-orders"] = instance_selector(
                "com/live2d/sdk/cubism/core/CubismDrawables", "getRenderOrders", "()[I"
            )
        pack_path = CORE_MODEL_READ_PACKS[version]
        pack = decode_json(
            pack_path.read_text(encoding="utf-8"),
            str(pack_path.relative_to(ROOT)),
        )
        profile_path = CORE_PROFILES[version]
        profile = decode_json(
            profile_path.read_text(encoding="utf-8"),
            str(profile_path.relative_to(ROOT)),
        )
        require(pack["format"] == "turboism.mapping.pack", f"{version}: bad pack format")
        require(pack["schemaVersion"] == 1, f"{version}: bad pack schema")
        require(pack["status"] == "DRAFT", f"{version}: Core pack is not DRAFT")
        require(
            pack["source"] == "exact-public-classfile-observation",
            f"{version}: Core pack provenance drift",
        )
        require(pack["cubismVersion"] == version, f"{version}: pack version drift")
        require(
            pack["metadata"]["artifactSha256"] == document["artifact"]["sha256"],
            f"{version}: pack artifact digest drift",
        )
        require(
            pack["metadata"]["inventoryRef"]
            == f"cubism-ref/core-api/observed/cubism-core-{version}.json",
            f"{version}: pack inventory reference drift",
        )
        expected_pack_id = f"cubism-{version}-core-model-read"
        require(
            expected_pack_id in profile["mappingPacks"],
            f"{version}: profile does not include the Core model-read pack",
        )
        require(
            profile["status"] == "DRAFT" and profile["capabilities"] == [],
            f"{version}: DRAFT Core facts unexpectedly enable a capability",
        )

        entries = {entry["name"]: entry for entry in pack["entries"]}
        require(
            set(entries) == set(expected_for_version),
            f"{version}: Core selector alias set drift",
        )
        require(
            len(entries)
            == len(pack["entries"])
            == len(expected_for_version),
            f"{version}: duplicate or extra Core selector",
        )

        inventory_classes = classes(document)
        for alias, facts in expected_for_version.items():
            entry = entries[alias]
            verification = entry["x.verification"]
            require(entry["kind"] == facts["kind"], f"{version}: {alias} kind drift")
            require(
                entry["runtime"] == facts["runtime"],
                f"{version}: {alias} runtime name drift",
            )
            require(
                verification["ownerInternalName"] == facts["owner"],
                f"{version}: {alias} owner drift",
            )
            require(
                verification["requiredAccessFlags"] == facts["required"]
                and verification["forbiddenAccessFlags"] == facts["forbidden"],
                f"{version}: {alias} access flags drift",
            )
            require(
                entry["status"] == "DRAFT"
                and entry["stability"] == "experimental"
                and entry["verifiedBy"] == "none"
                and entry["verifiedAt"] is None,
                f"{version}: {alias} crossed the observation boundary",
            )

            class_name = facts["owner"].replace("/", ".")
            require(
                class_name in inventory_classes,
                f"{version}: {alias} owner missing from inventory",
            )
            class_entry = inventory_classes[class_name]
            if facts["kind"] == "class":
                require(
                    "public" in class_entry["declaration"].split(),
                    f"{version}: {alias} owner is not public",
                )
                require(
                    "descriptor" not in entry,
                    f"{version}: {alias} class has a method descriptor",
                )
                continue

            require(
                entry["descriptor"] == facts["descriptor"],
                f"{version}: {alias} descriptor drift",
            )
            candidates = [
                member
                for member in class_entry["members"]
                if member["kind"] == "method"
                and member["name"] == facts["runtime"]
                and member["descriptor"] == facts["descriptor"]
            ]
            require(
                len(candidates) == 1,
                f"{version}: {alias} not backed by one exact inventory method",
            )
            require(
                set(candidates[0]["access"]) == facts["access"],
                f"{version}: {alias} public/static access drift",
            )


def test_canonical_serialization(
    api_52: dict[str, Any],
    api_53: dict[str, Any],
) -> None:
    for path, document in ((API_52, api_52), (API_53, api_53)):
        committed = path.read_text(encoding="utf-8")
        require(
            committed == canonical_json(document),
            f"{path.relative_to(ROOT)} is not canonical deterministic JSON",
        )


def test_strict_negative_mutations(api_52: dict[str, Any]) -> None:
    mutation = copy.deepcopy(api_52)
    mutation["unknown"] = True
    expect_invalid("unknown field", lambda: validate_document(mutation))

    mutation = copy.deepcopy(api_52)
    mutation["authorizesRuntime"] = True
    expect_invalid("must be false", lambda: validate_document(mutation))

    mutation = copy.deepcopy(api_52)
    mutation["summary"]["classCount"] += 1
    expect_invalid("does not match classes", lambda: validate_document(mutation))

    mutation = copy.deepcopy(api_52)
    mutation["classes"] = list(reversed(mutation["classes"]))
    expect_invalid("not sorted", lambda: validate_document(mutation))

    mutation = copy.deepcopy(api_52)
    first_member = mutation["classes"][0]["members"][0]
    first_member["descriptor"] = "(not-a-descriptor)"
    expect_invalid("invalid JVM descriptor", lambda: validate_document(mutation))

    mutation = copy.deepcopy(api_52)
    mutation["classes"][0]["members"].append(
        copy.deepcopy(mutation["classes"][0]["members"][0])
    )
    expect_invalid("duplicate member identity", lambda: validate_document(mutation))

    expect_invalid(
        "duplicate JSON key",
        lambda: decode_json(
            '{"format":"one","format":"two"}',
            "duplicate-fixture",
        ),
    )


def test_generated_reference(
    api_52: dict[str, Any],
    api_53: dict[str, Any],
) -> None:
    generated_once = render_reference([api_52, api_53])
    generated_twice = render_reference([api_53, api_52])
    require(
        generated_once == generated_twice,
        "reference depends on inventory argument ordering",
    )
    require(
        "They do not authorize runtime binding." in generated_once,
        "generated reference lost its evidence boundary",
    )
    require(
        "CubismPartView#getOffscreenIndices" in generated_once
        and "CubismOffscreenRenderingView#getReferenceObjectIndices" in generated_once,
        "generated reference lost descriptor follow-up warnings",
    )


def main() -> int:
    api_52 = load_inventory(API_52)
    api_53 = load_inventory(API_53)
    test_parser_contract()
    test_inventory_facts(api_52, api_53)
    test_exact_descriptor_and_constant_facts(api_52, api_53)
    test_core_model_read_mapping_packs(api_52, api_53)
    test_canonical_serialization(api_52, api_53)
    test_strict_negative_mutations(api_52)
    test_generated_reference(api_52, api_53)
    print(
        "PASS: Cubism Core public API inventory "
        "(5.2=22/158/19, 5.3.02=27/194/43)"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
