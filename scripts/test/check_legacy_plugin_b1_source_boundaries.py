#!/usr/bin/env python3
"""Fail-closed production-source and entrypoint delta scanner for Wave B1."""
from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable

DEFAULT_BASELINE = "73ead840ecdb2eb1280c51c355ad2eade787ac24"
JAVA_ROOT = Path("src/main/java")
MANIFEST_PATH = Path("src/main/resources/META-INF/turboism/plugin.json")
PACKAGE_DECLARATION = re.compile(r"^\s*package\s+([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*)\s*;")
IMPORT_DECLARATION = re.compile(r"^\s*import\s+(?:static\s+)?([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*(?:\.\*)?)\s*;")
TYPE_TOKEN = re.compile(r"\b[A-Za-z_$][\w$]*\b")

ALLOWED_B1_SDK_IMPORT = "dev.turboism.sdk.config"
FORBIDDEN_B1_IMPORT_PREFIXES = (
    "dev.turboism.core",
    "dev.turboism.hook",
    "dev.turboism.mapping",
    "dev.turboism.adapter",
    "dev.turboism.distribution",
    "dev.turboism.preview",
    "dev.turboism.sdk.cubism",
    "dev.turboism.sdk.ui",
    "dev.turboism.sdk.action",
    "dev.turboism.sdk.menu",
    "dev.turboism.sdk.event",
    "dev.turboism.sdk.storage",
    "dev.turboism.sdk.task",
    "dev.turboism.sdk.hostread",
    "dev.turboism.sdk.userfile",
    "com.live2d",
    "tool.agent",
    "tool.plugin",
)
FORBIDDEN_JDK_IMPORT_PREFIXES = (
    "java.nio.file",
    "java.net",
    "java.awt",
    "javax.swing",
    "java.lang.reflect",
    "java.lang.invoke",
    "java.util.concurrent.Executor",
    "java.util.concurrent.Executors",
    "java.util.concurrent.CompletableFuture",
    "java.util.concurrent.Future",
    "java.util.Timer",
)
FORBIDDEN_JDK_IMPORT_EXACT = frozenset(
    {
        "java.io.File",
        "java.io.FileDescriptor",
        "java.io.FileInputStream",
        "java.io.FileOutputStream",
        "java.io.FilePermission",
        "java.io.FileReader",
        "java.io.FileWriter",
        "java.io.RandomAccessFile",
    }
)
FORBIDDEN_QUALIFIED_PREFIXES = FORBIDDEN_B1_IMPORT_PREFIXES + (
    "java.nio.file",
    "java.net",
    "java.awt",
    "javax.swing",
    "java.lang.reflect",
    "java.lang.invoke",
    "java.lang.ProcessHandle",
    "java.util.concurrent.Executor",
    "java.util.concurrent.Executors",
    "java.util.concurrent.CompletableFuture",
    "java.util.concurrent.Future",
    "java.util.Timer",
)
FORBIDDEN_TYPE_NAMES = frozenset(
    {
        "File",
        "Files",
        "FileInputStream",
        "FileOutputStream",
        "FileReader",
        "FileWriter",
        "RandomAccessFile",
        "Path",
        "Paths",
        "FileSystem",
        "FileSystems",
        "URI",
        "URL",
        "URLConnection",
        "HttpClient",
        "HttpRequest",
        "HttpResponse",
        "Socket",
        "ServerSocket",
        "DatagramSocket",
        "Desktop",
        "Component",
        "Container",
        "Window",
        "Frame",
        "Dialog",
        "JFrame",
        "JDialog",
        "JPanel",
        "SwingUtilities",
        "UIManager",
        "Method",
        "Field",
        "Constructor",
        "AccessibleObject",
        "MethodHandles",
        "MethodHandle",
        "Process",
        "ProcessBuilder",
        "ProcessHandle",
        "Runtime",
        "Thread",
        "ThreadFactory",
        "Executor",
        "ExecutorService",
        "ScheduledExecutorService",
        "ScheduledThreadPoolExecutor",
        "ThreadPoolExecutor",
        "AbstractExecutorService",
        "Executors",
        "ForkJoinPool",
        "Timer",
        "TimerTask",
        "CompletableFuture",
        "Future",
    }
)
FORBIDDEN_MEMBER_PATTERNS = (
    (re.compile(r"\.\s*toCompletableFuture\s*\("), "CompletionStage.toCompletableFuture()"),
    (re.compile(r"\bCompletableFuture\s*\.\s*(?!completedStage\s*\()"), "CompletableFuture operation other than completedStage()"),
    (re.compile(r"\.\s*parallelStream\s*\("), "parallel stream concurrency"),
    (re.compile(r"\b(?:Files|Path|Paths|FileSystem|FileSystems)\s*\."), "direct filesystem access"),
    (re.compile(r"\b(?:URI|URL|URLConnection|HttpClient|HttpRequest|HttpResponse|Socket|ServerSocket|DatagramSocket)\s*\."), "direct network access"),
    (re.compile(r"\b(?:Desktop|SwingUtilities|UIManager)\s*\."), "direct host UI access"),
    (re.compile(r"\bClass\s*\.\s*forName\s*\("), "Class.forName() reflection"),
    (re.compile(r"\.\s*(?:getDeclared(?:Method|Field|Constructor)|get(?:Method|Field|Constructor))\s*\("), "reflection lookup"),
    (re.compile(r"\b(?:System|Runtime)\s*\.\s*(?:exit|getRuntime)\s*\("), "process/runtime access"),
    (re.compile(r"\.\s*(?:exec|start)\s*\("), "process launch"),
)

