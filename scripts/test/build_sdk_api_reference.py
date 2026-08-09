#!/usr/bin/env python3
"""Build a deterministic SDK reference JAR from an immutable Git commit."""
from __future__ import annotations

import argparse
import re
import subprocess
import tempfile
import zipfile
from pathlib import Path

COMMIT_RE = re.compile(r"[0-9a-f]{40}")
SOURCE_ROOT = "sdk/src/main/java/"


class ReferenceBuildError(RuntimeError):
    pass


def run(command: list[str], *, cwd: Path | None = None) -> bytes:
    try:
        return subprocess.check_output(command, cwd=cwd, stderr=subprocess.STDOUT)
    except subprocess.CalledProcessError as exc:
        output = exc.output.decode("utf-8", "replace").strip()
        raise ReferenceBuildError(
            f"command failed ({' '.join(command)}): {output or 'no output'}"
        ) from exc


def git(root: Path, *arguments: str) -> bytes:
    return run(["git", "--no-replace-objects", "-C", str(root), *arguments])


def source_paths(root: Path, commit: str) -> list[str]:
    git(root, "cat-file", "-e", f"{commit}^{{commit}}")
    output = git(
        root,
        "ls-tree",
        "-r",
        "--name-only",
        commit,
        "--",
        SOURCE_ROOT,
    ).decode("utf-8")
    paths = sorted(line for line in output.splitlines() if line.endswith(".java"))
    if not paths:
        raise ReferenceBuildError("anchor commit contains no SDK Java sources")
    if any(not path.startswith(SOURCE_ROOT) for path in paths):
        raise ReferenceBuildError("Git returned a path outside the SDK source root")
    return paths


def extract_sources(root: Path, commit: str, destination: Path) -> list[Path]:
    extracted: list[Path] = []
    for repository_path in source_paths(root, commit):
        relative = repository_path.removeprefix(SOURCE_ROOT)
        target = destination / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(git(root, "show", f"{commit}:{repository_path}"))
        extracted.append(target)
    return extracted


def compile_sources(sources: list[Path], classes: Path) -> None:
    classes.mkdir(parents=True, exist_ok=True)
    run(
        [
            "javac",
            "--release",
            "17",
            "-encoding",
            "UTF-8",
            "-d",
            str(classes),
            *(str(source) for source in sources),
        ]
    )


def write_deterministic_jar(classes: Path, output: Path) -> None:
    class_files = sorted(classes.rglob("*.class"))
    if not class_files:
        raise ReferenceBuildError("SDK reference compilation produced no classes")
    output.parent.mkdir(parents=True, exist_ok=True)
    temporary = output.with_suffix(output.suffix + ".tmp")
    with zipfile.ZipFile(temporary, "w") as archive:
        for class_file in class_files:
            relative = class_file.relative_to(classes).as_posix()
            entry = zipfile.ZipInfo(relative, date_time=(1980, 1, 1, 0, 0, 0))
            entry.compress_type = zipfile.ZIP_STORED
            entry.create_system = 3
            entry.external_attr = 0o100644 << 16
            archive.writestr(entry, class_file.read_bytes())
    temporary.replace(output)


def build(root: Path, commit: str, output: Path) -> None:
    if not COMMIT_RE.fullmatch(commit):
        raise ReferenceBuildError(
            "commit must be exactly 40 lowercase hexadecimal characters"
        )
    with tempfile.TemporaryDirectory(prefix="turboism-sdk-reference-") as directory:
        temporary = Path(directory)
        sources = extract_sources(root, commit, temporary / "src")
        classes = temporary / "classes"
        compile_sources(sources, classes)
        write_deterministic_jar(classes, output)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--commit", required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    try:
        build(args.root.resolve(), args.commit, args.output.resolve())
    except ReferenceBuildError as exc:
        raise SystemExit(f"SDK API reference build: {exc}") from exc


if __name__ == "__main__":
    main()
