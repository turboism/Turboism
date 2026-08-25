#!/usr/bin/env python3
"""Fail-closed verification for one assembled Turboism product release."""
from __future__ import annotations

import argparse
import hashlib
import re
import zipfile
from pathlib import Path


STRICT_VERSION = re.compile(r"^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$")


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def verify_sidecar(artifact: Path) -> None:
    sidecar = artifact.with_name(artifact.name + ".sha256")
    if not sidecar.is_file():
        raise ValueError(f"missing checksum sidecar: {sidecar}")
    expected = f"{sha256(artifact)}  {artifact.name}\n"
    if sidecar.read_text(encoding="utf-8") != expected:
        raise ValueError(f"invalid or non-portable checksum sidecar: {sidecar}")


def manifest_version(agent: bytes) -> str:
    with zipfile.ZipFile(__import__("io").BytesIO(agent)) as archive:
        text = archive.read("META-INF/MANIFEST.MF").decode("utf-8")
    values = {}
    current = None
    for line in text.splitlines():
        if line.startswith(" ") and current is not None:
            values[current] += line[1:]
        elif ": " in line:
            current, value = line.split(": ", 1)
            values[current] = value
    return values.get("Implementation-Version", "")


def verify_zip(path: Path, version: str, full: bool) -> None:
    with zipfile.ZipFile(path) as archive:
        names = set(archive.namelist())
        agent = archive.read("turboism-agent.jar")
        readme = archive.read("README.txt").decode("utf-8")
    plugins = {name for name in names if name.startswith("plugins/") and name.endswith(".jar")}
    if full and not plugins:
        raise ValueError(f"full archive has no plugins: {path}")
    if not full and plugins:
        raise ValueError(f"lite archive contains plugins: {path}")
    if version not in readme:
        raise ValueError(f"README does not contain release version: {path}")
    if manifest_version(agent) != version:
        raise ValueError(f"agent Implementation-Version is not {version}: {path}")


def verify(dist: Path, version: str) -> None:
    if not STRICT_VERSION.fullmatch(version):
        raise ValueError(f"invalid release version: {version}")
    expected = [
        dist / f"turboism-{version}-lite.zip",
        dist / f"turboism-{version}-full.zip",
        dist / f"TurboismInstaller-{version}.exe",
        dist / f"TurboismInstaller-{version}.jar",
    ]
    expected_names = {path.name for path in expected}
    expected_names.update(path.name + ".sha256" for path in expected)
    actual_names = {path.name for path in dist.iterdir() if path.is_file()}
    if actual_names != expected_names:
        raise ValueError(
            "release directory contents differ\n"
            f"expected: {sorted(expected_names)}\nactual: {sorted(actual_names)}"
        )
    for artifact in expected:
        if not artifact.is_file() or artifact.stat().st_size == 0:
            raise ValueError(f"missing or empty artifact: {artifact}")
        verify_sidecar(artifact)
    verify_zip(expected[0], version, full=False)
    verify_zip(expected[1], version, full=True)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--version", required=True)
    parser.add_argument("--dist", required=True, type=Path)
    args = parser.parse_args()
    try:
        verify(args.dist.resolve(), args.version)
    except (OSError, ValueError, zipfile.BadZipFile, KeyError) as failure:
        raise SystemExit(f"release verification failed: {failure}") from failure
    print(f"release verification passed: {args.version}")


if __name__ == "__main__":
    main()
