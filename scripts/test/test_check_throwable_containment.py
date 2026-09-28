#!/usr/bin/env python3
"""Self-test for check_throwable_containment.py: each compliant and violating shape."""
from __future__ import annotations

import subprocess
import sys
import tempfile
from pathlib import Path

CHECKER = Path(__file__).resolve().parent / "check_throwable_containment.py"

GUARDED_HELPER = """package dev.turboism.sample;

public final class Sample {

    void run() {
        try {
            work();
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            diagnose(failure);
        }
    }
}
"""

GUARDED_CLAUSE = """package dev.turboism.sample;

public final class Sample {

    void run() {
        try {
            work();
        } catch (ThreadDeath | VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable failure) {
            diagnose(failure);
        }
    }
}
"""

EXEMPT = """package dev.turboism.sample;

public final class Sample {

    void run() {
        try {
            work();
        } catch (Throwable failure) {
            // @containment-exempt: the merge below rethrows the highest-priority
            // failure, so a fatal still escapes instead of being swallowed.
            merge(failure);
        }
    }
}
"""

VIOLATING = """package dev.turboism.sample;

public final class Sample {

    void run() {
        try {
            work();
        } catch (Throwable failure) {
            diagnose(failure);
        }
    }
}
"""


def run(root: Path) -> subprocess.CompletedProcess:
    return subprocess.run(
        [sys.executable, str(CHECKER), str(root)],
        capture_output=True,
        text=True,
    )


def seed(root: Path, name: str, body: str) -> None:
    target = root / "runtime/src/main/java/dev/turboism/sample" / name
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(body)


def main() -> int:
    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp)
        seed(root, "Guarded.java", GUARDED_HELPER)
        seed(root, "Clause.java", GUARDED_CLAUSE)
        seed(root, "Exempt.java", EXEMPT)
        result = run(root)
        if result.returncode != 0:
            print("FAIL: compliant shapes must pass:", result.stderr, result.stdout)
            return 1

        seed(root, "Violating.java", VIOLATING)
        result = run(root)
        if result.returncode == 0:
            print("FAIL: an unguarded catch (Throwable) must fail closed")
            return 1
        if "unguarded catch (Throwable failure)" not in result.stdout:
            print("FAIL: violation must name the site:", result.stdout)
            return 1

        print("check_throwable_containment self-test passed")
        return 0


if __name__ == "__main__":
    sys.exit(main())
