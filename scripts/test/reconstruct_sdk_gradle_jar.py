#!/usr/bin/env python3
"""Rebuild an SDK JAR from an immutable Git commit with Gradle."""
from __future__ import annotations

import argparse
import os
import re
import shutil
import subprocess
import tarfile
import tempfile
from pathlib import Path

COMMIT_RE = re.compile(r"[0-9a-f]{40}")
WRAPPER_VERSION_RE = re.compile(r"gradle-([0-9]+(?:\.[0-9]+)+)-(?:bin|all)\.zip")
GRADLE_VERSION_RE = re.compile(r"^Gradle\s+([^\s]+)$", re.MULTILINE)
HISTORY_WORKTREE_ID = "history-reference"


class ReconstructionError(RuntimeError):
    pass


def run(
    command: list[str],
    *,
    cwd: Path | None = None,
    env: dict[str, str] | None = None,
    stdout=None,
):
    capture = stdout is None
    try:
        result = subprocess.run(
            command,
            cwd=cwd,
            env=env,
            stdout=subprocess.PIPE if capture else stdout,
            stderr=subprocess.PIPE,
            text=capture,
            check=True,
        )
    except OSError as exc:
        raise ReconstructionError(f"could not execute {' '.join(command)}: {exc}") from exc
    except subprocess.CalledProcessError as exc:
        detail = exc.stderr if capture else (exc.stderr or b"").decode("utf-8", "replace")
        raise ReconstructionError(
            f"command failed with exit {exc.returncode}: {' '.join(command)}\n"
            f"{detail.strip()[-4000:] or 'no output'}"
        ) from exc
    return result


def git(root: Path, *arguments: str) -> str:
    return run(["git", "--no-replace-objects", "-C", str(root), *arguments]).stdout.strip()


def validate_repository_root(value: Path) -> Path:
    root = value.expanduser().resolve()
    try:
        actual = Path(git(root, "rev-parse", "--show-toplevel")).resolve()
    except ReconstructionError as exc:
        raise ReconstructionError(f"repository root is not a Git worktree: {root}") from exc
    if actual != root:
        raise ReconstructionError(f"repository root must be the Git worktree root: {root}")
    return root


def validate_commit(root: Path, commit: str) -> None:
    if not COMMIT_RE.fullmatch(commit):
        raise ReconstructionError("commit must be exactly 40 lowercase hexadecimal characters")
    try:
        git(root, "cat-file", "-e", f"{commit}^{{commit}}")
    except ReconstructionError as exc:
        raise ReconstructionError(f"commit is not an available Git commit: {commit}") from exc


def validate_output_path(root: Path, value: Path) -> Path:
    raw = value.expanduser()
    if not raw.is_absolute():
        raw = Path.cwd() / raw
    if raw.is_symlink():
        raise ReconstructionError(f"output must not be a symlink: {raw}")
    if raw.exists() and raw.is_dir():
        raise ReconstructionError(f"output must be a file path: {raw}")
    if raw.suffix != ".jar":
        raise ReconstructionError(f"output must be a .jar file path: {raw}")

    output = raw.resolve()
    build = (root / "build").resolve()
    if output.is_relative_to(root) and not output.is_relative_to(build):
        raise ReconstructionError(f"output inside the repository must be under build/: {output}")
    if output.is_relative_to(root):
        relative = output.relative_to(root).as_posix()
        try:
            git(root, "ls-files", "--error-unmatch", "--", relative)
        except ReconstructionError:
            pass
        else:
            raise ReconstructionError(f"refusing to overwrite tracked output: {output}")
    return output


def extract_commit(root: Path, commit: str, destination: Path) -> Path:
    source = destination / "source"
    source.mkdir()
    archive = destination / "commit.tar"
    with archive.open("wb") as stream:
        run(
            ["git", "--no-replace-objects", "-C", str(root), "archive", "--format=tar", commit],
            stdout=stream,
        )
    with tarfile.open(archive, "r:") as tar:
        tar.extractall(source)
    return source


