#!/usr/bin/env python3
"""Run the two end-to-end Gradle fixtures for module-boundary enforcement.

The boundary rules themselves are unit-tested in ``buildSrc`` (``dev.turboism.gradle.internal
.boundary``): each source-shaped and bytecode-shaped predicate used to cost a full Gradle build
here, which made the suite 16 builds long and still left the predicates uncovered when it was not
run. What is left is the wiring, which only a real build can prove:

* ``plugin-host-ui-traversal-whitespace``: a violation the buildSrc scanner finds must reach the
  Gradle log through ``checkModuleBoundaries``, in the message the scanner produces.
* ``host-ui-traversal-comment-ok``: a source whose same tokens sit in a comment and a string
  literal must pass, which is what proves ``checkModuleBoundaries`` asks the scanner at all.
"""

from __future__ import annotations

import os
import shutil
import subprocess
import tempfile
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
# The wrapper is a shell script on POSIX and a batch file on Windows; the fixtures run on both.
GRADLEW = ROOT / ("gradlew.bat" if os.name == "nt" else "gradlew")
BOUNDARY_SCRIPT = ROOT / "gradle/module-boundaries.gradle.kts"
BUILDSRC_INTERNAL = ROOT / "buildSrc/src/main/java/dev/turboism/gradle/internal"


def write_buildsrc(root: Path) -> None:
    """The boundary script calls into buildSrc; fixtures replicate the classes it imports."""
    internal = root / "buildSrc/src/main/java/dev/turboism/gradle/internal"
    boundary = internal / "boundary"
    boundary.mkdir(parents=True)
    shutil.copy2(BUILDSRC_INTERNAL / "VerificationStamps.java", internal / "VerificationStamps.java")
    for source in sorted((BUILDSRC_INTERNAL / "boundary").glob("*.java")):
        shutil.copy2(source, boundary / source.name)


def write_project(project: Path, source: str = "") -> None:
    project.mkdir(parents=True, exist_ok=True)
    (project / "build.gradle.kts").write_text(
        "plugins {\n    `java-library`\n}\n",
        encoding="utf-8",
    )
    if source:
        source_file = project / "src/main/java/Fixture.java"
        source_file.parent.mkdir(parents=True, exist_ok=True)
        source_file.write_text(source, encoding="utf-8")


def write_fixture_build(root: Path, plugin_source: str, resource_source: str) -> None:
    policy = root / "gradle/module-boundaries.gradle.kts"
    policy.parent.mkdir()
    policy.write_bytes(BOUNDARY_SCRIPT.read_bytes())
    write_buildsrc(root)
    (root / "settings.gradle.kts").write_text(
        'rootProject.name = "boundary-fixture"\n'
        'include(":sdk", ":runtime", ":core-contract", ":persistence-core", '
        '":compatibility-policy", ":plugins:fixture")\n',
        encoding="utf-8",
    )
    (root / "build.gradle.kts").write_text(
        'tasks.register("checkSdkV4ExactApiCompatibility")\n'
        'apply(from = "gradle/module-boundaries.gradle.kts")\n',
        encoding="utf-8",
    )
    write_project(root / "sdk")
    write_project(root / "runtime")
    write_project(root / "core-contract")
    # The kernel and admission-policy gates need a minimal, contract-clean tree to pass, and the
    # script depends on their classes, so both are compiled by every fixture.
    write_project(
        root / "persistence-core",
        "package dev.turboism.persistence.config;\n\n"
        "import java.util.List;\n\n"
        "final class Fixture { List<String> values() { return List.of(); } }\n",
    )
    write_project(
        root / "compatibility-policy",
        "package dev.turboism.compatibility.policy;\n\n"
        "final class Fixture { boolean admits(final String version) { return "
        'version != null && version.equals("5.3.02"); } }\n',
    )
    write_project(root / "plugins/fixture", plugin_source)
    if resource_source:
        resource_file = root / "plugins/fixture/src/main/resources/Fixture.java"
        resource_file.parent.mkdir(parents=True, exist_ok=True)
        resource_file.write_text(resource_source, encoding="utf-8")


def run_gradle(name: str, plugin_source: str, *, expected: str = "", resource_source: str = "") -> None:
    """Run one fixture build; ``expected`` empty means the boundary task must pass."""
    with tempfile.TemporaryDirectory(prefix=f"turboism-boundary-{name}-") as directory:
        root = Path(directory)
        write_fixture_build(root, plugin_source, resource_source)
        result = subprocess.run(
            [str(GRADLEW), "-p", str(root), "--offline", "--no-daemon", "checkModuleBoundaries", "--console=plain"],
            cwd=ROOT,
            text=True,
            capture_output=True,
            check=False,
        )
        output = result.stdout + result.stderr
        if expected:
            if result.returncode == 0:
                raise AssertionError(f"{name}: boundary task unexpectedly passed\n{output}")
            if expected not in output:
                raise AssertionError(f"{name}: expected {expected!r} in Gradle output\n{output}")
            print(f"PASS {name}")
        else:
            if result.returncode != 0:
                raise AssertionError(f"{name}: boundary task unexpectedly failed\n{output}")
            print(f"PASS {name}")


def main() -> None:
    tasks = [
        lambda: run_gradle(
            "plugin-host-ui-traversal-whitespace",
            "import javax.swing.SwingUtilities;\n"
            "class Fixture { void inspect(javax.swing.JComponent c) "
            "{ java.awt.Window w = SwingUtilities . getWindowAncestor(c); } }\n",
            expected="must not discover or mutate host UI trees",
        ),
        lambda: run_gradle(
            "host-ui-traversal-comment-ok",
            "class Fixture { // SwingUtilities.getWindowAncestor( stays a comment\n"
            "    String note = \"SwingUtilities.getRoot(\";\n}\n",
        ),
    ]
    with ThreadPoolExecutor(max_workers=min(len(tasks), 4)) as executor:
        for _ in executor.map(lambda fn: fn(), tasks):
            pass


if __name__ == "__main__":
    main()