# A B1 entrypoint delta is deliberately narrow.  It must be mechanically
# recognizable rather than requiring a Java parser or trusting handwritten
# comments.  Worker code keeps every lifecycle bridge on one source line.
ENTRYPOINT_ALLOWED_IMPORT = re.compile(r"^import\s+([\w.]+\.b1\.application(?:\.[\w$]+|\.\*))\s*;$")
ENTRYPOINT_ALLOWED_FIELD = re.compile(
    r"^(?:private|protected|public)\s+(?:static\s+)?(?:final\s+)?[\w$.<>?,\s\[\]]+\s+\w+\s*(?:=\s*new\s+[\w$.<>?,\s]+\([^;]*\))?;$"
)
ENTRYPOINT_ALLOWED_ASSIGNMENT = re.compile(r"^(?:this\.)?\w+\s*=\s*(?:new\s+)?[\w$.<>?,()\s]+;$")
ENTRYPOINT_ALLOWED_WIRING = re.compile(
    r"^(?:this\.)?\w+\.(?:init\(context\.config\(\)\)|enable\(\)|disable\(\)|shutdown\(\))\s*;$"
)
ENTRYPOINT_ALLOWED_NULL_GUARD = re.compile(
    r"^if\s*\(\s*(?:this\.)?\w+\s*(?:==|!=)\s*null\s*\)\s*(?:\{\s*)?return;\s*\}?$"
)
ENTRYPOINT_ALLOWED_REQUIRE_NON_NULL = re.compile(
    r"^Objects\.requireNonNull\(\s*(?:this\.)?\w+(?:\s*,\s*\"(?:\\.|[^\"\\])*\")?\s*\);$"
)
FUTURE_DECLARATION = re.compile(
    r"\b(?:[A-Za-z_$][\w$]*\.)*(?:Future|CompletableFuture|CompletionStage)\s*"
    r"(?:<[^;=(){}]*>)?\s+([A-Za-z_$][\w$]*)\b"
)
FUTURE_BLOCKING_CALL = re.compile(r"\b(?:this\.)?([A-Za-z_$][\w$]*)\s*\.\s*(get|join)\s*\(")


