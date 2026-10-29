#!/usr/bin/env python3
"""Enforce the single Throwable/Error containment policy in production sources.

Every ``catch (Throwable|Error|multi-catch <var>)`` site must make its fatal-vs-containable decision through
one auditable mechanism instead of re-deciding per call site. A site is compliant when it

- opens its block with ``FatalErrors.rethrowIfFatal(<var>)`` (runtime/bootstrap sources), or
- is preceded in the same catch chain by a clause naming ``ThreadDeath``/``VirtualMachineError``
  that throws the caught fatal variable (sources that cannot see runtime internals), or
- carries an inline ``@containment-exempt: <reason>`` marker in the block head before any executable
  statement when the catch itself already propagates fatal failures correctly (e.g. a priority-merge rethrow).

Usage: check_throwable_containment.py [repo-root] [--report]
"""
from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path


PRODUCTION_ROOTS = (
    "sdk/src/main/java",
    "core-contract/src/main/java",
    "event-processor/src/main/java",
    "graal-host/src/main/java",
    "runtime/src/main/java",
    "bootstrap/src/main/java",
    "scripts/verification-sources/templates",
)
PLUGIN_ROOT = "plugins"
RUNTIME_BOOTSTRAP_PREFIXES = (
    "runtime/src/main/java",
    "bootstrap/src/main/java",
)

CATCH_RE = re.compile(
    r"catch\s*\(\s*(?:final\s+)?([A-Za-z0-9_$.|\s]*?\b(?:(?:java\.lang\.)?Throwable|(?:java\.lang\.)?Error)\b[A-Za-z0-9_$.|\s]*?)\s+(\w+)\s*\)\s*\{"
)
NON_STANDARD_FATAL_CATCH_RE = re.compile(
    r"catch\s*\([^)]*\b(?:ThreadDeath|VirtualMachineError)\b[^)]*\)\s*\{"
)
PRECEDING_FATAL_RE = re.compile(
    r"catch\s*\(\s*(?:final\s+)?(?:\bThreadDeath\b\s*\|\s*\bVirtualMachineError\b|\bVirtualMachineError\b\s*\|\s*\bThreadDeath\b)\s+(\w+)\s*\)\s*\{([^}]*)\}\s*$"
)
LONE_VME_CATCH_RE = re.compile(
    r"catch\s*\([^)]*\bVirtualMachineError\b[^)]*\)"
)
EXEMPT_MARKER = "@containment-exempt"
BLOCK_HEAD = 220


def mask_java_source(text: str) -> tuple[str, list[tuple[int, int, str]]]:
    """Masks string literals, char literals, text blocks, and comments to whitespace,
    preserving exact character offsets and line numbers.
    Returns (masked_text, comments) where comments is a list of (start, end, raw_comment).
    """
    n = len(text)
    out = list(text)
    comments: list[tuple[int, int, str]] = []
    i = 0
    while i < n:
        c = text[i]
        # Text block: """
        if c == '"' and i + 2 < n and text[i + 1] == '"' and text[i + 2] == '"':
            start = i
            i += 3
            while i + 2 < n:
                if text[i] == '"' and text[i + 1] == '"' and text[i + 2] == '"' and text[i - 1] != '\\':
                    break
                i += 1
            i = min(n, i + 3)
            for k in range(start, i):
                if text[k] != '\n':
                    out[k] = ' '
        # Regular string: "
        elif c == '"':
            start = i
            i += 1
            while i < n and text[i] != '"':
                if text[i] == '\\':
                    i += 1
                i += 1
            i = min(n, i + 1)
            for k in range(start, i):
                if text[k] != '\n':
                    out[k] = ' '
        # Char literal: '
        elif c == "'":
            start = i
            i += 1
            while i < n and text[i] != "'":
                if text[i] == '\\':
                    i += 1
                i += 1
            i = min(n, i + 1)
            for k in range(start, i):
                if text[k] != '\n':
                    out[k] = ' '
        # Line comment: //
        elif c == '/' and i + 1 < n and text[i + 1] == '/':
            start = i
            end = text.find('\n', i)
            if end == -1:
                end = n
            comments.append((start, end, text[start:end]))
            for k in range(start, end):
                out[k] = ' '
            i = end
        # Block comment: /*
        elif c == '/' and i + 1 < n and text[i + 1] == '*':
            start = i
            end = text.find('*/', i + 2)
            if end == -1:
                end = n
            else:
                end += 2
            comments.append((start, end, text[start:end]))
            for k in range(start, end):
                if text[k] != '\n':
                    out[k] = ' '
            i = end
        else:
            i += 1
    return "".join(out), comments


def find_block_end(masked_text: str, open_brace: int) -> int:
    """Finds matching closing brace on masked text (where strings/comments are whitespace)."""
    depth = 0
    for i in range(open_brace, len(masked_text)):
        if masked_text[i] == '{':
            depth += 1
        elif masked_text[i] == '}':
            depth -= 1
            if depth == 0:
                return i
    return len(masked_text)


def get_catch_exempt_reason(
    open_brace: int, block_end: int, first_code_pos: int, comments: list[tuple[int, int, str]]
) -> str | None:
    """Finds an @containment-exempt marker with a reason inside this catch block head
    strictly before the first executable statement.
    """
    head_end = min(block_end, open_brace + BLOCK_HEAD, first_code_pos)
    for c_start, c_end, comment_text in comments:
        if open_brace <= c_start < head_end:
            m = re.search(r"@containment-exempt\s*:\s*(\S[^\r\n]*)", comment_text)
            if m and m.group(1).strip():
                return m.group(1).strip()
    return None


