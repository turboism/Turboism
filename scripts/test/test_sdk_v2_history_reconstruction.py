#!/usr/bin/env python3
"""Minimal selftest for v2 historical reconstruction."""
from __future__ import annotations

import tempfile
from pathlib import Path

from reconstruct_sdk_gradle_jar import HISTORY_WORKTREE_ID, ReconstructionError, select_gradle_jar, validate_commit

def fails(action, text: str) -> None:
    try:
        action()
    except ReconstructionError as exc:
        assert text in str(exc), str(exc)
    else:
        raise AssertionError(f"expected ReconstructionError containing {text!r}")

def main() -> None:
    root = Path(__file__).resolve().parents[2]
    fails(lambda: validate_commit(root, "invalid"), "40 lowercase")
    fails(lambda: validate_commit(root, "0" * 40), "not an available Git commit")
    with tempfile.TemporaryDirectory(prefix="turboism-v2-history-selftest-") as directory:
        libraries = Path(directory) / "build" / "worktree" / HISTORY_WORKTREE_ID / "sdk" / "libs"
        libraries.mkdir(parents=True)
        fails(lambda: select_gradle_jar(libraries.parents[4]), "exactly one")
        one = libraries / "sdk.jar"
        one.write_bytes(b"jar")
        assert select_gradle_jar(libraries.parents[4]) == one
        (libraries / "extra.jar").write_bytes(b"extra")
        fails(lambda: select_gradle_jar(libraries.parents[4]), "exactly one")
    print("SDK v2 historical reconstruction selftest passed.")

if __name__ == "__main__":
    main()