@dataclass(frozen=True)
class Violation:
    path: Path
    line: int
    message: str

    def render(self, root: Path) -> str:
        try:
            display = self.path.relative_to(root).as_posix()
        except ValueError:
            display = self.path.as_posix()
        return f"{display}:{self.line}: {self.message}"


def fail(message: str) -> None:
    print(f"FAIL: {message}", file=sys.stderr)
    raise SystemExit(1)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--root",
        type=Path,
        default=Path(__file__).resolve().parents[2],
        help="repository root to scan (default: this repository)",
    )
    parser.add_argument(
        "--baseline",
        default=DEFAULT_BASELINE,
        help=f"fixed Git baseline for entrypoint delta checks (default: {DEFAULT_BASELINE})",
    )
    return parser.parse_args()


def strip_comments_and_literals(source: str) -> str:
    """Preserve line count while excluding comments and string/char literals."""
    pattern = re.compile(
        r'("(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'|//[^\n]*|/\*.*?\*/)',
        re.DOTALL,
    )

    def replace(match: re.Match[str]) -> str:
        value = match.group(0)
        if value.startswith("//") or value.startswith("/*"):
            return "\n" * value.count("\n")
        return '""' if value.startswith('"') else "''"

    return pattern.sub(replace, source)


def package_from_source(path: Path, source: str) -> tuple[str | None, list[Violation]]:
    for line in strip_comments_and_literals(source).splitlines():
        match = PACKAGE_DECLARATION.match(line)
        if match:
            return match.group(1), []
    return None, [Violation(path, 1, "B1 source must declare a package")]


def normalize_plugin_package(entrypoint: str, manifest: Path, root: Path) -> str | None:
    if "." not in entrypoint:
        return None
    package = entrypoint.rsplit(".", 1)[0]
    if not package.startswith("dev.turboism.plugin."):
        fail(
            f"{manifest.relative_to(root).as_posix()}: plugin entrypoint package must begin "
            "dev.turboism.plugin."
        )
    return package


def import_is_allowed(value: str, owning_package: str) -> bool:
    if value.startswith("java.") or value.startswith("javax."):
        return value not in FORBIDDEN_JDK_IMPORT_EXACT and not value.startswith(FORBIDDEN_JDK_IMPORT_PREFIXES)
    if value == ALLOWED_B1_SDK_IMPORT or value.startswith(ALLOWED_B1_SDK_IMPORT + "."):
        return True
    return value == owning_package or value.startswith(owning_package + ".")


def import_violation_reason(value: str, owning_package: str) -> str:
    if value.startswith(FORBIDDEN_B1_IMPORT_PREFIXES):
        return f"forbidden B1 import {value}"
    if value in FORBIDDEN_JDK_IMPORT_EXACT or value.startswith(FORBIDDEN_JDK_IMPORT_PREFIXES):
        return f"forbidden direct I/O, UI, reflection, or concurrency import {value}"
    if value.startswith("dev.turboism.sdk."):
        return f"only dev.turboism.sdk.config.* is allowed in B1 source, found {value}"
    if value.startswith("dev.turboism.plugin.") and not (
        value == owning_package or value.startswith(owning_package + ".")
    ):
        return f"B1 source may not import another plugin package: {value}"
    return f"B1 source import is outside the allowlist: {value}"


