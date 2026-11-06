#!/usr/bin/env python3
"""Fail-closed ast-grep rule tests and production scans."""
from __future__ import annotations

import re
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
VERSION_LINE = re.compile(
    r"^(?:ast-grep|sg)\s+\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?$", re.IGNORECASE
)


def valid_binary(executable: str) -> bool:
    """True when `executable` reports a valid ast-grep CLI version line.

    The probe matches version lines individually instead of comparing the whole
    output: package managers may prepend install warnings on stderr (npm blocks
    lifecycle scripts by default), and unrelated binaries (such as the Unix `sg`
    group command) must not satisfy the gate.
    """
    result = subprocess.run(
        [executable, "--version"], capture_output=True, text=True, check=False
    )
    if result.returncode != 0:
        return False
    for line in (result.stdout + "\n" + result.stderr).splitlines():
        if VERSION_LINE.match(line.strip()):
            return True
    return False


def binary() -> str:
    for candidate in ("ast-grep", "sg"):
        executable = shutil.which(candidate)
        if executable is not None and valid_binary(executable):
            return executable
    sys.exit(
        "checkArchitectureRules: ast-grep is required; install npm i -g @ast-grep/cli"
    )


def main() -> int:
    executable = binary()
    for args in (
        ("test", "--config", "sgconfig.yml", "--skip-snapshot-tests"),
        ("scan", "--config", "sgconfig.yml", "--error"),
    ):
        result = subprocess.run([executable, *args], cwd=ROOT, check=False)
        if result.returncode != 0:
            return result.returncode
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
