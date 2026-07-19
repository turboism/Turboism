#!/usr/bin/env python3
"""Mutation tests for the B1 production source and entrypoint boundary scanner."""
from __future__ import annotations

import json
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path
from typing import Callable

ROOT = Path(__file__).resolve().parents[2]
SCANNER = ROOT / "scripts/test/check_legacy_plugin_b1_source_boundaries.py"
PLUGIN = "example"
PACKAGE = "dev.turboism.plugin.example"
ENTRYPOINT = f"{PACKAGE}.ExamplePlugin"
ENTRYPOINT_RELATIVE = Path(
    "plugins/example/src/main/java/dev/turboism/plugin/example/ExamplePlugin.java"
)
MANIFEST_RELATIVE = Path("plugins/example/src/main/resources/META-INF/turboism/plugin.json")
B1_DOMAIN = Path("plugins/example/src/main/java/dev/turboism/plugin/example/b1/domain")
B1_APPLICATION = Path("plugins/example/src/main/java/dev/turboism/plugin/example/b1/application")


class AssertionFailure(RuntimeError):
    pass


def write(path: Path, content: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding="utf-8")


def baseline_entrypoint() -> str:
    return """package dev.turboism.plugin.example;

import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.sdk.plugin.TurboismPlugin;

public final class ExamplePlugin implements TurboismPlugin {

    private PluginContext context;
    private boolean enabled;

    @Override
    public void init(final PluginContext context) {
        this.context = context;
    }

    @Override
    public void enable() {
        enabled = true;
    }

    @Override
    public void disable() {
        enabled = false;
    }

    @Override
    public void shutdown() {
        enabled = false;
        context = null;
    }
}
"""


def manifest() -> str:
    return json.dumps(
        {
            "format": "turboism.plugin.meta",
            "schemaVersion": 1,
            "id": "dev.turboism.plugin.example",
            "name": "Example",
            "version": "0.1.0",
            "description": "scanner fixture",
            "entrypoints": {"plugin": ENTRYPOINT},
            "turboismApi": "[0.1.0,0.2.0)",
            "authors": [{"name": "Turboism Contributors"}],
            "license": "Project License",
            "homepage": "https://turboism.dev",
            "dependencies": [],
            "permissions": [],
            "capabilities": [],
            "environment": {"requiresCubism": False, "ui": "none"},
        },
        indent=2,
    ) + "\n"


def initialize_repository(directory: Path) -> str:
    write(directory / MANIFEST_RELATIVE, manifest())
    write(directory / ENTRYPOINT_RELATIVE, baseline_entrypoint())
    run_git(directory, "init", "--quiet")
    run_git(directory, "config", "user.email", "b1-scanner@example.invalid")
    run_git(directory, "config", "user.name", "B1 Scanner Test")
    run_git(directory, "add", ".")
    run_git(directory, "commit", "--quiet", "-m", "baseline")
    return run_git(directory, "rev-parse", "HEAD").strip()


def run_git(directory: Path, *arguments: str) -> str:
    result = subprocess.run(
        ["git", *arguments],
        cwd=directory,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        check=False,
    )
    if result.returncode != 0:
        raise AssertionFailure(f"git {' '.join(arguments)} failed:\n{result.stdout}")
    return result.stdout


def run_scanner(directory: Path, baseline: str) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        [
            sys.executable,
            str(SCANNER),
            "--root",
            str(directory),
            "--baseline",
            baseline,
        ],
        cwd=ROOT,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        check=False,
    )


def b1_source(package: str, body: str, imports: tuple[str, ...] = ()) -> str:
    rendered_imports = "".join(f"import {item};\n" for item in imports)
    gap = "\n" if rendered_imports else ""
    return f"package {package};\n\n{rendered_imports}{gap}public final class Fixture {{\n{body}\n}}\n"


def write_domain(directory: Path, name: str, body: str, imports: tuple[str, ...] = ()) -> None:
    write(
        directory / B1_DOMAIN / name,
        b1_source(f"{PACKAGE}.b1.domain", body, imports),
    )


def assert_accepts(name: str, mutate: Callable[[Path], None]) -> None:
    with tempfile.TemporaryDirectory(prefix="turboism-b1-source-accept-") as temp:
        directory = Path(temp)
        baseline = initialize_repository(directory)
        mutate(directory)
        result = run_scanner(directory, baseline)
        if result.returncode != 0:
            raise AssertionFailure(f"accepted fixture rejected: {name}\n{result.stdout}")