def has_direct_rethrow(body_masked: str, var_name: str) -> bool:
    """Returns True if the body sequentially executes without branching/loops/inner try-catch
    and ends with an unconditional raw throw of the exact caught variable (no cast).
    """
    if re.search(r"\b(?:return|if|switch|while|for|case|catch|try)\b", body_masked):
        return False
    return bool(re.search(rf"(?:^|[;{{}}])\s*throw\s+{re.escape(var_name)}\s*;\s*$", body_masked.strip()))


def has_valid_preceding_fatal(preceding_masked: str) -> bool:
    m = PRECEDING_FATAL_RE.search(preceding_masked)
    if not m:
        return False
    fatal_var = m.group(1)
    body = m.group(2)
    return has_direct_rethrow(body, fatal_var)


def sources(root: Path):
    for relative in PRODUCTION_ROOTS:
        base = root / relative
        if base.is_dir():
            yield from sorted(base.rglob("*.java"))
    plugins = root / PLUGIN_ROOT
    if plugins.is_dir():
        for src in sorted(plugins.glob("*/src/main/java")):
            if src.is_dir():
                yield from sorted(src.rglob("*.java"))


def findings(root: Path):
    violations = []
    for path in sources(root):
        text = path.read_text(encoding="utf-8")
        try:
            relative = path.relative_to(root)
        except ValueError:
            relative = path
        rel_str = str(relative).replace("\\", "/")
        is_runtime_bootstrap = any(
            rel_str.startswith(prefix) for prefix in RUNTIME_BOOTSTRAP_PREFIXES
        )

        masked_text, comments = mask_java_source(text)

        if is_runtime_bootstrap:
            for match in NON_STANDARD_FATAL_CATCH_RE.finditer(masked_text):
                open_brace = match.end() - 1
                block_end = find_block_end(masked_text, open_brace)
                body_masked = masked_text[open_brace + 1:block_end]
                first_code_rel = len(body_masked) - len(body_masked.lstrip())
                first_code_pos = open_brace + 1 + first_code_rel if body_masked.strip() else block_end
                if get_catch_exempt_reason(open_brace, block_end, first_code_pos, comments):
                    continue
                line = text.count("\n", 0, match.start()) + 1
                violations.append(
                    f"{relative}:{line}: non-standard fatal error catch; "
                    "runtime/bootstrap sources must use FatalErrors.rethrowIfFatal"
                )
        else:
            for match in LONE_VME_CATCH_RE.finditer(masked_text):
                if "ThreadDeath" not in match.group(0):
                    line = text.count("\n", 0, match.start()) + 1
                    violations.append(
                        f"{relative}:{line}: non-standard fatal error catch; "
                        "non-runtime sources must catch both ThreadDeath and VirtualMachineError"
                    )

        for match in CATCH_RE.finditer(masked_text):
            caught_type = match.group(1).strip()
            var_name = match.group(2).strip()
            open_brace = match.end() - 1
            block_end = find_block_end(masked_text, open_brace)
            body_masked = masked_text[open_brace + 1:block_end]

            first_code_rel = len(body_masked) - len(body_masked.lstrip())
            first_code_pos = open_brace + 1 + first_code_rel if body_masked.strip() else block_end

            # 1. Reviewed local exemption marker inside block head strictly before first executable statement
            if get_catch_exempt_reason(open_brace, block_end, first_code_pos, comments):
                continue

            # 2. First top-level executable statement is FatalErrors.rethrowIfFatal(var_name);
            first_stmt_match = re.match(
                rf"^\s*(?:dev\.turboism\.core\.runtime\.work\.)?(?:FatalErrors\.)?rethrowIfFatal\s*\(\s*{re.escape(var_name)}\s*\)\s*;",
                body_masked
            )
            if first_stmt_match:
                continue

            # 3. Direct unconditional sequential cleanup ending with throw var_name;
            # Must not contain early returns or branching, and must have no cast
            if has_direct_rethrow(body_masked, var_name):
                continue

            # 4. Non-runtime source preceded by a clause catching ThreadDeath and VirtualMachineError that throws
            if not is_runtime_bootstrap:
                preceding = masked_text[:match.start()]
                if has_valid_preceding_fatal(preceding):
                    continue

            line = text.count("\n", 0, match.start()) + 1
            violations.append(f"{relative}:{line}: unguarded catch ({caught_type} {var_name})")
    return violations


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("root", nargs="?", default=".")
    parser.add_argument(
        "--report",
        action="store_true",
        help="print findings and exit 0",
    )
    args = parser.parse_args()

    violations = findings(Path(args.root).resolve())
    for violation in violations:
        print(violation)
    if violations and not args.report:
        print(
            f"FAIL: {len(violations)} unguarded catch (Throwable/Error/multi-catch) site(s); each must call "
            "FatalErrors.rethrowIfFatal, follow a ThreadDeath/VirtualMachineError clause, "
            "or carry an @containment-exempt marker",
            file=sys.stderr,
        )
        return 1
    print(f"throwable-containment: {len(violations)} finding(s)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
