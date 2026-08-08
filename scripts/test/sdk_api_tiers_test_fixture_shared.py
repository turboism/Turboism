"""Shared annotation and stable fixture sources for SDK API tier tests."""
from __future__ import annotations


def shared_sources(current: bool, variant: str) -> dict[str, str]:
    result = {
        "dev/turboism/sdk/cubism/CubismFacade.java": facade_source(current),
        "dev/turboism/sdk/stable/StableService.java": stable_source(current, variant),
        "dev/turboism/sdk/PackageMarker.java": package_marker_source(),
    }
    if current:
        result["dev/turboism/sdk/PreviewApi.java"] = preview_api_source(variant)
    return result


def annotation_source(targets: str, retention: str, member: str = "") -> str:
    return f"""package dev.turboism.sdk;
@java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.{retention})
@java.lang.annotation.Target({targets})
public @interface PreviewApi {{
{member}}}
"""


def preview_api_source(variant: str) -> str:
    retention = "RUNTIME" if variant == "preview-api-retention-change" else "CLASS"
    member = "    String reason() default \"\";\n" if variant == "preview-api-shape-change" else ""
    return annotation_source(preview_targets(variant), retention, member)


def preview_targets(variant: str) -> str:
    if variant == "preview-api-target-change":
        return "{java.lang.annotation.ElementType.TYPE, java.lang.annotation.ElementType.METHOD, java.lang.annotation.ElementType.PARAMETER}"
    return "{java.lang.annotation.ElementType.TYPE, java.lang.annotation.ElementType.METHOD}"


def facade_source(current: bool) -> str:
    marker = "@dev.turboism.sdk.PreviewApi\n" if current else ""
    return f"""package dev.turboism.sdk.cubism;
public class CubismFacade {{
    {marker}public dev.turboism.sdk.cubism.transaction.TransactionManager transactionManager() {{
        return null;
    }}
    public String stableFacade() {{ return \"stable\"; }}
}}
"""


def stable_source(current: bool, variant: str) -> str:
    marker = "@dev.turboism.sdk.PreviewApi\n" if current and variant == "historical-stable-marker-change" else ""
    changed = current and variant in {"historical-stable-marker-change", "stable-change"}
    return stable_source_text(marker, changed)


def stable_source_text(marker: str, changed: bool) -> str:
    return f"""package dev.turboism.sdk.stable;
{marker}public class StableService {{
    public {'int' if changed else 'String'} stable() {{ return {'1' if changed else '"stable"'}; }}
}}
"""


def package_marker_source() -> str:
    return """package dev.turboism.sdk;
@java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.CLASS)
@java.lang.annotation.Target(java.lang.annotation.ElementType.TYPE)
public @interface PackageMarker {}
"""