def assert_rejects(name: str, mutate: Callable[[Path], None]) -> None:
    with tempfile.TemporaryDirectory(prefix="turboism-b1-source-reject-") as temp:
        directory = Path(temp)
        baseline = initialize_repository(directory)
        mutate(directory)
        result = run_scanner(directory, baseline)
        if result.returncode == 0:
            raise AssertionFailure(f"unsafe mutation was accepted: {name}\n{result.stdout}")


def add_empty_b1(directory: Path) -> None:
    write_domain(directory, "Empty.java", "")


def add_valid_domain_and_config_application(directory: Path) -> None:
    write_domain(
        directory,
        "Policy.java",
        "    public String normalize(final String value) {\n"
        "        return value.trim();\n"
        "    }",
        ("java.util.Objects",),
    )
    write(
        directory / B1_APPLICATION / "ConfigApplication.java",
        b1_source(
            f"{PACKAGE}.b1.application",
            "    private PluginConfigRegistry registry;\n\n"
            "    public CompletionStage<Void> bind(final PluginConfigRegistry value, final CompletionStage<Void> stage) {\n"
            "        registry = Objects.requireNonNull(value, \"value\");\n"
            "        return stage.thenRun(() -> { });\n"
            "    }",
            (
                "dev.turboism.sdk.config.PluginConfigRegistry",
                "java.util.Objects",
                "java.util.concurrent.CompletionStage",
                f"{PACKAGE}.b1.domain.Policy",
            ),
        ),
    )


def add_wrong_path(directory: Path) -> None:
    write(
        directory / "plugins/example/src/main/java/dev/turboism/plugin/example/b1/unsafe/Fixture.java",
        b1_source(f"{PACKAGE}.b1.domain", ""),
    )


def add_wrong_package(directory: Path) -> None:
    write(
        directory / B1_DOMAIN / "Fixture.java",
        b1_source(f"{PACKAGE}.b1.unsafe", ""),
    )


def add_import(item: str) -> Callable[[Path], None]:
    return lambda directory: write_domain(directory, "Fixture.java", "", (item,))


def add_forbidden_statement(statement: str) -> Callable[[Path], None]:
    return lambda directory: write_domain(directory, "Fixture.java", f"    {statement}")


def delete_baseline_line(directory: Path) -> None:
    path = directory / ENTRYPOINT_RELATIVE
    path.write_text(
        path.read_text(encoding="utf-8").replace("        enabled = true;\n", ""),
        encoding="utf-8",
    )


def modify_baseline_line(directory: Path) -> None:
    path = directory / ENTRYPOINT_RELATIVE
    path.write_text(
        path.read_text(encoding="utf-8").replace("        enabled = true;", "        enabled = false;"),
        encoding="utf-8",
    )


def insert_after(text: str, marker: str, addition: str) -> str:
    if marker not in text:
        raise AssertionFailure(f"fixture marker missing: {marker!r}")
    return text.replace(marker, marker + addition, 1)


def add_entrypoint_ui_host_call(directory: Path) -> None:
    path = directory / ENTRYPOINT_RELATIVE
    path.write_text(
        insert_after(path.read_text(encoding="utf-8"), "        enabled = true;\n", "        context.uiHost();\n"),
        encoding="utf-8",
    )


def add_entrypoint_cubism_call(directory: Path) -> None:
    path = directory / ENTRYPOINT_RELATIVE
    path.write_text(
        insert_after(path.read_text(encoding="utf-8"), "        enabled = true;\n", "        context.cubism();\n"),
        encoding="utf-8",
    )


def add_entrypoint_host_constructor_argument(directory: Path) -> None:
    add_valid_entrypoint_wiring(directory)
    path = directory / ENTRYPOINT_RELATIVE
    path.write_text(
        path.read_text(encoding="utf-8").replace(
            "new ExampleApplication();",
            "new ExampleApplication(context.uiHost());",
        ),
        encoding="utf-8",
    )


def add_entrypoint_host_null_guard(directory: Path) -> None:
    path = directory / ENTRYPOINT_RELATIVE
    path.write_text(
        insert_after(
            path.read_text(encoding="utf-8"),
            "        this.context = context;\n",
            "        Objects.requireNonNull(context.uiHost());\n",
        ),
        encoding="utf-8",
    )


