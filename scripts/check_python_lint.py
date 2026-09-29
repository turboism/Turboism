#!/usr/bin/env python3
"""Fail-closed Python lint gate for scripts/.

Runs `ruff check` against the repo's ruff.toml. The pinned release keeps results
identical across machines — a different ruff version fails with an install hint
rather than silently widening or narrowing the rule set.

Install:  python3 -m pip install "ruff==0.16.9"
"""
from __future__ import annotations

import shutil
import subprocess
import sys
from pathlib import Path
from typing import NoReturn

ROOT = Path(__file__).resolve().parents[1]
PINNED_RUFF = "0.16.9"


def fail(message: str) -> NoReturn:
    print(f"checkPythonLint: {message}", file=sys.stderr)
    raise SystemExit(1)


def ruff_command() -> list[str]:
    binary = shutil.which("ruff")
    if binary:
        return [binary]
    probe = subprocess.run(
        [sys.executable, "-m", "ruff", "--version"],
        capture_output=True,
        text=True,
    )
    if probe.returncode == 0:
        return [sys.executable, "-m", "ruff"]
    fail(f"ruff=={PINNED_RUFF} not found on PATH; install with: "
         f"python3 -m pip install \"ruff=={PINNED_RUFF}\"")
    return []  # unreachable


def main() -> int:
    command = ruff_command()
    version = subprocess.run(command + ["--version"], capture_output=True, text=True)
    observed = version.stdout.strip().removeprefix("ruff ")
    if observed != PINNED_RUFF:
        fail(f"ruff {observed} found, expected {PINNED_RUFF}; "
             f"install with: python3 -m pip install \"ruff=={PINNED_RUFF}\"")
    result = subprocess.run(
        command + ["check", "--config", str(ROOT / "ruff.toml"), "scripts/"],
        cwd=ROOT,
    )
    return result.returncode


if __name__ == "__main__":
    raise SystemExit(main())
