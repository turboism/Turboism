#!/usr/bin/env python3
"""Reject deprecated package layouts and package-only production Java shells."""
from __future__ import annotations

import re
import sys
from pathlib import Path

FORBIDDEN_DIRECTORIES = (
    "sdk/src/main/java/dev/turboism/sdk/cubism/callback",
)

FORBIDDEN_FILES = (
    "sdk/src/main/java/dev/turboism/sdk/cubism/transaction/DocumentId.java",
    "runtime/src/main/java/dev/turboism/core/runtime/CallbackExecutionResult.java",
    "runtime/src/main/java/dev/turboism/core/runtime/CallbackExecutionStatus.java",
    "runtime/src/main/java/dev/turboism/core/runtime/CallbackSubmission.java",
    "runtime/src/main/java/dev/turboism/core/runtime/PluginCallback.java",
    "runtime/src/main/java/dev/turboism/core/runtime/PluginCallbackExecutor.java",
    "runtime/src/main/java/dev/turboism/core/runtime/PluginCallbackExecutorConfiguration.java",
    "runtime/src/main/java/dev/turboism/core/runtime/PluginExecutorRegistry.java",
)

REQUIRED_FILES = (
    "sdk/src/main/java/dev/turboism/sdk/cubism/hook/ParameterHooks.java",
    "sdk/src/main/java/dev/turboism/sdk/cubism/hook/PartHooks.java",
    "sdk/src/main/java/dev/turboism/sdk/cubism/event/SelectionChangedEvent.java",
    "sdk/src/main/java/dev/turboism/sdk/cubism/id/DocumentId.java",
    "runtime/src/main/java/dev/turboism/core/runtime/work/PluginWorkExecutor.java",
    "runtime/src/main/java/dev/turboism/core/runtime/work/PluginWorkExecutorRegistry.java",
    "runtime/src/main/java/dev/turboism/core/runtime/work/PluginWorkResult.java",
    "runtime/src/main/java/dev/turboism/core/runtime/work/PluginWorkStatus.java",
    "runtime/src/main/java/dev/turboism/core/runtime/work/PluginWorkSubmission.java",
)

FORBIDDEN_PRODUCTION_TEXT = (
    "dev.turboism.sdk.cubism.callback",
    "dev.turboism.sdk.cubism.transaction.DocumentId",
    "CallbackExecutionResult",
    "CallbackExecutionStatus",
    "CallbackSubmission",
    "PluginCallbackExecutor",
    "PluginExecutorRegistry",
    "CallbackBudgetEvent",
)

PRODUCTION_ROOTS = (
    "sdk/src/main/java",
    "runtime/src/main/java",
)

PACKAGE_ONLY = re.compile(r"\A\s*package\s+[A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*\s*;\s*\Z")
BLOCK_COMMENT = re.compile(r"/\*.*?\*/", re.DOTALL)
LINE_COMMENT = re.compile(r"//[^\r\n]*")


def main() -> int:
    root = Path(sys.argv[1] if len(sys.argv) > 1 else ".").resolve()
    failures: list[str] = []

    for relative in FORBIDDEN_DIRECTORIES:
        path = root / relative
        if path.exists():
            failures.append(f"deprecated package directory exists: {relative}")

    for relative in FORBIDDEN_FILES:
        path = root / relative
        if path.exists():
            failures.append(f"deprecated production type exists: {relative}")

    for relative in REQUIRED_FILES:
        path = root / relative
        if not path.is_file():
            failures.append(f"required package-layout type is missing: {relative}")

    for source_root in PRODUCTION_ROOTS:
        directory = root / source_root
        for source in sorted(directory.rglob("*.java")):
            relative = source.relative_to(root).as_posix()
            text = source.read_text(encoding="utf-8")
            if source.name != "package-info.java" and is_package_only(text):
                failures.append(f"package-only production Java shell: {relative}")
            for forbidden in FORBIDDEN_PRODUCTION_TEXT:
                if forbidden in text:
                    failures.append(f"deprecated package/type reference {forbidden!r}: {relative}")

    duplicate_document_ids = sorted(
        path.relative_to(root).as_posix()
        for source_root in PRODUCTION_ROOTS
        for path in (root / source_root).rglob("DocumentId.java")
        if path.relative_to(root).as_posix()
        != "sdk/src/main/java/dev/turboism/sdk/cubism/id/DocumentId.java"
    )
    failures.extend(
        f"duplicate DocumentId type outside sdk.cubism.id: {relative}"
        for relative in duplicate_document_ids
    )

    if failures:
        for failure in failures:
            print(f"FAIL: {failure}")
        return 1

    print("PASS: package layout uses hook/event/id/work ownership and has no production Java shells")
    return 0


def is_package_only(text: str) -> bool:
    without_comments = BLOCK_COMMENT.sub("", text)
    without_comments = LINE_COMMENT.sub("", without_comments)
    return PACKAGE_ONLY.fullmatch(without_comments) is not None


if __name__ == "__main__":
    raise SystemExit(main())
