#!/usr/bin/env python3
"""Negative self-test for check_plugin_sections.py.

`plugin-sections.nsh` is a generated artifact tracked in Git. This self-test
proves the gate detects the failure modes that used to go unnoticed:

  1. the tracked artifact matches a fresh regeneration (pass path)
  2. a stale version string in the tracked artifact fails the gate
  3. a plugin dropped from the tracked artifact fails the gate
  4. an assembler without the production generator block fails the gate

All mutations happen on temporary copies; the tracked artifact is never modified.
"""

from __future__ import annotations

import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

PACKAGE = Path(__file__).resolve().parent
GATE = PACKAGE / "check_plugin_sections.py"
NSH = PACKAGE / "plugin-sections.nsh"
ASSEMBLE = PACKAGE / "assemble-release.sh"


def run_gate(*extra: str) -> subprocess.CompletedProcess:
    return subprocess.run(
        [sys.executable, str(GATE), *extra],
        text=True,
        capture_output=True,
    )


def check(name: str, result: subprocess.CompletedProcess, expect_success: bool, expect_text: str) -> bool:
    combined = result.stdout + result.stderr
    if expect_success:
        ok = result.returncode == 0
        detail = "expected exit 0"
    else:
        ok = result.returncode != 0
        detail = "expected non-zero exit"
    if ok and expect_text not in combined:
        ok = False
        detail = f"expected output containing {expect_text!r}"
    print(f"{'ok' if ok else 'FAIL'}: {name}" + ("" if ok else f" ({detail}, exit {result.returncode})"))
    if not ok:
        print(combined[-2000:], file=sys.stderr)
    return ok


def main() -> int:
    results: list[bool] = []
    with tempfile.TemporaryDirectory(prefix="turboism-plugin-sections-selftest-") as temp:
        work = Path(temp)

        results.append(check("current tracked artifact is up to date", run_gate(), True, "matches a fresh regeneration"))

        stale = work / "stale.nsh"
        text = NSH.read_text(encoding="utf-8-sig")
        if "0.68.1" not in text:
            print("FAIL: fixture precondition: expected framework version in tracked artifact", file=sys.stderr)
            return 1
        stale.write_text(text.replace("0.68.1", "0.68.0"), encoding="utf-8-sig")
        results.append(check("stale version is rejected", run_gate("--nsh", str(stale)), False, "is stale"))

        pruned = work / "pruned.nsh"
        marker = 'File "/oname=acp.jar"'
        lines = [line for line in text.splitlines() if marker not in line]
        if len(lines) == len(text.splitlines()):
            print("FAIL: fixture precondition: expected an acp payload line", file=sys.stderr)
            return 1
        pruned.write_text("\n".join(lines) + "\n", encoding="utf-8-sig")
        results.append(check("dropped plugin payload is rejected", run_gate("--nsh", str(pruned)), False, "is stale"))

        no_generator = work / "assemble-release.sh"
        no_generator.write_text(
            (ASSEMBLE.read_text(encoding="utf-8").split("<<'PYEOF'")[0]),
            encoding="utf-8",
        )
        results.append(
            check(
                "assembler without the generator block fails closed",
                run_gate("--assemble", str(no_generator)),
                False,
                "must keep exactly two PYEOF blocks",
            )
        )

        shutil.rmtree(work, ignore_errors=True)

    passed = sum(1 for result in results if result)
    print(f"plugin-sections gate self-test: {passed}/{len(results)} cases passed")
    return 0 if passed == len(results) else 1


if __name__ == "__main__":
    raise SystemExit(main())
