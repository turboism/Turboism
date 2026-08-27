#!/usr/bin/env python3
"""Minimal selftest for v2 historical reconstruction."""
from __future__ import annotations

import tempfile
from pathlib import Path
from types import SimpleNamespace
from unittest import mock

import reconstruct_sdk_gradle_jar as reconstruction
from reconstruct_sdk_gradle_jar import HISTORY_WORKTREE_ID, ReconstructionError, select_gradle_jar, validate_commit

def fails(action, text: str) -> None:
    try:
        action()
    except ReconstructionError as exc:
        assert text in str(exc), str(exc)
    else:
        raise AssertionError(f"expected ReconstructionError containing {text!r}")

def cache_reuse_keeps_source_and_output_isolated() -> None:
    commit = "1" * 40
    commands: list[tuple[list[str], Path | None, dict[str, str] | None]] = []
    with tempfile.TemporaryDirectory(prefix="turboism-v2-cache-selftest-") as directory:
        fixture = Path(directory)
        root = fixture / "repository"
        root.mkdir()
        gradle = fixture / "gradle"
        gradle.write_text("unused", encoding="utf-8")
        cache = fixture / "gradle-user-home"
        cache.mkdir()
        # A mutable cache artifact must never be accepted as the reconstructed output.
        (cache / "sdk.jar").write_bytes(b"cache-jar")
        output = fixture / "reference.jar"

        def extract(root_value: Path, commit_value: str, destination: Path) -> Path:
            assert root_value == root.resolve()
            assert commit_value == commit
            source = destination / "source"
            properties = source / "gradle" / "wrapper" / "gradle-wrapper.properties"
            properties.parent.mkdir(parents=True)
            properties.write_text(
                "distributionUrl=https://services.gradle.org/distributions/gradle-8.10.2-bin.zip\n",
                encoding="utf-8",
            )
            return source

        def run(command, *, cwd=None, env=None, binary=False):
            assert not binary
            commands.append((list(command), cwd, env))
            if "--version" in command:
                return SimpleNamespace(stdout="Gradle 8.10.2\n")
            source = Path(command[command.index("-p") + 1])
            libraries = source / "build" / "worktree" / HISTORY_WORKTREE_ID / "sdk" / "libs"
            libraries.mkdir(parents=True)
            (libraries / "sdk.jar").write_bytes(b"historical-jar")
            return SimpleNamespace(stdout="")

        with (
            mock.patch.object(reconstruction, "validate_commit") as validate,
            mock.patch.object(reconstruction, "extract_commit", side_effect=extract),
            mock.patch.object(reconstruction, "run", side_effect=run),
        ):
            assert reconstruction.build(root, commit, gradle, output, cache) == output.resolve()

        validate.assert_called_once_with(root.resolve(), commit)
        assert output.read_bytes() == b"historical-jar"
        assert output.read_bytes() != (cache / "sdk.jar").read_bytes()
        version_command, _, version_env = commands[0]
        build_command, build_cwd, build_env = commands[1]
        assert version_command[1:3] == ["--offline", "--version"]
        assert "--offline" in build_command
        assert "--no-build-cache" in build_command
        assert build_command[build_command.index("--gradle-user-home") + 1] == str(cache.resolve())
        assert build_command[-2:] == [":sdk:jar", "--console=plain"]
        assert build_cwd != cache.resolve()
        assert version_env is not None and version_env["GRADLE_USER_HOME"] == str(cache.resolve())
        assert build_env is not None and build_env["GRADLE_USER_HOME"] == str(cache.resolve())


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
    cache_reuse_keeps_source_and_output_isolated()
    print("SDK v2 historical reconstruction selftest passed.")

if __name__ == "__main__":
    main()
