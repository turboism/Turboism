#!/usr/bin/env python3
"""Enforce the single Throwable-containment policy in production sources.

Every ``catch (Throwable <var>)`` site must make its fatal-vs-containable decision through
one auditable mechanism instead of re-deciding per call site. A site is compliant when it

- opens its block with ``FatalErrors.rethrowIfFatal(<var>)`` (runtime/bootstrap sources), or
- is preceded in the same catch chain by a clause naming ``ThreadDeath``/``VirtualMachineError``
  (sources that cannot see runtime internals), or
- carries an inline ``@containment-exempt: <reason>`` marker in the block head when the catch
  itself already propagates fatal failures correctly (e.g. a priority-merge rethrow).

Sites are located with a regex over source text; a ``catch (Throwable`` inside a line or block
comment still counts, because containing real JVM fatals in dead code documents the wrong
policy and the marker escape exists precisely for reviewed exceptions.

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

CATCH_RE = re.compile(r"catch\s*\(\s*Throwable\s+(\w+)\s*\)\s*\{")
PRECEDING_FATAL_RE = re.compile(
    r"catch\s*\([^)]*\b(?:ThreadDeath|VirtualMachineError)\b[^)]*\)\s*\w*\s*\{[^{}]*\}\s*$"
)
EXEMPT_MARKER = "@containment-exempt"
# How far into the block rethrowIfFatal / the marker may appear. Keeping the window tight
# forces the guard to stay the block's first statement instead of drifting into noise.
BLOCK_HEAD = 220


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
        for match in CATCH_RE.finditer(text):
            head = text[match.end():match.end() + BLOCK_HEAD]
            if "rethrowIfFatal(" in head:
                continue
            if EXEMPT_MARKER in head or EXEMPT_MARKER in text[max(0, match.start() - 160):match.start()]:
                continue
            preceding = text[:match.start()]
            if PRECEDING_FATAL_RE.search(preceding):
                continue
            line = text.count("\n", 0, match.start()) + 1
            violations.append(f"{relative}:{line}: unguarded catch (Throwable {match.group(1)})")
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
            f"FAIL: {len(violations)} unguarded catch (Throwable) site(s); each must call "
            "FatalErrors.rethrowIfFatal, follow a ThreadDeath/VirtualMachineError clause, "
            "or carry an @containment-exempt marker",
            file=sys.stderr,
        )
        return 1
    print(f"throwable-containment: {len(violations)} finding(s)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
