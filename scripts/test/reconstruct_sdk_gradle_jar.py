#!/usr/bin/env python3
"""Reconstruct the SDK Gradle JAR from an immutable Git commit."""
from __future__ import annotations

import argparse
import hashlib
import os
import re
import shlex
import shutil
import stat
import subprocess
import tarfile
import tempfile
from pathlib import Path, PurePosixPath

COMMIT_RE = re.compile(r"[0-9a-f]{40}")
GRADLE_DISTRIBUTION_RE = re.compile(r"gradle-([0-9]+(?:\.[0-9]+)+)-(?:bin|all)\.zip")
GRADLE_VERSION_RE = re.compile(r"^Gradle\s+([^\s]+)$", re.MULTILINE)
HISTORY_WORKTREE_ID = "history-reference"


class ReconstructionError(RuntimeError):
    """Raised when historical reconstruction cannot be completed safely."""


def command_text(command: list[str]) -> str:
    return shlex.join(command)


def run_checked(command: list[str], *, cwd: Path | None = None, env: dict[str, str] | None = None) -> subprocess.CompletedProcess[str]:
    try:
        result = subprocess.run(
            command,
            cwd=cwd,
            env=env,
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            check=False,
        )
    except OSError as exc:
        raise ReconstructionError(f"could not execute {command_text(command)}: {exc}") from exc
    if result.returncode:
        detail = (result.stderr or result.stdout).strip() or "no output"
        raise ReconstructionError(
            f"command failed with exit {result.returncode}: {command_text(command)}\n{detail[-4000:]}"
        )
    return result


def git(root: Path, *arguments: str) -> str:
    result = run_checked(["git", "--no-replace-objects", "-C", str(root), *arguments])
    return result.stdout.strip()


def validate_repository_root(value: Path) -> Path:
    root = value.expanduser()
    if not root.exists() or not root.is_dir():
        raise ReconstructionError(f"repository root is not a directory: {root}")
    root = root.resolve()
    try:
        actual = Path(git(root, "rev-parse", "--show-toplevel")).resolve()
    except ReconstructionError as exc:
        raise ReconstructionError(f"repository root is not a Git worktree: {root}") from exc
    if actual != root:
        raise ReconstructionError(f"repository root must be the Git worktree root: {root} (Git reports {actual})")
    return root


def validate_commit(root: Path, commit: str) -> None:
    if not COMMIT_RE.fullmatch(commit):
        raise ReconstructionError("commit must be exactly 40 lowercase hexadecimal characters")
    try:
        git(root, "cat-file", "-e", f"{commit}^{{commit}}")
    except ReconstructionError as exc:
        raise ReconstructionError(f"commit is not an available Git commit: {commit}") from exc


def is_within(path: Path, directory: Path) -> bool:
    try:
        path.relative_to(directory)
    except ValueError:
        return False
    return True


def validate_output_path(root: Path, value: Path) -> Path:
    raw = value.expanduser()
    if not raw.is_absolute():
        raw = Path.cwd() / raw
    if raw.is_symlink():
        raise ReconstructionError(f"output must not be a symlink: {raw}")
    if raw.exists() and raw.is_dir():
        raise ReconstructionError(f"output must be a file path, not a directory: {raw}")
    if not raw.name or raw.suffix != ".jar":
        raise ReconstructionError(f"output must be a .jar file path: {raw}")
    output = raw.resolve()
    if output == root:
        raise ReconstructionError(f"output must not be the repository root: {output}")
    build_directory = (root / "build").resolve()
    if is_within(output, root) and not is_within(output, build_directory):
        raise ReconstructionError(
            f"output inside the repository must be under build/; refusing to touch worktree files: {output}"
        )
    if output.parent.exists() and not output.parent.is_dir():
        raise ReconstructionError(f"output parent is not a directory: {output.parent}")
    if is_within(output, root):
        relative = output.relative_to(root)
        result = subprocess.run(
            [
                "git",
                "--no-replace-objects",
                "-C",
                str(root),
                "ls-files",
                "--error-unmatch",
                "--full-name",
                "--",
                relative.as_posix(),
            ],
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            check=False,
        )
        if result.returncode == 0:
            raise ReconstructionError(f"refusing to overwrite tracked output: {output}")
    return output


def archive_commit(root: Path, commit: str, archive: Path) -> None:
    command = ["git", "--no-replace-objects", "-C", str(root), "archive", "--format=tar", commit]
    try:
        with archive.open("wb") as stream:
            result = subprocess.run(command, stdout=stream, stderr=subprocess.PIPE, check=False)
    except OSError as exc:
        raise ReconstructionError(f"could not create the isolated Git archive: {exc}") from exc
    if result.returncode:
        detail = result.stderr.decode("utf-8", "replace").strip() or "no output"
        raise ReconstructionError(f"command failed with exit {result.returncode}: {command_text(command)}\n{detail[-4000:]}")


def extract_archive(archive: Path, destination: Path) -> Path:
    source = destination / "source"
    source.mkdir()
    destination_root = source.resolve()
    try:
        with tarfile.open(archive, "r:") as tar:
            members = tar.getmembers()
            if not members:
                raise ReconstructionError("pinned Git archive is empty")
            for member in members:
                path = PurePosixPath(member.name)
                target = (source / member.name).resolve()
                if path.is_absolute() or ".." in path.parts or not is_within(target, destination_root):
                    raise ReconstructionError(f"Git archive contains an unsafe path: {member.name}")
                if member.issym() or member.islnk() or not (member.isdir() or member.isreg()):
                    raise ReconstructionError(f"Git archive contains an unsupported entry: {member.name}")
            tar.extractall(source)
    except (OSError, tarfile.TarError) as exc:
        raise ReconstructionError(f"could not extract the isolated Git archive: {exc}") from exc
    return source