def scan_b1_source(path: Path, source: str, owning_package: str, root: Path) -> list[Violation]:
    violations: list[Violation] = []
    relative = path.relative_to(root / "plugins")
    parts = relative.parts
    # plugins/<id>/src/main/java/<owning package>/b1/<domain|application>/...
    expected_prefix = (parts[0], *JAVA_ROOT.parts, *owning_package.split("."), "b1")
    if parts[: len(expected_prefix)] != expected_prefix:
        violations.append(
            Violation(path, 1, f"B1 source path must be below {'/'.join(expected_prefix)}/domain or /application")
        )
        return violations
    category_index = len(expected_prefix)
    if len(parts) <= category_index or parts[category_index] not in {"domain", "application"}:
        violations.append(Violation(path, 1, "B1 source path must be under b1/domain or b1/application"))

    package, package_errors = package_from_source(path, source)
    violations.extend(package_errors)
    if package is not None and package not in {f"{owning_package}.b1.domain", f"{owning_package}.b1.application"}:
        violations.append(
            Violation(path, 1, f"B1 package must be {owning_package}.b1.domain or {owning_package}.b1.application")
        )

    cleaned = strip_comments_and_literals(source)
    future_variables: set[str] = set()
    for number, line in enumerate(cleaned.splitlines(), start=1):
        import_match = IMPORT_DECLARATION.match(line)
        if import_match:
            imported = import_match.group(1)
            if not import_is_allowed(imported, owning_package):
                violations.append(Violation(path, number, import_violation_reason(imported, owning_package)))
            continue
        future_variables.update(match.group(1) for match in FUTURE_DECLARATION.finditer(line))
        allowed_completed_stage = bool(re.search(
            r"(?<![\w$])(?:java\.util\.concurrent\.)?CompletableFuture\s*\.\s*completedStage\s*\(",
            line,
        ))
        for prefix in FORBIDDEN_QUALIFIED_PREFIXES:
            if prefix == "java.util.concurrent.CompletableFuture" and allowed_completed_stage:
                continue
            if re.search(rf"(?<![\w$]){re.escape(prefix)}(?:\.|\b)", line):
                violations.append(Violation(path, number, f"forbidden fully qualified B1 API {prefix}"))
        for token in TYPE_TOKEN.findall(line):
            if token == "CompletableFuture" and allowed_completed_stage:
                continue
            if token in FORBIDDEN_TYPE_NAMES:
                violations.append(Violation(path, number, f"forbidden B1 API/type {token}"))
        for pattern, description in FORBIDDEN_MEMBER_PATTERNS:
            if pattern.search(line):
                violations.append(Violation(path, number, f"forbidden B1 operation {description}"))
        for future_call in FUTURE_BLOCKING_CALL.finditer(line):
            if future_call.group(1) in future_variables:
                violations.append(
                    Violation(path, number, f"forbidden B1 operation blocking future {future_call.group(2)}()")
                )
    return violations


def git_tree_files(root: Path, baseline: str, relative_root: Path) -> set[Path]:
    result = subprocess.run(
        ["git", "-C", str(root), "ls-tree", "-r", "--name-only", baseline, "--", relative_root.as_posix()],
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        check=False,
    )
    if result.returncode != 0:
        fail(
            f"cannot enumerate baseline {baseline}:{relative_root.as_posix()}; "
            f"ensure --root is a Git work tree containing the fixed baseline\n{result.stderr.strip()}"
        )
    return {Path(line) for line in result.stdout.splitlines() if line}


def git_show(root: Path, baseline: str, relative: Path) -> str | None:
    result = subprocess.run(
        ["git", "-C", str(root), "show", f"{baseline}:{relative.as_posix()}"],
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        check=False,
    )
    if result.returncode == 0:
        return result.stdout
    if "does not exist" in result.stderr or "exists on disk, but not in" in result.stderr:
        return None
    fail(
        f"cannot read baseline {baseline}:{relative.as_posix()}; "
        f"ensure --root is a Git work tree containing the fixed baseline\n{result.stderr.strip()}"
    )
    return None


def normalized_lines(source: str) -> list[str]:
    return [line.rstrip() for line in source.splitlines()]


def baseline_is_subsequence(baseline_lines: list[str], current_lines: list[str]) -> bool:
    cursor = 0
    for line in current_lines:
        if cursor < len(baseline_lines) and line == baseline_lines[cursor]:
            cursor += 1
    return cursor == len(baseline_lines)


