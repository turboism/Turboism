#!/usr/bin/env python3
"""Run offline host tooling contracts selected by the completed-commit gate."""
from pathlib import Path
import subprocess
import sys
import time

ROOT = Path(__file__).resolve().parents[2]
PATTERNS = (
    "test_host_validation_*.py",
    "test_external_psd_*.py",
    "test_cubism_version_matrix_*.py",
    "test_cubism_host_validation_*.py",
    "test_cleanup_host_artifacts.py",
    "test_turboism_dev.py",
    "test_plugin_package_builder.py",
    "test_host_task_processes.py",
)
SHELL_TESTS = (
    "test_worktree_id_resolution.sh",
    "test_worktree_artifact_boundary.sh",
)


def main() -> int:
    status = 0
    for pattern in PATTERNS:
        started = time.monotonic()
        result = subprocess.run(
            [sys.executable, "-m", "unittest", "discover", "-s", "scripts/test", "-p", pattern],
            cwd=ROOT, check=False,
        )
        status = max(status, int(result.returncode != 0))
        print(f"TIMING {pattern}: {time.monotonic() - started:.1f}s", flush=True)
    for name in SHELL_TESTS:
        started = time.monotonic()
        result = subprocess.run(["bash", str(ROOT / "scripts/test" / name)], cwd=ROOT, check=False)
        status = max(status, int(result.returncode != 0))
        print(f"TIMING {name}: {time.monotonic() - started:.1f}s", flush=True)
    return status


if __name__ == "__main__":
    raise SystemExit(main())
