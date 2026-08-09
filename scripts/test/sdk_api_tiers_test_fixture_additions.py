"""Current-only fixture additions for SDK API tier tests."""
from __future__ import annotations


def addition_sources(current: bool, variant: str) -> dict[str, str]:
    if not current:
        return {}
    result = stable_addition_source(variant)
    result.update(new_preview_sources(variant))
    result.update(package_promotion_sources(variant))
    return result


def stable_addition_source(variant: str) -> dict[str, str]:
    if variant not in {"add-current-stable", "add-current-stable-change", "add-current-preview"}:
        return {}
    changed = variant == "add-current-stable-change"
    marker = "@dev.turboism.sdk.PreviewApi\n" if variant == "add-current-preview" else ""
    return {"dev/turboism/sdk/stable/CurrentStable.java": current_stable_source(marker, changed)}


def current_stable_source(marker: str, changed: bool) -> str:
    return f"""package dev.turboism.sdk.stable;
{marker}public class CurrentStable {{
    public {'int' if changed else 'String'} value() {{ return {'1' if changed else '"current"'}; }}
}}
"""


def new_preview_sources(variant: str) -> dict[str, str]:
    result = new_preview_type_source(variant)
    result.update(new_preview_method_source(variant))
    return result


def new_preview_type_source(variant: str) -> dict[str, str]:
    variants = {"new-preview", "new-preview-unmarked", "new-preview-change", "new-preview-promotion-change"}
    if variant not in variants:
        return {}
    marker = "" if variant in {"new-preview-unmarked", "new-preview-promotion-change"} else "@dev.turboism.sdk.PreviewApi\n"
    extra = "\n    public int added() { return 1; }" if variant in {"new-preview-change", "new-preview-promotion-change"} else ""
    return {"dev/turboism/sdk/newpreview/NewThing.java": new_thing_source(marker, extra)}


def new_thing_source(marker: str, extra: str) -> str:
    return f"""package dev.turboism.sdk.newpreview;
{marker}public class NewThing {{
    public String value() {{ return \"new\"; }}{extra}
}}
"""


def new_preview_method_source(variant: str) -> dict[str, str]:
    if variant not in {"new-preview-method", "new-preview-method-unmarked"}:
        return {}
    marker = "" if variant == "new-preview-method-unmarked" else "@dev.turboism.sdk.PreviewApi\n"
    return {"dev/turboism/sdk/newpreview/MethodOwner.java": method_owner_source(marker)}


def method_owner_source(marker: str) -> str:
    return f"""package dev.turboism.sdk.newpreview;
public class MethodOwner {{
    {marker}public String previewMethod() {{ return \"new\"; }}
    public String stableMethod() {{ return \"stable\"; }}
}}
"""


def package_promotion_sources(variant: str) -> dict[str, str]:
    if variant == "historical-package-promotion-annotation":
        return {"dev/turboism/sdk/cubism/write/package-info.java": """@Deprecated
package dev.turboism.sdk.cubism.write;
"""}
    variants = {"historical-package-promotion-with-new-preview-admitted", "historical-package-promotion-with-new-preview"}
    if variant not in variants:
        return {}
    marker = "@dev.turboism.sdk.PreviewApi\n" if variant.endswith("-admitted") else ""
    return {"dev/turboism/sdk/cubism/write/NewWriteThing.java": new_write_thing_source(marker)}


def new_write_thing_source(marker: str) -> str:
    return f"""package dev.turboism.sdk.cubism.write;
{marker}public class NewWriteThing {{
    public String value() {{ return \"new\"; }}
}}
"""