def added_lines(baseline_lines: list[str], current_lines: list[str]) -> list[tuple[int, str]]:
    """Return lines inserted while matching the baseline as a strict subsequence."""
    additions: list[tuple[int, str]] = []
    cursor = 0
    for number, line in enumerate(current_lines, start=1):
        if cursor < len(baseline_lines) and line == baseline_lines[cursor]:
            cursor += 1
        else:
            additions.append((number, line))
    return additions


def is_comment_or_blank(line: str) -> bool:
    stripped = line.strip()
    return not stripped or stripped.startswith("//") or stripped.startswith("/*") or stripped.startswith("*") or stripped.startswith("*/")


def is_own_b1_application_type(value: str, owning_package: str) -> bool:
    return value.startswith(f"{owning_package}.b1.application.")


def application_imports_from_additions(additions: list[tuple[int, str]], owning_package: str) -> set[str]:
    imported: set[str] = set()
    for _number, line in additions:
        match = ENTRYPOINT_ALLOWED_IMPORT.match(line.strip())
        if match:
            target = match.group(1)
            if target.startswith(f"{owning_package}.b1.application."):
                imported.add(target.rsplit(".", 1)[-1])
    return imported


def is_allowed_entrypoint_field(line: str, owning_package: str, application_types: set[str]) -> bool:
    if not ENTRYPOINT_ALLOWED_FIELD.match(line):
        return False
    declared = re.search(r"(?:private|protected|public)\s+(?:static\s+)?(?:final\s+)?([\w$.]+)\s+\w+", line)
    constructed = re.search(r"=\s*new\s+([\w$.]+)\s*\(([^;]*)\)\s*;$", line)
    if declared is None or constructed is None:
        return False
    declared_type = declared.group(1)
    constructed_type = constructed.group(1)
    constructor_arguments = constructed.group(2).strip()
    return (
        constructor_arguments == ""
        and declared_type == constructed_type
        and (
            is_own_b1_application_type(declared_type, owning_package)
            or declared_type in application_types
        )
    )


def entrypoint_receiver(line: str) -> str | None:
    match = re.match(r"^(?:this\.)?([A-Za-z_$][\w$]*)\s*\.", line)
    return match.group(1) if match else None


def entrypoint_guarded_name(line: str) -> str | None:
    match = re.match(r"^if\s*\(\s*(?:this\.)?([A-Za-z_$][\w$]*)\s*(?:==|!=)\s*null\s*\)", line)
    return match.group(1) if match else None


def is_application_variable(name: str | None) -> bool:
    return name is not None and (name == "b1Application" or name.lower().endswith("application"))


def is_allowed_entrypoint_addition(
    line: str,
    owning_package: str,
    application_types: set[str],
) -> bool:
    stripped = line.strip()
    if is_comment_or_blank(stripped):
        return True
    import_match = ENTRYPOINT_ALLOWED_IMPORT.match(stripped)
    if import_match:
        target = import_match.group(1)
        return target == f"{owning_package}.b1.application" or target.startswith(
            f"{owning_package}.b1.application."
        )
    if ENTRYPOINT_ALLOWED_WIRING.match(stripped):
        return is_application_variable(entrypoint_receiver(stripped))
    if ENTRYPOINT_ALLOWED_NULL_GUARD.match(stripped):
        return is_application_variable(entrypoint_guarded_name(stripped))
    if ENTRYPOINT_ALLOWED_REQUIRE_NON_NULL.match(stripped):
        guarded = stripped.split("(", 1)[1].split(",", 1)[0].strip()
        if guarded.startswith("this."):
            guarded = guarded[5:]
        return "." not in guarded and is_application_variable(guarded)
    if is_allowed_entrypoint_field(stripped, owning_package, application_types):
        return True
    if ENTRYPOINT_ALLOWED_ASSIGNMENT.match(stripped):
        assignment = re.match(
            r"^(?:this\.)?([A-Za-z_$][\w$]*)\s*=\s*new\s+([\w$.]+)\s*\(\s*\)\s*;$",
            stripped,
        )
        if assignment is None or not is_application_variable(assignment.group(1)):
            return False
        constructed_type = assignment.group(2)
        return constructed_type in application_types or is_own_b1_application_type(
            constructed_type, owning_package
        )
    return False


