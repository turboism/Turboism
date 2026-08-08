#!/usr/bin/env python3
"""Small selftest for the v2 historical reconstruction helper."""
from __future__ import annotations

import tempfile
from pathlib import Path

from reconstruct_sdk_gradle_jar import COMMIT_RE, HISTORY_WORKTREE_ID, ReconstructionError, copy_atomically, select_gradle_jar, validate_commit, validate_output_path


def fails(action, text: str) -> None:
    try:
        action()
    except ReconstructionError as exc:
        assert text in str(exc), str(exc)
    else:
        raise AssertionError(f"expected ReconstructionError containing {text!r}")


def main() -> None:
    assert COMMIT_RE.fullmatch("0" * 40) and not COMMIT_RE.fullmatch("0" * 39) and not COMMIT_RE.fullmatch("A" * 40)
    fails(lambda: validate_commit(Path(__file__).resolve().parents[2], "0" * 40), "not an available Git commit")
    with tempfile.TemporaryDirectory(prefix="turboism-v2-history-selftest-") as directory:
        root = Path(directory)
        (root / "build").mkdir()
        output = validate_output_path(root, root / "build" / "reference.jar")
        assert output == root / "build" / "reference.jar"
        fails(lambda: validate_output_path(root, root / "docs" / "reference.jar"), "under build/")
        fails(lambda: validate_output_path(root, root / "build" / "reference.zip"), ".jar")
        libraries = root / "source" / "build" / "worktree" / HISTORY_WORKTREE_ID / "sdk" / "libs"
        fails(lambda: select_gradle_jar(root / "source"), "missing")
        libraries.mkdir(parents=True)
        selected = libraries / "sdk-history.jar"
        selected.write_bytes(b"jar")
        assert select_gradle_jar(root / "source") == selected
        (libraries / "extra.jar").write_bytes(b"extra")
        fails(lambda: select_gradle_jar(root / "source"), "exactly one")
        published = root / "build" / "published.jar"
        copy_atomically(selected, published)
        assert published.read_bytes() == b"jar" and not list(published.parent.glob(f".{published.name}.*.tmp"))
    print("SDK v2 historical reconstruction selftest passed.")


if __name__ == "__main__":
    main()
