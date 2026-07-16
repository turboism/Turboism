#!/usr/bin/env python3
from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
PLUGIN_MAIN = ROOT / "plugins"
PROJECT_INSPECTOR = (
    ROOT
    / "plugins/project-inspector/src/main/java/dev/turboism/plugin/projectinspector/ProjectInspectorPlugin.java"
)

FORBIDDEN_TYPES = (
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
)


def strip_comments_and_literals(source: str) -> str:
    pattern = re.compile(
        r'("(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'|//[^\n]*|/\*.*?\*/)',
        re.DOTALL,
    )

    def replace(match: re.Match[str]) -> str:
        text = match.group(0)
        if text.startswith("//") or text.startswith("/*"):
            return "\n" * text.count("\n")
        return '""' if text.startswith('"') else "''"

    return pattern.sub(replace, source)


def fail(message: str) -> None:
    print(f"FAIL: {message}", file=sys.stderr)
    raise SystemExit(1)


def main() -> None:
    violations: list[str] = []
    sources = sorted(PLUGIN_MAIN.glob("*/src/main/java/**/*.java"))
    if not sources:
        fail("no production plugin Java sources found")

    forbidden_pattern = re.compile(
        r"\b(?:" + "|".join(re.escape(item) for item in FORBIDDEN_TYPES) + r")\b"
    )
    forbidden_async = re.compile(
        r"\bCompletableFuture\s*\.\s*(?:runAsync|supplyAsync)\s*\(|"
        r"\bForkJoinPool\s*\.\s*commonPool\s*\(|"
        r"\.\s*parallelStream\s*\(|\.\s*parallel\s*\("
    )
    for path in sources:
        cleaned = strip_comments_and_literals(path.read_text(encoding="utf-8"))
        for line_number, line in enumerate(cleaned.splitlines(), start=1):
            ownership_line = re.sub(r"\bThread\s*\.\s*currentThread\s*\(\s*\)\s*\.\s*interrupt\s*\(\s*\)", "", line)
            match = forbidden_pattern.search(ownership_line)
            async_match = forbidden_async.search(line)
            if match:
                violations.append(
                    f"{path.relative_to(ROOT).as_posix()}:{line_number}: "
                    f"plugin-owned execution type {match.group(0)} is forbidden"
                )
            if async_match:
                violations.append(
                    f"{path.relative_to(ROOT).as_posix()}:{line_number}: "
                    "plugin-owned common-pool or parallel execution is forbidden"
                )

    if not PROJECT_INSPECTOR.is_file():
        fail("ProjectInspectorPlugin.java is missing")
    inspector = strip_comments_and_literals(PROJECT_INSPECTOR.read_text(encoding="utf-8"))
    if re.search(r"\bcontext\s*\.\s*cubismRead\s*\(", inspector):
        violations.append(
            "plugins/project-inspector/src/main/java/dev/turboism/plugin/projectinspector/"
            "ProjectInspectorPlugin.java: synchronous context.cubismRead() is forbidden"
        )
    if not re.search(r"\bcontext\s*\.\s*hostReads\s*\(", inspector):
        violations.append(
            "ProjectInspectorPlugin.java must consume context.hostReads()"
        )

    if violations:
        fail("async host-read production boundary violations:\n" + "\n".join(violations))
    print(
        "PASS: async host-read production boundaries "
        f"({len(sources)} plugin source files scanned)"
    )


if __name__ == "__main__":
    main()
