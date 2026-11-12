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


def write_runtime_source(root: Path, relative: str, source: str) -> None:
    """Write one ``:runtime`` source relative to ``runtime/src/main/java/dev/turboism``.

    The boundary task never compiles ``:runtime``, so these fixtures pin the scanning rules
    without the source having to resolve against a real core package.
    """
    source_file = root / "runtime/src/main/java/dev/turboism" / relative
    source_file.parent.mkdir(parents=True, exist_ok=True)
    source_file.write_text(source, encoding="utf-8")


def write_fixture_build(
    root: Path,
    plugin_source: str,
    resource_source: str,
    runtime_sources: tuple[tuple[str, str], ...] = (),
) -> None:
    policy = root / "gradle/module-boundaries.gradle.kts"
    policy.parent.mkdir()
    policy.write_bytes(BOUNDARY_SCRIPT.read_bytes())
    write_buildsrc(root)
    (root / "settings.gradle.kts").write_text(
        'rootProject.name = "boundary-fixture"\n'
        'include(":sdk", ":runtime", ":core-contract", ":persistence-core", '
        '":compatibility-policy", ":plugins:fixture")\n'
        # The kernel and admission-policy gates read the consolidated ``core/`` layout, so the
        # fixture projects have to live there too instead of at the top level.
        'project(":persistence-core").projectDir = file("core/persistence")\n'
        'project(":compatibility-policy").projectDir = file("core/compatibility-policy")\n',
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
        root / "core/persistence",
        "package dev.turboism.persistence.config;\n\n"
        "import java.util.List;\n\n"
        "final class Fixture { List<String> values() { return List.of(); } }\n",
    )
    write_project(
        root / "core/compatibility-policy",
        "package dev.turboism.compatibility.policy;\n\n"
        "final class Fixture { boolean admits(final String version) { return "
        'version != null && version.equals("5.3.02"); } }\n',
    )
    write_project(root / "plugins/fixture", plugin_source)
    for relative, source in runtime_sources:
        write_runtime_source(root, relative, source)
    if resource_source:
        resource_file = root / "plugins/fixture/src/main/resources/Fixture.java"
        resource_file.parent.mkdir(parents=True, exist_ok=True)
        resource_file.write_text(resource_source, encoding="utf-8")


def run_gradle(
    name: str,
    plugin_source: str,
    *,
    expected: str = "",
    resource_source: str = "",
    runtime_sources: tuple[tuple[str, str], ...] = (),
) -> None:
    """Run one fixture build; ``expected`` empty means the boundary task must pass."""
    with tempfile.TemporaryDirectory(prefix=f"turboism-boundary-{name}-") as directory:
        root = Path(directory)
        write_fixture_build(root, plugin_source, resource_source, runtime_sources)
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
            "runtime-host-package-literal",
            "class Fixture {}\n",
            runtime_sources=((
                "ui/Fixture.java",
                'package dev.turboism.ui;\nclass Fixture { String owner = "com.live2d.fixture.Host"; }\n',
            ),),
            expected="Forbidden runtime host-package literal",
        ),
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
        # ``:runtime`` top-level packages are exhaustively classified: a package no layer
        # names cannot silently sit outside the layering contract.
        lambda: run_gradle(
            "runtime-unclassified-top-package",
            "class Fixture {}\n",
            runtime_sources=(("rogue/Fixture.java", "package dev.turboism.rogue;\n\nclass Fixture {}\n"),),
            expected="Unclassified :runtime top-level package dev.turboism.rogue",
        ),
        # ``dev.turboism.runtime.log`` is the leaf the MODULE_CONTRACTS entry promises: importing
        # ``core.*`` from there must fail even though ``core`` may import ``runtime.log``.
        lambda: run_gradle(
            "runtime-log-upward-import",
            "class Fixture {}\n",
            runtime_sources=(
                (
                    "runtime/log/Fixture.java",
                    "package dev.turboism.runtime.log;\n\n"
                    "import dev.turboism.core.plugin.PluginContext;\n\n"
                    "final class Fixture {}\n",
                ),
            ),
            expected="Runtime layering violation in runtime/.../runtime/log/Fixture.java",
        ),
        # ``core.schema`` sits below ``core.descriptor``: the manifest reader reports its
        # findings through the schema vocabulary, so the schema package may never import back.
        lambda: run_gradle(
            "core-schema-descriptor-import",
            "class Fixture {}\n",
            runtime_sources=(
                (
                    "core/schema/Fixture.java",
                    "package dev.turboism.core.schema;\n\n"
                    "import dev.turboism.core.descriptor.PluginDescriptorParser;\n\n"
                    "final class Fixture {}\n",
                ),
            ),
            expected="Runtime sub-package violation in runtime/.../core/schema/Fixture.java",
        ),
        # A core source that imports a composition layer needs no exemption: the plugin
        # service wiring lives in ``kernel.context``, so ``core`` stays below every surface.
        lambda: run_gradle(
            "core-composition-layer-import",
            "class Fixture {}\n",
            runtime_sources=(
                (
                    "core/plugin/Fixture.java",
                    "package dev.turboism.core.plugin;\n\n"
                    "import dev.turboism.adapter.host.RuntimeHostAdapterAccess;\n\n"
                    "final class Fixture {}\n",
                ),
            ),
            expected="Runtime layering violation in runtime/.../core/plugin/Fixture.java",
        ),
    ]
    with ThreadPoolExecutor(max_workers=min(len(tasks), 4)) as executor:
        for _ in executor.map(lambda fn: fn(), tasks):
            pass


if __name__ == "__main__":
    main()
