#!/usr/bin/env python3
"""Generate the SDK Cubism Editor availability guide from compiled annotations."""

from __future__ import annotations

import argparse
import html
import json
import subprocess
import tempfile
from pathlib import Path

REVIEWED = ("5.2.03", "5.3.02")
HELPER = r'''
import dev.turboism.sdk.CubismEditor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

public final class DumpCubismEditorAvailability {
    public static void main(String[] args) throws Exception {
        List<Class<?>> types = List.of(
            Class.forName("dev.turboism.sdk.cubism.model.Part"),
            Class.forName("dev.turboism.sdk.cubism.model.Drawable"),
            Class.forName("dev.turboism.sdk.cubism.model.ModelTextures"),
            Class.forName("dev.turboism.sdk.cubism.model.AlphaComposition")
        );
        List<String> rows = new ArrayList<>();
        for (Class<?> type : types) {
            CubismEditor typeAvailability = type.getAnnotation(CubismEditor.class);
            if (typeAvailability == null) continue;
            if (type.isEnum()) {
                rows.add(json(type.getName(), "type", Arrays.asList(typeAvailability.value())));
                continue;
            }
            Method[] methods = type.getDeclaredMethods();
            Arrays.sort(methods, Comparator.comparing(DumpCubismEditorAvailability::methodId));
            for (Method method : methods) {
                if (!Modifier.isPublic(method.getModifiers())) continue;
                CubismEditor methodAvailability = method.getAnnotation(CubismEditor.class);
                String[] versions = methodAvailability == null
                    ? typeAvailability.value()
                    : methodAvailability.value();
                rows.add(json(type.getName(), methodId(method), Arrays.asList(versions)));
            }
        }
        System.out.println("[" + String.join(",", rows) + "]");
    }

    private static String methodId(Method method) {
        String params = Arrays.stream(method.getParameterTypes())
            .map(Class::getTypeName)
            .reduce((left, right) -> left + "," + right)
            .orElse("");
        return method.getName() + "(" + params + ")";
    }

    private static String json(String owner, String member, List<String> versions) {
        return "{\"owner\":\"" + owner + "\",\"member\":\"" + member
            + "\",\"versions\":[\"" + String.join("\",\"", versions) + "\"]}";
    }
}
'''


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--sdk-jar", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--check", action="store_true")
    return parser.parse_args()


def matrix(sdk_jar: Path) -> list[dict[str, object]]:
    with tempfile.TemporaryDirectory(prefix="turboism-cubism-docs-") as directory:
        root = Path(directory)
        source = root / "DumpCubismEditorAvailability.java"
        source.write_text(HELPER, encoding="utf-8")
        subprocess.run(
            ["javac", "-cp", str(sdk_jar), "-d", str(root), str(source)],
            check=True,
        )
        completed = subprocess.run(
            ["java", "-cp", f"{root}:{sdk_jar}", "DumpCubismEditorAvailability"],
            check=True,
            capture_output=True,
            text=True,
        )
    rows = json.loads(completed.stdout)
    validate(rows)
    return rows


def validate(rows: list[dict[str, object]]) -> None:
    if not rows:
        raise SystemExit("no @CubismEditor availability declarations found")
    for row in rows:
        versions = row["versions"]
        if not versions or len(versions) != len(set(versions)):
            raise SystemExit(f"invalid availability set: {row}")
        if any(version not in REVIEWED for version in versions):
            raise SystemExit(f"unreviewed Cubism version in availability metadata: {row}")


def render(rows: list[dict[str, object]]) -> str:
    grouped: dict[str, list[dict[str, object]]] = {}
    for row in rows:
        grouped.setdefault(str(row["owner"]), []).append(row)
    lines = [
        "# Cubism Editor API availability",
        "",
        "This file is generated from runtime-visible `@CubismEditor` metadata in the compiled SDK. ",
        "Versions are exact reviewed Cubism Editor versions; ranges are not accepted.",
        "",
    ]
    for owner in sorted(grouped):
        lines.extend((f"## `{owner}`", "", "| Member | Supported exact versions |", "|---|---|"))
        for row in grouped[owner]:
            member = html.escape(str(row["member"]))
            versions = ", ".join(f"`{version}`" for version in row["versions"])
            lines.append(f"| `{member}` | {versions} |")
        lines.append("")
    return "\n".join(lines)


def main() -> None:
    args = parse_args()
    generated = render(matrix(args.sdk_jar.resolve()))
    output = args.output.resolve()
    if args.check:
        if not output.is_file() or output.read_text(encoding="utf-8") != generated:
            raise SystemExit(
                f"Cubism Editor API availability documentation is stale: {output}"
            )
        print(
            "PASS: Cubism Editor API availability documentation matches "
            f"{len(generated.splitlines())} generated lines"
        )
        return
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(generated, encoding="utf-8")
    print(output)


if __name__ == "__main__":
    main()
