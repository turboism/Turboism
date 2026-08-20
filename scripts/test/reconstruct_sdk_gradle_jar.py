#!/usr/bin/env python3
"""Rebuild an SDK JAR from an immutable Git commit with Gradle."""
from __future__ import annotations

import argparse
import io
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

def run(command: list[str], *, cwd: Path | None = None, env: dict[str, str] | None = None, binary: bool = False):
    try:
        result = subprocess.run(command, cwd=cwd, env=env, capture_output=True, text=not binary, check=True)
    except OSError as exc:
        raise ReconstructionError(f"could not execute {' '.join(command)}: {exc}") from exc
    except subprocess.CalledProcessError as exc:
        detail = exc.stderr or exc.stdout or (b"" if binary else "")
        if binary:
            detail = detail.decode("utf-8", "replace")
        raise ReconstructionError(
            f"command failed with exit {exc.returncode}: {' '.join(command)}\n"
            f"{detail.strip()[-4000:] or 'no output'}"
        ) from exc
    return result

def git(root: Path, *arguments: str) -> str:
    return run(["git", "--no-replace-objects", "-C", str(root), *arguments]).stdout.strip()

def validate_commit(root: Path, commit: str) -> None:
    if not COMMIT_RE.fullmatch(commit):
        raise ReconstructionError("commit must be exactly 40 lowercase hexadecimal characters")
    try:
        git(root, "cat-file", "-e", f"{commit}^{{commit}}")
    except ReconstructionError as exc:
        raise ReconstructionError(f"commit is not an available Git commit: {commit}") from exc

def extract_commit(root: Path, commit: str, destination: Path) -> Path:
    source = destination / "source"
    source.mkdir()
    archive = run(
        ["git", "--no-replace-objects", "-C", str(root), "archive", "--format=tar", commit],
        binary=True,
    ).stdout
    try:
        with tarfile.open(fileobj=io.BytesIO(archive), mode="r:") as tar:
            tar.extractall(source)
    except (OSError, tarfile.TarError) as exc:
        raise ReconstructionError(f"could not extract the pinned Git archive: {exc}") from exc
    return source

def select_gradle_jar(source: Path, worktree_id: str = HISTORY_WORKTREE_ID) -> Path:
    libraries = source / "build" / "worktree" / worktree_id / "sdk" / "libs"
    jars = sorted(path for path in libraries.glob("*.jar") if path.is_file())
    if len(jars) != 1:
        names = ", ".join(path.name for path in jars) or "none"
        raise ReconstructionError(f"expected exactly one Gradle SDK JAR in {libraries}; found {len(jars)} ({names})")
    if jars[0].stat().st_size == 0:
        raise ReconstructionError(f"Gradle SDK JAR is empty: {jars[0]}")
    return jars[0]

def publish(source: Path, output: Path) -> None:
    temporary = output.with_name(f".{output.name}.tmp")
    try:
        output.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(source, temporary)
        temporary.replace(output)
    except OSError as exc:
        raise ReconstructionError(f"could not publish {output}: {exc}") from exc
    finally:
        temporary.unlink(missing_ok=True)

def build(
    root_value: Path,
    commit: str,
    gradle_value: Path,
    output_value: Path,
    reuse_gradle_user_home: Path | None = None,
) -> Path:
    root = root_value.resolve()
    validate_commit(root, commit)
    gradle = gradle_value.resolve()
    output = output_value.resolve()
    with tempfile.TemporaryDirectory(prefix="turboism-sdk-v2-history-") as directory:
        isolated = Path(directory)
        source = extract_commit(root, commit, isolated)
        gradle_home = (
            reuse_gradle_user_home.resolve()
            if reuse_gradle_user_home is not None
            else isolated / "gradle-user-home"
        )
        if reuse_gradle_user_home is None:
            gradle_home.mkdir()
        elif not gradle_home.is_dir():
            raise ReconstructionError(f"reused Gradle user home is not a directory: {gradle_home}")
        environment = os.environ.copy()
        environment["GRADLE_USER_HOME"] = str(gradle_home)
        environment["GRADLE_OPTS"] = ""
        environment.pop("GRADLE_HOME", None)
        properties = source / "gradle" / "wrapper" / "gradle-wrapper.properties"
        try:
            wrapper = properties.read_text(encoding="utf-8")
        except OSError as exc:
            raise ReconstructionError(f"pinned Git archive has no readable Gradle wrapper properties: {properties}") from exc
        expected = WRAPPER_VERSION_RE.search(wrapper)
        if not expected:
            raise ReconstructionError(f"could not determine the pinned Gradle version from {properties}")
        actual = GRADLE_VERSION_RE.search(run([str(gradle), "--offline", "--version"], env=environment).stdout)
        if not actual or actual.group(1) != expected.group(1):
            raise ReconstructionError(
                f"Gradle version mismatch: historical wrapper requires {expected.group(1)}, "
                f"executable reported {actual.group(1) if actual else 'unknown'}"
            )
        run(
            [
                str(gradle), "--offline", "--no-daemon", "--no-build-cache", "--gradle-user-home", str(gradle_home),
                "-p", str(source), f"-PturboismWorktreeId={HISTORY_WORKTREE_ID}", ":sdk:jar", "--console=plain",
            ],
            cwd=source,
            env=environment,
        )
        publish(select_gradle_jar(source), output)
    print(f"SDK Gradle historical reconstruction passed: commit={commit} output={output}")
    return output

def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--commit", required=True)
    parser.add_argument("--gradle", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument(
        "--reuse-gradle-user-home",
        type=Path,
        help="Reuse an existing dependency/plugin cache while keeping the historical build offline.",
    )
    args = parser.parse_args()
    try:
        build(
            args.root,
            args.commit,
            args.gradle,
            args.output,
            args.reuse_gradle_user_home,
        )
    except ReconstructionError as exc:
        raise SystemExit(f"SDK Gradle historical reconstruction: {exc}") from exc

if __name__ == "__main__":
    main()