def add_valid_entrypoint_wiring(directory: Path) -> None:
    write(
        directory / B1_APPLICATION / "ExampleApplication.java",
        b1_source(
            f"{PACKAGE}.b1.application",
            "    public void init(final PluginConfigRegistry config) { }\n\n"
            "    public void enable() { }\n\n"
            "    public void disable() { }\n\n"
            "    public void shutdown() { }",
            ("dev.turboism.sdk.config.PluginConfigRegistry",),
        ),
    )
    path = directory / ENTRYPOINT_RELATIVE
    text = path.read_text(encoding="utf-8")
    text = insert_after(
        text,
        "import dev.turboism.sdk.plugin.TurboismPlugin;\n",
        "import dev.turboism.plugin.example.b1.application.ExampleApplication;\n",
    )
    text = insert_after(
        text,
        "    private PluginContext context;\n",
        "    private ExampleApplication b1Application = new ExampleApplication();\n",
    )
    text = insert_after(
        text,
        "        this.context = context;\n",
        "        b1Application.init(context.config());\n",
    )
    text = insert_after(text, "        enabled = true;\n", "        b1Application.enable();\n")
    text = insert_after(text, "        enabled = false;\n", "        b1Application.disable();\n")
    shutdown_marker = "        enabled = false;\n        context = null;\n"
    if shutdown_marker not in text:
        raise AssertionFailure("shutdown fixture marker missing")
    text = text.replace(
        shutdown_marker,
        "        enabled = false;\n        b1Application.shutdown();\n        context = null;\n",
        1,
    )
    path.write_text(text, encoding="utf-8")


def main() -> None:
    accepts = [
        ("legal empty B1 domain", add_empty_b1),
        ("legal domain and config application", add_valid_domain_and_config_application),
        ("legal own B1 application entrypoint wiring", add_valid_entrypoint_wiring),
    ]
    rejects = [
        ("wrong B1 source path", add_wrong_path),
        ("wrong B1 package", add_wrong_package),
        ("other plugin import", add_import("dev.turboism.plugin.other.Helper")),
        ("runtime import", add_import("dev.turboism.core.PluginManager")),
        ("SDK Cubism import", add_import("dev.turboism.sdk.cubism.CubismFacade")),
        ("File import", add_import("java.io.File")),
        ("Files import", add_import("java.nio.file.Files")),
        ("Path import", add_import("java.nio.file.Path")),
        ("network import", add_import("java.net.URI")),
        ("AWT import", add_import("java.awt.Desktop")),
        ("Swing import", add_import("javax.swing.JFrame")),
        ("reflection import", add_import("java.lang.reflect.Method")),
        ("direct Files call", add_forbidden_statement("Files.exists(null);")),
        ("direct network call", add_forbidden_statement("URI.create(\"https://example.invalid\");")),
        ("ProcessBuilder use", add_forbidden_statement("ProcessBuilder builder = null;")),
        ("Thread use", add_forbidden_statement("Thread worker = null;")),
        ("Executor use", add_forbidden_statement("Executor executor = null;")),
        ("Timer use", add_forbidden_statement("Timer timer = null;")),
        ("CompletableFuture use", add_forbidden_statement("CompletableFuture<Object> future = null;")),
        ("toCompletableFuture call", add_forbidden_statement("stage.toCompletableFuture();")),
        ("Future get call", add_forbidden_statement("CompletionStage<Object> future = null; future.get();")),
        ("Future join call", add_forbidden_statement("CompletionStage<Object> stage = null; stage.join();")),
        ("entrypoint deletes baseline behavior", delete_baseline_line),
        ("entrypoint modifies baseline behavior", modify_baseline_line),
        ("entrypoint adds UI host call", add_entrypoint_ui_host_call),
        ("entrypoint adds Cubism call", add_entrypoint_cubism_call),
        ("entrypoint passes host UI into B1 construction", add_entrypoint_host_constructor_argument),
        ("entrypoint uses host UI in a null guard", add_entrypoint_host_null_guard),
    ]
    for name, mutate in accepts:
        assert_accepts(name, mutate)
    for name, mutate in rejects:
        assert_rejects(name, mutate)
    print(f"PASS: B1 source boundary mutation suite ({len(accepts)} accepted, {len(rejects)} rejected)")


if __name__ == "__main__":
    try:
        main()
    except AssertionFailure as error:
        print(f"FAIL: {error}", file=sys.stderr)
        raise SystemExit(1)
