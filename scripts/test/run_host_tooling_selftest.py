#!/usr/bin/env python3
"""Run offline host tooling contracts selected by the completed-commit gate."""
from concurrent.futures import ThreadPoolExecutor
import os
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


def _run_pattern_file(pattern_file: Path) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        [sys.executable, "-m", "unittest", "discover", "-s", "scripts/test", "-p", pattern_file.name],
        cwd=ROOT,
        capture_output=True,
        text=True,
        check=False,
    )


def main() -> int:
    status = 0
    test_dir = ROOT / "scripts" / "test"
    workers = min(4, os.cpu_count() or 4)
    for pattern in PATTERNS:
        started = time.monotonic()
        matched = sorted(test_dir.glob(pattern))
        if len(matched) > 1:
            with ThreadPoolExecutor(max_workers=workers) as executor:
                for res in executor.map(_run_pattern_file, matched):
                    if res.stdout:
                        sys.stdout.write(res.stdout)
                    if res.stderr:
                        sys.stderr.write(res.stderr)
                    status = max(status, int(res.returncode != 0))
        else:
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
