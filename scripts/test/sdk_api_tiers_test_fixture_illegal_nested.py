"""Nested and inaccessible illegal PreviewApi fixture placements."""
from __future__ import annotations


def nested_sources(kind: str) -> dict[str, str]:
    return {
        "private-nested-class": private_nested_class_sources,
        "package-private-outer-public-nested-class": hidden_nested_class_sources,
        "package-private-outer-public-nested-method": hidden_nested_method_sources,
        "private-outer-public-nested-class": private_outer_nested_class_sources,
        "private-outer-public-nested-method": private_outer_nested_method_sources,
        "nested-annotation": nested_annotation_sources,
    }.get(kind, empty_sources)()


def empty_sources() -> dict[str, str]:
    return {}


def private_nested_class_sources() -> dict[str, str]:
    return {"dev/turboism/sdk/illegal/PrivateNestedOwner.java": """package dev.turboism.sdk.illegal;
public class PrivateNestedOwner {
    @dev.turboism.sdk.PreviewApi
    private static class PrivateNested {}
}
"""}


def hidden_nested_class_sources() -> dict[str, str]:
    return {"dev/turboism/sdk/illegal/HiddenOwner.java": """package dev.turboism.sdk.illegal;
class HiddenOwner {
    @dev.turboism.sdk.PreviewApi
    public static class PublicNested {}
}
"""}


def hidden_nested_method_sources() -> dict[str, str]:
    return {"dev/turboism/sdk/illegal/HiddenOwner.java": """package dev.turboism.sdk.illegal;
class HiddenOwner {
    public static class PublicNested {
        @dev.turboism.sdk.PreviewApi
        public String exposed() { return \"x\"; }
    }
}
"""}


def private_outer_nested_class_sources() -> dict[str, str]:
    return {"dev/turboism/sdk/illegal/PrivateOuterContainer.java": """package dev.turboism.sdk.illegal;
public class PrivateOuterContainer {
    private static class PrivateOuter {
        @dev.turboism.sdk.PreviewApi
        public static class PublicNested {}
    }
}
"""}


def private_outer_nested_method_sources() -> dict[str, str]:
    return {"dev/turboism/sdk/illegal/PrivateOuterContainer.java": """package dev.turboism.sdk.illegal;
public class PrivateOuterContainer {
    private static class PrivateOuter {
        public static class PublicNested {
            @dev.turboism.sdk.PreviewApi
            public String exposed() { return \"x\"; }
        }
    }
}
"""}


def nested_annotation_sources() -> dict[str, str]:
    return {
        "dev/turboism/sdk/illegal/Wrapper.java": """package dev.turboism.sdk.illegal;
@java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.CLASS)
@java.lang.annotation.Target(java.lang.annotation.ElementType.TYPE)
public @interface Wrapper {
    dev.turboism.sdk.PreviewApi value();
}
""",
        "dev/turboism/sdk/illegal/NestedAnnotationUse.java": """package dev.turboism.sdk.illegal;
@Wrapper(@dev.turboism.sdk.PreviewApi)
public class NestedAnnotationUse {}
""",
    }