def wrapper_gradle_version(source: Path) -> str:
    properties = source / "gradle" / "wrapper" / "gradle-wrapper.properties"
    if not properties.is_file():
        raise ReconstructionError("pinned Git archive has no Gradle wrapper properties")
    match = GRADLE_DISTRIBUTION_RE.search(properties.read_text(encoding="utf-8"))
    if not match:
        raise ReconstructionError(f"could not determine the pinned Gradle version from {properties}")
    return match.group(1)


def isolated_gradle_environment(gradle_home: Path) -> dict[str, str]:
    environment = os.environ.copy()
    environment["GRADLE_USER_HOME"] = str(gradle_home)
    environment["GRADLE_OPTS"] = ""
    environment.pop("GRADLE_HOME", None)
    return environment


def gradle_candidates(version: str) -> list[Path]:
    candidates: list[Path] = []
    if os.environ.get("GRADLE_HOME"):
        candidates.append(Path(os.environ["GRADLE_HOME"]) / "bin" / "gradle")
    path_gradle = shutil.which("gradle")
    if path_gradle:
        candidates.append(Path(path_gradle))
    homes: list[Path] = []
    if os.environ.get("GRADLE_USER_HOME"):
        homes.append(Path(os.environ["GRADLE_USER_HOME"]).expanduser())
    homes.append(Path.home() / ".gradle")
    for home in homes:
        wrapper_root = home / "wrapper" / "dists"
        for pattern in (f"gradle-{version}-bin/**/bin/gradle", f"gradle-{version}-all/**/bin/gradle"):
            candidates.extend(sorted(wrapper_root.glob(pattern)))
    unique: list[Path] = []
    seen: set[Path] = set()
    for candidate in candidates:
        try:
            resolved = candidate.resolve()
        except OSError:
            continue
        if resolved not in seen and resolved.is_file() and os.access(resolved, os.X_OK):
            seen.add(resolved)
            unique.append(resolved)
    return unique


def find_gradle(version: str, gradle_home: Path) -> Path:
    errors: list[str] = []
    environment = isolated_gradle_environment(gradle_home)
    for candidate in gradle_candidates(version):
        command = [str(candidate), "--offline", "--no-daemon", "--gradle-user-home", str(gradle_home), "--version"]
        try:
            result = run_checked(command, env=environment)
        except ReconstructionError as exc:
            errors.append(str(exc))
            continue
        match = GRADLE_VERSION_RE.search(result.stdout)
        if match and match.group(1) == version:
            return candidate
        errors.append(f"{candidate} reported {match.group(1) if match else 'an unknown version'}")
    detail = "; ".join(errors[-3:])
    suffix = f" Checked candidates: {detail}" if detail else ""
    raise ReconstructionError(f"Gradle {version} is not available for offline reconstruction.{suffix}")


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
    output.parent.mkdir(parents=True, exist_ok=True)
    temporary_name: str | None = None
    try:
        descriptor, temporary_name = tempfile.mkstemp(prefix=f".{output.name}.", suffix=".tmp", dir=output.parent)
        with os.fdopen(descriptor, "wb") as destination:
            with source.open("rb") as input_file:
                shutil.copyfileobj(input_file, destination)
            destination.flush()
            os.fsync(destination.fileno())
        os.chmod(temporary_name, stat.S_IMODE(source.stat().st_mode))
        os.replace(temporary_name, output)
        temporary_name = None
        try:
            directory_descriptor = os.open(output.parent, os.O_RDONLY)
            try:
                os.fsync(directory_descriptor)
            finally:
                os.close(directory_descriptor)
        except OSError:
            pass
    except OSError as exc:
        raise ReconstructionError(f"could not atomically publish {output}: {exc}") from exc
    finally:
        if temporary_name:
            try:
                os.unlink(temporary_name)
            except FileNotFoundError:
                pass


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def build(root_value: Path, commit: str, output_value: Path) -> Path:
    root = validate_repository_root(root_value)
    validate_commit(root, commit)
    output = validate_output_path(root, output_value)
    with tempfile.TemporaryDirectory(prefix="turboism-sdk-v2-history-") as directory:
        isolated = Path(directory)
        archive = isolated / "commit.tar"
        archive_commit(root, commit, archive)
        source = extract_archive(archive, isolated)
        gradle_home = isolated / "gradle-user-home"
        project_cache = isolated / "project-cache"
        gradle_home.mkdir()
        project_cache.mkdir()
        gradle_version = wrapper_gradle_version(source)
        gradle = find_gradle(gradle_version, gradle_home)
        environment = isolated_gradle_environment(gradle_home)
        command = [
            str(gradle),
            "--offline",
            "--no-daemon",
            "--no-build-cache",
            "--gradle-user-home",
            str(gradle_home),
            "--project-cache-dir",
            str(project_cache),
            "-p",
            str(source),
            f"-PturboismWorktreeId={HISTORY_WORKTREE_ID}",
            ":sdk:jar",
            "--console=plain",
        ]
        run_checked(command, cwd=source, env=environment)
        jar = select_gradle_jar(source)
        copy_atomically(jar, output)
    print(f"SDK Gradle historical reconstruction passed: commit={commit} output={output} sha256={sha256_file(output)} size={output.stat().st_size}")
    return output


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--commit", required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    try:
        build(args.root, args.commit, args.output)
    except ReconstructionError as exc:
        raise SystemExit(f"SDK Gradle historical reconstruction: {exc}") from exc


if __name__ == "__main__":
    main()
