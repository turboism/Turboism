#!/usr/bin/env python3
"""Fail-closed, pinned ast-grep rule tests and production scans."""
from __future__ import annotations

import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PINNED = "0.45.3"


def pinned(executable: str) -> bool:
    """True when `executable` reports exactly the pinned ast-grep version.

    The probe matches version lines individually instead of comparing the whole
    output: package managers may prepend install warnings on stderr (npm blocks
    lifecycle scripts by default), and that must not look like a wrong version.
    """
    result = subprocess.run(
        [executable, "--version"], capture_output=True, text=True, check=False
    )
    if result.returncode != 0:
        return False
    for line in (result.stdout + "\n" + result.stderr).splitlines():
        name, _, version = line.strip().partition(" ")
        if name.lower() in {"ast-grep", "sg"} and version.strip() == PINNED:
            return True
    return False


def binary() -> str:
    for candidate in ("ast-grep", "sg"):
        executable = shutil.which(candidate)
        if executable is not None and pinned(executable):
            return executable
    sys.exit(
        "checkArchitectureRules: ast-grep=="
        + PINNED
        + " is required; install npm i -g @ast-grep/cli@"
        + PINNED
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
