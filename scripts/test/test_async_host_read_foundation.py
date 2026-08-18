#!/usr/bin/env python3
"""Async host-read structural boundary gate.

Scope is intentionally narrowed to the async-host foundation's actual new
production consumer: plugins/project-inspector/.../ProjectInspectorPlugin.java.
The 41 pre-existing thread/executor/timer usages across the other 8 plugins
are recorded product debt, not migrated or allow-listed here; this script must
never claim they are clean.
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
INSPECTOR = (
    "plugins/project-inspector/src/main/java/dev/turboism/plugin/projectinspector/"
    "ProjectInspectorPlugin.java"
)
PROJECT_INSPECTOR = ROOT / INSPECTOR

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
    if not PROJECT_INSPECTOR.is_file():
        fail(f"ProjectInspectorPlugin.java is missing at {INSPECTOR}")
    violations: list[str] = []

    forbidden_pattern = re.compile(
        r"\b(?:" + "|".join(re.escape(item) for item in FORBIDDEN_TYPES) + r")\b"
    )
    forbidden_async = re.compile(
        r"\bCompletableFuture\s*\.\s*(?:runAsync|supplyAsync)\s*\(|"
        r"\bForkJoinPool\s*\.\s*commonPool\s*\(|"
        r"\.\s*parallelStream\s*\(|\.\s*parallel\s*\("
    )
    cleaned = strip_comments_and_literals(PROJECT_INSPECTOR.read_text(encoding="utf-8"))
    for line_number, line in enumerate(cleaned.splitlines(), start=1):
        ownership_line = re.sub(r"\bThread\s*\.\s*currentThread\s*\(\s*\)\s*\.\s*interrupt\s*\(\s*\)", "", line)
        match = forbidden_pattern.search(ownership_line)
        async_match = forbidden_async.search(line)
        if match:
            violations.append(
                f"{INSPECTOR}:{line_number}: "
                f"plugin-owned execution type {match.group(0)} is forbidden"
            )
        if async_match:
            violations.append(
                f"{INSPECTOR}:{line_number}: "
                "plugin-owned common-pool or parallel execution is forbidden"
            )

    if re.search(r"\bcontext\s*\.\s*cubismRead\s*\(", cleaned):
        violations.append(
            f"{INSPECTOR}: synchronous context.cubismRead() is forbidden"
        )
    if not re.search(r"\bcontext\s*\.\s*hostReads\s*\(", cleaned):
        violations.append(
            f"{INSPECTOR} must consume context.hostReads()"
        )

    if violations:
        fail("async host-read production boundary violations:\n" + "\n".join(violations))
    print(
        "PASS: async host-read consumer boundary check passed for "
        "plugins/project-inspector/src/main/java/dev/turboism/plugin/projectinspector/"
        "ProjectInspectorPlugin.java (pre-existing async usage in unrelated "
        "plugins is out of scope and NOT claimed clean)"
    )


if __name__ == "__main__":
    main()