def wrapper_gradle_version(source: Path) -> str:
    properties = source / "gradle" / "wrapper" / "gradle-wrapper.properties"
    try:
        text = properties.read_text(encoding="utf-8")
    except OSError as exc:
        raise ReconstructionError(f"pinned Git archive has no readable Gradle wrapper properties: {properties}") from exc
    match = WRAPPER_VERSION_RE.search(text)
    if not match:
        raise ReconstructionError(f"could not determine the pinned Gradle version from {properties}")
    return match.group(1)

def select_gradle_jar(source: Path, worktree_id: str = HISTORY_WORKTREE_ID) -> Path:
    libraries = source / "build" / "worktree" / worktree_id / "sdk" / "libs"
    if not libraries.is_dir():
        raise ReconstructionError(f"Gradle SDK output directory is missing: {libraries}")
    candidates = sorted(
        path for path in libraries.iterdir() if path.suffix == ".jar" and path.is_file() and not path.is_symlink()
    )
    if len(candidates) != 1:
        names = ", ".join(path.name for path in candidates) or "none"
        raise ReconstructionError(f"expected exactly one Gradle SDK JAR in {libraries}; found {len(candidates)} ({names})")
    if candidates[0].stat().st_size == 0:
        raise ReconstructionError(f"Gradle SDK JAR is empty: {candidates[0]}")
    return candidates[0]


def copy_atomically(source: Path, output: Path) -> None:
    temporary: Path | None = None
    try:
        output.parent.mkdir(parents=True, exist_ok=True)
        descriptor, name = tempfile.mkstemp(prefix=f".{output.name}.", suffix=".tmp", dir=output.parent)
        temporary = Path(name)
        os.close(descriptor)
        shutil.copyfile(source, temporary)
        temporary.replace(output)
    except OSError as exc:
        raise ReconstructionError(f"could not publish {output}: {exc}") from exc
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)


def build(root_value: Path, commit: str, gradle_value: Path, output_value: Path) -> Path:
    root = validate_repository_root(root_value)
    validate_commit(root, commit)
    gradle = gradle_value.expanduser().resolve()
    if not gradle.is_file() or not os.access(gradle, os.X_OK):
        raise ReconstructionError(f"Gradle executable is not available: {gradle}")
    output = validate_output_path(root, output_value)
    with tempfile.TemporaryDirectory(prefix="turboism-sdk-v2-history-") as directory:
        isolated = Path(directory)
        source = extract_commit(root, commit, isolated)
        gradle_home = isolated / "gradle-user-home"
        gradle_home.mkdir()
        environment = os.environ.copy()
        environment["GRADLE_USER_HOME"] = str(gradle_home)
        environment["GRADLE_OPTS"] = ""
        environment.pop("GRADLE_HOME", None)
        expected_version = wrapper_gradle_version(source)
        actual = GRADLE_VERSION_RE.search(
            run([str(gradle), "--offline", "--version"], env=environment).stdout
        )
        if not actual or actual.group(1) != expected_version:
            raise ReconstructionError(
                f"Gradle version mismatch: historical wrapper requires {expected_version}, "
                f"executable reported {actual.group(1) if actual else 'unknown'}"
            )
        run(
            [
                str(gradle),
                "--offline",
                "--no-daemon",
                "--no-build-cache",
                "--gradle-user-home",
                str(gradle_home),
                "-p",
                str(source),
                f"-PturboismWorktreeId={HISTORY_WORKTREE_ID}",
                ":sdk:jar",
                "--console=plain",
            ],
            cwd=source,
            env=environment,
        )
        copy_atomically(select_gradle_jar(source), output)
    print(f"SDK Gradle historical reconstruction passed: commit={commit} output={output}")
    return output


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--commit", required=True)
    parser.add_argument("--gradle", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    try:
        build(args.root, args.commit, args.gradle, args.output)
    except ReconstructionError as exc:
        raise SystemExit(f"SDK Gradle historical reconstruction: {exc}") from exc


if __name__ == "__main__":
    main()