def scan_entrypoint_delta(
    path: Path,
    source: str,
    baseline_source: str | None,
    owning_package: str,
    root: Path,
) -> list[Violation]:
    if baseline_source is None:
        return [Violation(path, 1, "entrypoint source is absent from the fixed B1 baseline")]
    baseline_lines = normalized_lines(baseline_source)
    current_lines = normalized_lines(source)
    violations: list[Violation] = []
    if not baseline_is_subsequence(baseline_lines, current_lines):
        violations.append(
            Violation(path, 1, "baseline entrypoint source must remain a line-for-line ordered subsequence")
        )
        return violations
    additions = added_lines(baseline_lines, current_lines)
    application_types = application_imports_from_additions(additions, owning_package)
    for number, line in additions:
        if not is_allowed_entrypoint_addition(line, owning_package, application_types):
            violations.append(
                Violation(
                    path,
                    number,
                    "entrypoint B1 delta is not a permitted one-line application lifecycle bridge: " + line.strip(),
                )
            )
    return violations


def parse_entrypoints(value: object, label: str) -> list[str]:
    if isinstance(value, list):
        entries = value
    elif isinstance(value, dict) and set(value) == {"plugin"}:
        # Historical B1 baselines predate plugin.meta v2. Runtime never accepts this shape.
        entries = [value["plugin"]]
    else:
        fail(f"{label}: entrypoints must be an ordered array")
    if not entries or any(not isinstance(entry, str) or not entry for entry in entries):
        fail(f"{label}: entrypoints must contain non-empty strings")
    if len(set(entries)) != len(entries):
        fail(f"{label}: entrypoints must be unique")
    return list(entries)


def manifest_entrypoints(manifest: Path, root: Path) -> list[str]:
    try:
        data = json.loads(manifest.read_text(encoding="utf-8"))
        return parse_entrypoints(
            data["entrypoints"],
            manifest.relative_to(root).as_posix(),
        )
    except (OSError, UnicodeDecodeError, json.JSONDecodeError, KeyError, TypeError) as error:
        fail(f"{manifest.relative_to(root).as_posix()}: cannot read plugin entrypoints: {error}")


def entrypoint_path(plugin_root: Path, entrypoint: str) -> Path:
    return plugin_root / JAVA_ROOT / Path(*entrypoint.split(".")).with_suffix(".java")


def plugin_roots(root: Path) -> Iterable[Path]:
    plugins = root / "plugins"
    if not plugins.is_dir():
        fail(f"plugin root missing: {plugins}")
    yield from sorted(path for path in plugins.iterdir() if path.is_dir())


