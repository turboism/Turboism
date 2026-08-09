"""JAR fixture compilation helpers for the SDK API tier mutation matrix."""
from __future__ import annotations

import subprocess
import zipfile
from pathlib import Path

from sdk_api_tiers_test_fixture_core import sources
from sdk_api_tiers_test_support import fail


def write_text(root: Path, relative: str, text: str) -> None:
    destination = root / relative
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_text(text, encoding="utf-8")


def compile_fixture(root: Path, name: str, current: bool, variant: str) -> Path:
    fixture = root / name
    source = fixture / "src"
    classes = fixture / "classes"
    for relative, content in sources(current, variant).items():
        write_text(source, relative, content)
    classes.mkdir(parents=True)
    compile_sources(source, classes)
    return create_archive(fixture, classes)


def compile_sources(source: Path, classes: Path) -> None:
    java_sources = sorted(str(path) for path in source.rglob("*.java"))
    subprocess.run(
        ["javac", "--release", "17", "-parameters", "-d", str(classes), *java_sources],
        check=True,
        text=True,
        capture_output=True,
    )


def create_archive(fixture: Path, classes: Path) -> Path:
    archive = fixture / "sdk.jar"
    subprocess.run(["jar", "--create", "--file", str(archive), "-C", str(classes), "."], check=True)
    return archive


def delete_jar_entry(source: Path, destination: Path, entry: str) -> None:
    with zipfile.ZipFile(source) as input_archive, zipfile.ZipFile(destination, "w") as output_archive:
        found = copy_without_entry(input_archive, output_archive, entry)
    if not found:
        fail(f"test fixture is missing JAR entry {entry}")


def copy_without_entry(input_archive, output_archive, entry: str) -> bool:
    found = False
    for info in input_archive.infolist():
        if info.filename == entry:
            found = True
            continue
        output_archive.writestr(info, input_archive.read(info.filename))
    return found
