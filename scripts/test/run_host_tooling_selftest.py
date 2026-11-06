#!/usr/bin/env python3
"""Run offline host tooling contracts selected by the completed-commit gate."""
from concurrent.futures import ThreadPoolExecutor
import os
from pathlib import Path
import re
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
    "test_run_host_tooling_selftest.py",
)
SHELL_TESTS = (
    "test_worktree_id_resolution.sh",
    "test_worktree_artifact_boundary.sh",
)
# unittest reports its own count on stderr ("Ran 3 tests in 0.12s") and older
# interpreters still exit 0 when the count is zero, so the runner reads it back.
SUMMARY = re.compile(r"^Ran (\d+) tests? in ", re.MULTILINE)


def _run_pattern_file(pattern_file: Path) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        [sys.executable, "-m", "unittest", "discover", "-s", "scripts/test", "-p", pattern_file.name],
        cwd=ROOT,
        capture_output=True,
        text=True,
        check=False,
    )


def _tests_run(result: subprocess.CompletedProcess[str]) -> int | None:
    """Tests unittest reported, or None when it never produced a summary."""
    match = SUMMARY.search(result.stderr or "")
    return int(match.group(1)) if match else None


def _report(result: subprocess.CompletedProcess[str], label: str) -> int:
    """Forward one capture and fail closed when the run proved nothing."""
    if result.stdout:
        sys.stdout.write(result.stdout)
    if result.stderr:
        sys.stderr.write(result.stderr)
    if result.returncode != 0:
        return 1
    ran = _tests_run(result)
    if not ran:
        print(f"FAIL {label}: unittest ran no test", flush=True)
        return 1
    return 0


def main() -> int:
    status = 0
    test_dir = ROOT / "scripts" / "test"
    workers = min(4, os.cpu_count() or 4)
    for pattern in PATTERNS:
        started = time.monotonic()
        matched = sorted(test_dir.glob(pattern))
        if not matched:
            # A pattern that selects nothing must not pass as "no findings".
            print(f"FAIL {pattern}: no test file matches the pattern", flush=True)
            status = 1
        elif len(matched) > 1:
            with ThreadPoolExecutor(max_workers=workers) as executor:
                for res in executor.map(_run_pattern_file, matched):
                    status = max(status, _report(res, pattern))
        else:
            status = max(status, _report(_run_pattern_file(matched[0]), pattern))
        print(f"TIMING {pattern}: {time.monotonic() - started:.1f}s", flush=True)
    for name in SHELL_TESTS:
        started = time.monotonic()
        result = subprocess.run(["bash", str(ROOT / "scripts/test" / name)], cwd=ROOT, check=False)
        status = max(status, int(result.returncode != 0))
        print(f"TIMING {name}: {time.monotonic() - started:.1f}s", flush=True)
    return status


if __name__ == "__main__":
    raise SystemExit(main())