def scan(root: Path, baseline: str) -> list[Violation]:
    violations: list[Violation] = []
    baseline_plugin_files = git_tree_files(root, baseline, Path("plugins"))
    current_plugin_roots = list(plugin_roots(root))
    current_manifests = {
        (plugin_root / MANIFEST_PATH).relative_to(root)
        for plugin_root in current_plugin_roots
        if (plugin_root / MANIFEST_PATH).is_file()
    }
    baseline_manifests = {path for path in baseline_plugin_files if path.parts[-len(MANIFEST_PATH.parts):] == MANIFEST_PATH.parts}
    for missing_manifest in sorted(baseline_manifests - current_manifests):
        violations.append(Violation(root / missing_manifest, 1, "baseline plugin manifest must not be removed"))
    for plugin_root in current_plugin_roots:
        manifest = plugin_root / MANIFEST_PATH
        if not manifest.is_file():
            continue
        manifest_relative = manifest.relative_to(root)
        baseline_manifest_source = git_show(root, baseline, manifest_relative)
        entrypoints = manifest_entrypoints(manifest, root)
        entrypoint = entrypoints[0]
        if baseline_manifest_source is None:
            violations.append(Violation(manifest, 1, "plugin manifest is absent from the fixed B1 baseline"))
        else:
            try:
                baseline_entrypoints = parse_entrypoints(
                    json.loads(baseline_manifest_source)["entrypoints"],
                    manifest_relative.as_posix() + " (baseline)",
                )
            except (json.JSONDecodeError, KeyError, TypeError) as error:
                fail(f"{manifest_relative.as_posix()}: cannot read baseline plugin entrypoint: {error}")
            if entrypoints[:len(baseline_entrypoints)] != baseline_entrypoints:
                violations.append(Violation(
                    manifest,
                    1,
                    "historical entrypoints must remain an ordered prefix of plugin.meta v2 entrypoints",
                ))
        owning_package = normalize_plugin_package(entrypoint, manifest, root)
        if owning_package is None:
            continue
        main = plugin_root / JAVA_ROOT
        current_java_files = set(main.rglob("*.java")) if main.is_dir() else set()
        baseline_java_files = {
            path for path in baseline_plugin_files
            if path.parts[:2] == ("plugins", plugin_root.name)
            and path.parts[2:2 + len(JAVA_ROOT.parts)] == JAVA_ROOT.parts
            and path.suffix == ".java"
        }
        current_java_relatives = {path.relative_to(root) for path in current_java_files}
        entrypoint_source_paths = {
            entrypoint_path(plugin_root, value) for value in entrypoints
        }
        for missing in sorted(baseline_java_files - current_java_relatives):
            violations.append(Violation(root / missing, 1, "baseline production source must not be removed"))
        for path in sorted(current_java_files):
            relative = path.relative_to(root)
            relative_to_main = path.relative_to(main)
            source = path.read_text(encoding="utf-8")
            if "b1" in relative_to_main.parts:
                violations.extend(scan_b1_source(path, source, owning_package, root))
                continue
            baseline_source = git_show(root, baseline, relative)
            if baseline_source is None:
                violations.append(Violation(path, 1, "new B1 production source must live below b1/domain or b1/application"))
            elif source != baseline_source and path not in entrypoint_source_paths:
                violations.append(Violation(path, 1, "baseline non-entrypoint production source must remain byte-for-byte unchanged"))
        for entrypoint_source_path in sorted(entrypoint_source_paths):
            if not entrypoint_source_path.is_file():
                violations.append(Violation(
                    entrypoint_source_path,
                    1,
                    "manifest entrypoint source file is missing",
                ))
                continue
            relative = entrypoint_source_path.relative_to(root)
            baseline_source = git_show(root, baseline, relative)
            source = entrypoint_source_path.read_text(encoding="utf-8")
            relative_to_main = entrypoint_source_path.relative_to(main)
            if baseline_source is None and "b1" in relative_to_main.parts:
                violations.extend(scan_b1_source(
                    entrypoint_source_path,
                    source,
                    owning_package,
                    root,
                ))
            else:
                violations.extend(scan_entrypoint_delta(
                    entrypoint_source_path,
                    source,
                    baseline_source,
                    owning_package,
                    root,
                ))
    return violations


def main() -> None:
    args = parse_args()
    root = args.root.resolve()
    if not root.is_dir():
        fail(f"repository root does not exist: {root}")
    violations = scan(root, args.baseline)
    if violations:
        print("FAIL: legacy plugin B1 production source boundary violations:", file=sys.stderr)
        for violation in violations:
            print(f"  {violation.render(root)}", file=sys.stderr)
        raise SystemExit(1)
    print(f"PASS: legacy plugin B1 production source boundaries (baseline {args.baseline})")


if __name__ == "__main__":
    main()
