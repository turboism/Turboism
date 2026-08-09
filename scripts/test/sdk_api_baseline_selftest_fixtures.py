"""Java fixture construction for the SDK API baseline shell selftest."""
from __future__ import annotations

import subprocess
from pathlib import Path


def compile_fixture(root: Path, variant: str) -> None:
    fixture = root / variant
    source = fixture / "src"
    classes = fixture / "classes"
    for relative, content in sources(variant).items():
        write_text(source, relative, content)
    classes.mkdir(parents=True, exist_ok=True)
    compile_sources(source, classes)
    create_archive(fixture, classes)


def write_text(root: Path, relative: str, content: str) -> None:
    destination = root / relative
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_text(content, encoding="utf-8")


def compile_sources(source: Path, classes: Path) -> None:
    java_sources = sorted(str(path) for path in source.rglob("*.java"))
    subprocess.run(["javac", "--release", "17", "-parameters", "-d", str(classes), *java_sources], check=True)


def create_archive(fixture: Path, classes: Path) -> None:
    subprocess.run(["jar", "--create", "--file", str(fixture / "sdk.jar"), "-C", str(classes), "."], check=True)


def sources(variant: str) -> dict[str, str]:
    result = base_sources()
    result["sample/api/Service.java"] = service_source(variant)
    result["sample/api/Marker.java"] = marker_source(variant)
    result.update(variant_sources(variant))
    return result


def base_sources() -> dict[str, str]:
    return {
        "sample/api/Level.java": "package sample.api;\npublic enum Level { LOW, HIGH }\n",
        "sample/api/Nested.java": "package sample.api;\npublic @interface Nested { String name(); }\n",
        "sample/api/package-info.java": "@sample.api.Marker\npackage sample.api;\n",
        "sample/api/Point.java": "package sample.api;\npublic record Point(int x, int y) {}\n",
        "sample/api/Shape.java": "package sample.api;\npublic sealed interface Shape permits Circle {}\n",
        "sample/api/Circle.java": "package sample.api;\npublic final class Circle implements Shape {}\n",
    }


def marker_source(variant: str) -> str:
    number = "8" if variant == "changed-default" else "7"
    return f'''package sample.api;
public @interface Marker {{
    int number() default {number};
    String text() default "A😀";
    Class<?> type() default String.class;
    Level level() default Level.LOW;
    Nested nested() default @Nested(name = "inside");
    int[] values() default {{1, 2}};
}}
'''


def service_source(variant: str) -> str:
    fields = service_fields(variant)
    method = service_method(variant)
    return f'''package sample.api;
import java.io.IOException;
@Marker
public interface Service<T extends Number> {{
{fields}
    {method}
}}
'''


def service_fields(variant: str) -> str:
    if variant == "reordered-fields":
        return "    int COUNT = 3;\n    String NAME = \"stable\";"
    name = "changed" if variant == "changed-constant" else "stable"
    return f"    String NAME = \"{name}\";\n    int COUNT = 3;"


def service_method(variant: str) -> str:
    if variant == "additive":
        return "T read(String key) throws IOException;\n    default boolean available() { return true; }"
    if variant == "changed-descriptor":
        return "T read(CharSequence key) throws IOException;"
    if variant == "forbidden":
        return "com.live2d.privateapi.Host read(String key) throws IOException;"
    return "T read(String key) throws IOException;"


def variant_sources(variant: str) -> dict[str, str]:
    if variant == "additive":
        return {"sample/api/Extra.java": "package sample.api;\npublic final class Extra {}\n"}
    if variant == "forbidden":
        return {"com/live2d/privateapi/Host.java": "package com.live2d.privateapi;\npublic final class Host {}\n"}
    return {}
