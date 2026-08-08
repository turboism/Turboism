"""Illegal PreviewApi placement fixture sources for SDK API tier tests."""
from __future__ import annotations

from sdk_api_tiers_test_fixture_illegal_nested import nested_sources
from sdk_api_tiers_test_fixture_shared import annotation_source


_TARGETS = {
    "parameter": "{java.lang.annotation.ElementType.TYPE, java.lang.annotation.ElementType.METHOD, java.lang.annotation.ElementType.PARAMETER}",
    "type-use": "{java.lang.annotation.ElementType.TYPE, java.lang.annotation.ElementType.METHOD, java.lang.annotation.ElementType.TYPE_USE}",
    "field": "{java.lang.annotation.ElementType.TYPE, java.lang.annotation.ElementType.METHOD, java.lang.annotation.ElementType.FIELD}",
    "record-component": "{java.lang.annotation.ElementType.TYPE, java.lang.annotation.ElementType.METHOD, java.lang.annotation.ElementType.RECORD_COMPONENT}",
    "package": "{java.lang.annotation.ElementType.TYPE, java.lang.annotation.ElementType.METHOD, java.lang.annotation.ElementType.PACKAGE}",
    "constructor": "{java.lang.annotation.ElementType.TYPE, java.lang.annotation.ElementType.METHOD, java.lang.annotation.ElementType.CONSTRUCTOR}",
    "nested-annotation": "{java.lang.annotation.ElementType.TYPE, java.lang.annotation.ElementType.METHOD, java.lang.annotation.ElementType.ANNOTATION_TYPE}",
}


def illegal_sources(current: bool, variant: str) -> dict[str, str]:
    if not current or not variant.startswith("illegal-"):
        return {}
    kind = variant.removeprefix("illegal-")
    result = preview_annotation_source(kind)
    result.update(placement_sources(kind))
    return result


def preview_annotation_source(kind: str) -> dict[str, str]:
    if kind not in _TARGETS:
        return {}
    return {"dev/turboism/sdk/PreviewApi.java": annotation_source(_TARGETS[kind], "CLASS")}


def placement_sources(kind: str) -> dict[str, str]:
    direct = {
        "parameter": parameter_sources,
        "type-use": type_use_sources,
        "field": field_sources,
        "record-component": record_component_sources,
        "package": package_sources,
        "constructor": constructor_sources,
        "private-method": private_method_sources,
        "package-method": package_method_sources,
        "public-method-nonexported-owner": nonexported_method_sources,
        "package-class": package_class_sources,
    }
    return direct[kind]() if kind in direct else nested_sources(kind)


def parameter_sources() -> dict[str, str]:
    return {"dev/turboism/sdk/illegal/IllegalParameter.java": """package dev.turboism.sdk.illegal;
public class IllegalParameter {
    public String value(@dev.turboism.sdk.PreviewApi String value) { return value; }
}
"""}


def type_use_sources() -> dict[str, str]:
    return {"dev/turboism/sdk/illegal/IllegalTypeUse.java": """package dev.turboism.sdk.illegal;
public class IllegalTypeUse {
    public @dev.turboism.sdk.PreviewApi String value() { return \"x\"; }
}
"""}


def field_sources() -> dict[str, str]:
    return {"dev/turboism/sdk/illegal/IllegalField.java": """package dev.turboism.sdk.illegal;
public class IllegalField {
    @dev.turboism.sdk.PreviewApi public String value;
}
"""}


def record_component_sources() -> dict[str, str]:
    return {"dev/turboism/sdk/illegal/IllegalRecord.java": """package dev.turboism.sdk.illegal;
public record IllegalRecord(@dev.turboism.sdk.PreviewApi int value) {}
"""}


def package_sources() -> dict[str, str]:
    return {
        "dev/turboism/sdk/illegalpackage/package-info.java": """@dev.turboism.sdk.PreviewApi
package dev.turboism.sdk.illegalpackage;
""",
        "dev/turboism/sdk/illegalpackage/IllegalPackage.java": """package dev.turboism.sdk.illegalpackage;
public class IllegalPackage {}
""",
    }


def constructor_sources() -> dict[str, str]:
    return {"dev/turboism/sdk/illegal/IllegalConstructor.java": """package dev.turboism.sdk.illegal;
public class IllegalConstructor {
    @dev.turboism.sdk.PreviewApi public IllegalConstructor() {}
}
"""}


def private_method_sources() -> dict[str, str]:
    return {"dev/turboism/sdk/illegal/IllegalPrivateMethod.java": """package dev.turboism.sdk.illegal;
public class IllegalPrivateMethod {
    @dev.turboism.sdk.PreviewApi private String hidden() { return \"x\"; }
}
"""}


def package_method_sources() -> dict[str, str]:
    return {"dev/turboism/sdk/illegal/IllegalPackageMethod.java": """package dev.turboism.sdk.illegal;
public class IllegalPackageMethod {
    @dev.turboism.sdk.PreviewApi String packageVisible() { return \"x\"; }
}
"""}


def nonexported_method_sources() -> dict[str, str]:
    return {"dev/turboism/sdk/illegal/NonExportedMethodOwner.java": """package dev.turboism.sdk.illegal;
class NonExportedMethodOwner {
    @dev.turboism.sdk.PreviewApi public String exposed() { return \"x\"; }
}
"""}


def package_class_sources() -> dict[str, str]:
    return {"dev/turboism/sdk/illegal/PackagePrivateClass.java": """package dev.turboism.sdk.illegal;
@dev.turboism.sdk.PreviewApi
class PackagePrivateClass {}
"""}
