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
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class DumpCubismEditorAvailability {
    private static final List<String> REVIEWED = List.of("5.2.03", "5.3.02");
    private static final Set<String> REVIEWED_SET = Set.copyOf(REVIEWED);

    public static void main(String[] args) throws Exception {
        List<Class<?>> types = List.of(
            Class.forName("dev.turboism.sdk.cubism.model.Part"),
            Class.forName("dev.turboism.sdk.cubism.model.Drawable"),
            Class.forName("dev.turboism.sdk.cubism.model.ModelTextures"),
            Class.forName("dev.turboism.sdk.cubism.model.AlphaComposition")
        );
        List<String> rows = new ArrayList<>();
        for (Class<?> type : types) {
            List<CubismEditor> typeDeclarations = new ArrayList<>();
            collectTypeAvailability(type, typeDeclarations, new LinkedHashSet<>());
            if (typeDeclarations.isEmpty()) continue;
            if (type.isEnum()) {
                rows.add(json(type.getName(), "type", resolve(typeDeclarations, type.getName())));
                continue;
            }
            Method[] methods = type.getDeclaredMethods();
            Arrays.sort(methods, Comparator.comparing(DumpCubismEditorAvailability::methodId));
            for (Method method : methods) {
                if (!Modifier.isPublic(method.getModifiers())) continue;
                List<CubismEditor> declarations = new ArrayList<>(typeDeclarations);
                CubismEditor methodAvailability = method.getAnnotation(CubismEditor.class);
                if (methodAvailability != null) declarations.add(methodAvailability);
                rows.add(json(
                    type.getName(),
                    methodId(method),
                    resolve(declarations, type.getName() + "#" + methodId(method))
                ));
            }
        }
        System.out.println("[" + String.join(",", rows) + "]");
    }

    private static void collectTypeAvailability(
        Class<?> type,
        List<CubismEditor> declarations,
        Set<Class<?>> visited
    ) {
        if (!visited.add(type)) return;
        for (Class<?> parent : type.getInterfaces()) {
            collectTypeAvailability(parent, declarations, visited);
        }
        CubismEditor direct = type.getAnnotation(CubismEditor.class);
        if (direct != null) declarations.add(direct);
    }

    private static List<String> resolve(List<CubismEditor> declarations, String apiId) {
        LinkedHashSet<String> supported = new LinkedHashSet<>(REVIEWED);
        for (CubismEditor declaration : declarations) {
            supported.retainAll(expand(declaration, apiId));
        }
        return REVIEWED.stream().filter(supported::contains).toList();
    }

    private static Set<String> expand(CubismEditor declaration, String apiId) {
        List<String> exact = Arrays.asList(declaration.value());
        List<String> excluded = Arrays.asList(declaration.exclude());
        String from = declaration.from();
        String to = declaration.to();
        boolean hasRange = !from.isEmpty() || !to.isEmpty();
        if ((!exact.isEmpty() && hasRange)
            || hasDuplicates(exact)
            || hasDuplicates(excluded)
            || exact.stream().anyMatch(version -> !REVIEWED_SET.contains(version))
            || exact.stream().anyMatch(version -> !isExactVersion(version))
            || excluded.stream().anyMatch(version -> !isExactVersion(version))
            || (!from.isEmpty() && !isExactVersion(from))
            || (!to.isEmpty() && !isExactVersion(to))
            || (!from.isEmpty() && !to.isEmpty() && compareVersions(from, to) > 0)) {
            throw new IllegalStateException("Invalid @CubismEditor declaration on " + apiId);
        }
        LinkedHashSet<String> expanded = exact.isEmpty()
            ? new LinkedHashSet<>(REVIEWED)
            : new LinkedHashSet<>(exact);
        if (hasRange) {
            expanded.removeIf(version -> (!from.isEmpty() && compareVersions(version, from) < 0)
                || (!to.isEmpty() && compareVersions(version, to) > 0));
        }
        expanded.removeAll(excluded);
        return Set.copyOf(expanded);
    }

    private static boolean hasDuplicates(List<String> versions) {
        return new LinkedHashSet<>(versions).size() != versions.size();
    }

    private static boolean isExactVersion(String version) {
        if (version == null || version.isEmpty()) return false;
        String[] components = version.split("\\.", -1);
        if (components.length != 3) return false;
        for (String component : components) {
            if (component.isEmpty()) return false;
            for (int index = 0; index < component.length(); index++) {
                if (!Character.isDigit(component.charAt(index))) return false;
            }
        }
        return true;
    }

    private static int compareVersions(String left, String right) {
        String[] leftComponents = left.split("\\.", -1);
        String[] rightComponents = right.split("\\.", -1);
        for (int index = 0; index < 3; index++) {
            int compared = new BigInteger(leftComponents[index]).compareTo(
                new BigInteger(rightComponents[index])
            );
            if (compared != 0) return compared;
        }
        return 0;
    }

    private static String methodId(Method method) {
        String params = Arrays.stream(method.getParameterTypes())
            .map(Class::getTypeName)
            .reduce((left, right) -> left + "," + right)
            .orElse("");
        return method.getName() + "(" + params + ")";
    }

    private static String json(String owner, String member, List<String> versions) {
        String encodedVersions = versions.isEmpty()
            ? ""
            : "\"" + String.join("\",\"", versions) + "\"";
        return "{\"owner\":\"" + owner + "\",\"member\":\"" + member
            + "\",\"versions\":[" + encodedVersions + "]}";
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
        if len(versions) != len(set(versions)):
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
        "Ranges and exclusions are expanded only across exact reviewed Cubism Editor versions.",
        "",
    ]
    for owner in sorted(grouped):
        lines.extend((f"## `{owner}`", "", "| Member | Supported exact versions |", "|---|---|"))
        for row in grouped[owner]:
            member = html.escape(str(row["member"]))
            versions = ", ".join(f"`{version}`" for version in row["versions"])
            if not versions:
                versions = "_none_"
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
