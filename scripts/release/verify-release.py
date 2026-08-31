#!/usr/bin/env python3
"""Fail-closed verification for one assembled Turboism product release."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import tempfile
import zipfile
from pathlib import Path


STRICT_VERSION = re.compile(r"^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$")
FX_RUNTIME_ROOT = "runtimes/fx/0.0.5/windows-x86_64/"
FX_RUNTIME_FILES = {
    FX_RUNTIME_ROOT + "fx.exe",
    FX_RUNTIME_ROOT + "LICENSE",
    FX_RUNTIME_ROOT + "THIRD_PARTY_NOTICES.md",
    FX_RUNTIME_ROOT + "TURBOISM-DISTRIBUTION-NOTICE.txt",
    FX_RUNTIME_ROOT + "manifest.properties",
}
FX_RUNTIME_SIZE = 11_174_912
FX_RUNTIME_SHA256 = "04eca2ccb0037d4080724ad644cb42a2605f610632e0e95148f077e1550c4541"


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


def release_plugin_modules(path: Path) -> set[str]:
    lines = path.read_text(encoding="utf-8").splitlines()
    if not lines or any(not line or line.startswith("#") for line in lines):
        raise ValueError(f"release plugin roster contains blank/comment lines: {path}")
    if lines != sorted(lines) or len(lines) != len(set(lines)):
        raise ValueError(f"release plugin roster is not unique ASCII order: {path}")
    pattern = re.compile(r"^:plugins:([a-z0-9-]+)$")
    modules = []
    for line in lines:
        match = pattern.fullmatch(line)
        if match is None:
            raise ValueError(f"invalid release plugin roster entry: {line}")
        if match.group(1) != "core":
            modules.append(match.group(1))
    if not modules:
        raise ValueError("release plugin roster has no packaged plugins")
    return set(modules)


def verify_zip(path: Path, version: str, full: bool, plugins: set[str]) -> None:
    with zipfile.ZipFile(path) as archive:
        names = set(archive.namelist())
        agent = archive.read("turboism-agent.jar")
        readme = archive.read("README.txt").decode("utf-8")
    packaged = {
        name.removeprefix("plugins/").removesuffix(".jar")
        for name in names
        if name.startswith("plugins/") and name.endswith(".jar")
    }
    if full and packaged != plugins:
        raise ValueError(
            f"full archive plugin roster differs: {path}\n"
            f"expected: {sorted(plugins)}\nactual: {sorted(packaged)}"
        )
    if not full and packaged:
        raise ValueError(f"lite archive contains plugins: {path}")
    fx_names = {name for name in names if name.startswith("runtimes/fx/")}
    if full:
        if fx_names != FX_RUNTIME_FILES:
            raise ValueError(
                f"full archive managed fx inventory differs: {path}\n"
                f"expected: {sorted(FX_RUNTIME_FILES)}\nactual: {sorted(fx_names)}"
            )
        with zipfile.ZipFile(path) as archive:
            executable = archive.read(FX_RUNTIME_ROOT + "fx.exe")
        if (len(executable) != FX_RUNTIME_SIZE
                or hashlib.sha256(executable).hexdigest() != FX_RUNTIME_SHA256):
            raise ValueError(f"full archive managed fx identity differs: {path}")
    elif fx_names:
        raise ValueError(f"lite archive contains managed fx runtime bytes: {path}")
    if version not in readme:
        raise ValueError(f"README does not contain release version: {path}")
    if manifest_version(agent) != version:
        raise ValueError(f"agent Implementation-Version is not {version}: {path}")


def verify_windows_stage(stage: Path) -> None:
    if not stage.is_dir() or stage.is_symlink():
        raise ValueError(f"Windows shared stage is unavailable or unsafe: {stage}")
    for path in stage.rglob("*"):
        if path.is_symlink() or not (path.is_file() or path.is_dir()):
            raise ValueError(f"Windows shared stage contains unsafe entry: {path}")
    fx_root = stage / "runtimes" / "fx"
    actual_fx = {
        path.relative_to(stage).as_posix()
        for path in fx_root.rglob("*")
        if path.is_file()
    } if fx_root.is_dir() and not fx_root.is_symlink() else set()
    if actual_fx != FX_RUNTIME_FILES:
        raise ValueError(
            "Windows shared stage managed fx inventory differs\n"
            f"expected: {sorted(FX_RUNTIME_FILES)}\nactual: {sorted(actual_fx)}"
        )
    executable = stage / FX_RUNTIME_ROOT / "fx.exe"
    if executable.stat().st_size != FX_RUNTIME_SIZE or sha256(executable) != FX_RUNTIME_SHA256:
        raise ValueError(f"Windows shared stage managed fx identity differs: {executable}")


def release_artifacts(dist: Path, version: str) -> list[Path]:
    if not STRICT_VERSION.fullmatch(version):
        raise ValueError(f"invalid release version: {version}")
    primary = [
        dist / f"turboism-{version}-lite.zip",
        dist / f"turboism-{version}-full.zip",
        dist / f"TurboismInstaller-{version}.exe",
        dist / f"TurboismInstaller-{version}.jar",
    ]
    return sorted(primary + [path.with_name(path.name + ".sha256") for path in primary])


def verify(
    dist: Path,
    version: str,
    release_plugins: Path | None = None,
    windows_stage: Path | None = None,
) -> None:
    expected = release_artifacts(dist, version)
    expected_names = {path.name for path in expected}
    entries = list(dist.iterdir())
    unsafe = [path for path in entries if path.is_symlink() or not path.is_file()]
    if unsafe:
        raise ValueError(f"release directory contains non-regular entries: {unsafe}")
    actual_names = {path.name for path in entries}
    if actual_names != expected_names:
        raise ValueError(
            "release directory contents differ\n"
            f"expected: {sorted(expected_names)}\nactual: {sorted(actual_names)}"
        )
    primary = [path for path in expected if not path.name.endswith(".sha256")]
    for artifact in primary:
        if not artifact.is_file() or artifact.is_symlink() or artifact.stat().st_size == 0:
            raise ValueError(f"missing, unsafe, or empty artifact: {artifact}")
        verify_sidecar(artifact)
    if release_plugins is None:
        raise ValueError("release plugin roster is required")
    plugins = release_plugin_modules(release_plugins)
    verify_zip(
        dist / f"turboism-{version}-lite.zip",
        version,
        full=False,
        plugins=plugins,
    )
    verify_zip(
        dist / f"turboism-{version}-full.zip",
        version,
        full=True,
        plugins=plugins,
    )
    if windows_stage is not None:
        verify_windows_stage(windows_stage)


def artifact_manifest(
    dist: Path,
    version: str,
    release_plugins: Path | None = None,
    windows_stage: Path | None = None,
) -> dict:
    """Return a deterministic manifest without writing into the eight-file dist."""
    verify(dist, version, release_plugins, windows_stage)
    artifacts = []
    for path in release_artifacts(dist, version):
        suffix = ".sha256" if path.name.endswith(".sha256") else path.suffix.lower()
        media_type = {
            ".zip": "application/zip",
            ".exe": "application/octet-stream",
            ".jar": "application/java-archive",
            ".sha256": "text/plain",
        }[suffix]
        artifacts.append({
            "name": path.name,
            "mediaType": media_type,
            "size": path.stat().st_size,
            "sha256": sha256(path),
        })
    return {
        "format": "turboism.framework-artifacts",
        "schemaVersion": 1,
        "version": version,
        "artifacts": artifacts,
    }


def write_manifest(path: Path, document: dict) -> None:
    path = path.resolve()
    path.parent.mkdir(parents=True, exist_ok=True)
    descriptor, name = tempfile.mkstemp(prefix=f".{path.name}.", suffix=".tmp", dir=path.parent)
    temporary = Path(name)
    try:
        with os.fdopen(descriptor, "wb") as output:
            output.write(json.dumps(
                document, ensure_ascii=False, allow_nan=False,
                separators=(",", ":"), sort_keys=True,
            ).encode("utf-8") + b"\n")
            output.flush()
            os.fsync(output.fileno())
        os.replace(temporary, path)
    finally:
        temporary.unlink(missing_ok=True)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--version", required=True)
    parser.add_argument("--dist", required=True, type=Path)
    parser.add_argument("--release-plugins", required=True, type=Path)
    parser.add_argument("--windows-stage", required=True, type=Path)
    parser.add_argument(
        "--manifest-output", type=Path,
        help="optional output outside dist for a deterministic framework artifact manifest",
    )
    args = parser.parse_args()
    try:
        document = artifact_manifest(
            args.dist.resolve(),
            args.version,
            args.release_plugins.resolve(),
            args.windows_stage.resolve(),
        )
        if args.manifest_output is not None:
            write_manifest(args.manifest_output, document)
    except (OSError, ValueError, zipfile.BadZipFile, KeyError) as failure:
        raise SystemExit(f"release verification failed: {failure}") from failure
    print(f"release verification passed: {args.version}")
    if args.manifest_output is not None:
        print(f"release artifact manifest: {args.manifest_output.resolve()}")


if __name__ == "__main__":
    main()
