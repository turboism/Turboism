#!/usr/bin/env python3
"""Run minimal negative Gradle fixtures for module-boundary enforcement."""

from __future__ import annotations

import subprocess
import tempfile
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
GRADLEW = ROOT / "gradlew"
BOUNDARY_SCRIPT = ROOT / "gradle/module-boundaries.gradle.kts"
STAMP_HELPER = ROOT / "buildSrc/src/main/java/dev/turboism/gradle/internal/VerificationStamps.java"


def write_buildsrc(root: Path) -> None:
    """The boundary script calls into buildSrc; fixtures replicate the helper."""
    stamp = root / "buildSrc/src/main/java/dev/turboism/gradle/internal/VerificationStamps.java"
    stamp.parent.mkdir(parents=True, exist_ok=True)
    stamp.write_bytes(STAMP_HELPER.read_bytes())


def write_project(
    project: Path, dependency: str = "", source: str = "", resource_source: str = "",
) -> None:
    project.mkdir(parents=True, exist_ok=True)
    (project / "build.gradle.kts").write_text(
        "plugins {\n    `java-library`\n}\n" + dependency,
        encoding="utf-8",
    )
    if source:
        source_file = project / "src/main/java/Fixture.java"
        source_file.parent.mkdir(parents=True, exist_ok=True)
        source_file.write_text(source, encoding="utf-8")
    if resource_source:
        resource_file = project / "src/main/resources/Fixture.java"
        resource_file.parent.mkdir(parents=True, exist_ok=True)
        resource_file.write_text(resource_source, encoding="utf-8")


def run_fixture(
    name: str, sdk_dependency: str = "", plugin_dependency: str = "", source: str = "",
    expected: str = "", *, runtime_dependency: str = "", contract_dependency: str = "",
    resource_source: str = "",
) -> None:
    with tempfile.TemporaryDirectory(prefix=f"turboism-boundary-{name}-") as directory:
        root = Path(directory)
        # The task declares its policy file as a project-relative input. Keep the
        # actual rule source in the fixture so Gradle reaches the rejection being tested.
        policy = root / "gradle/module-boundaries.gradle.kts"
        policy.parent.mkdir()
        policy.write_bytes(BOUNDARY_SCRIPT.read_bytes())
        write_buildsrc(root)
        (root / "settings.gradle.kts").write_text(
            'rootProject.name = "boundary-fixture"\n'
            'include(":sdk", ":runtime", ":core-contract", ":plugins:fixture")\n',
            encoding="utf-8",
        )
        (root / "build.gradle.kts").write_text(
            'tasks.register("checkSdkV4ExactApiCompatibility")\n'
            'apply(from = "gradle/module-boundaries.gradle.kts")\n',
            encoding="utf-8",
        )
        write_project(root / "sdk", sdk_dependency)
        write_project(root / "runtime", runtime_dependency)
        write_project(root / "core-contract", contract_dependency)
        write_project(
            root / "plugins/fixture", plugin_dependency, source, resource_source,
        )
        if "files(" in plugin_dependency:
            (root / "plugins/fixture/bad.jar").write_bytes(b"not-a-jar")

        result = subprocess.run(
            [str(GRADLEW), "-p", str(root), "--offline", "--no-daemon", "checkModuleBoundaries", "--console=plain"],
            cwd=ROOT,
            text=True,
            capture_output=True,
            check=False,
        )
        output = result.stdout + result.stderr
        if result.returncode == 0:
            raise AssertionError(f"{name}: boundary task unexpectedly passed\n{output}")
        if expected and expected not in output:
            raise AssertionError(f"{name}: expected {expected!r} in Gradle output\n{output}")
        print(f"PASS {name}")


def run_clean_fixture(name: str, source: str = "", resource_source: str = "") -> None:
    """Assert that a plugin fixture source passes the boundary task."""
    with tempfile.TemporaryDirectory(prefix=f"turboism-boundary-{name}-") as directory:
        root = Path(directory)
        policy = root / "gradle/module-boundaries.gradle.kts"
        policy.parent.mkdir()
        policy.write_bytes(BOUNDARY_SCRIPT.read_bytes())
        write_buildsrc(root)
        (root / "settings.gradle.kts").write_text(
            'rootProject.name = "boundary-fixture"\n'
            'include(":sdk", ":runtime", ":core-contract", ":plugins:fixture")\n',
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
        write_project(root / "plugins/fixture", source=source, resource_source=resource_source)

        result = subprocess.run(
            [str(GRADLEW), "-p", str(root), "--offline", "--no-daemon", "checkModuleBoundaries", "--console=plain"],
            cwd=ROOT,
            text=True,
            capture_output=True,
            check=False,
        )
        output = result.stdout + result.stderr
        if result.returncode != 0:
            raise AssertionError(f"{name}: boundary task unexpectedly failed\n{output}")
        print(f"PASS {name}")


def main() -> None:
    fixtures = [
        (
            "sdk-runtime-project",
            'dependencies { compileOnly(project(":runtime")) }\n',
            "",
            "",
            "SDK :sdk may not depend on project component :runtime",
        ),
        (
            "plugin-non-sdk-project",
            "",
            'dependencies { compileOnly(project(":runtime")) }\n',
            "",
            "Plugin :plugins:fixture may not depend on project component :runtime",
        ),
        (
            "plugin-external-module",
            "",
            'dependencies { compileOnly("example:forbidden:1.0") }\n',
            "",
            "may only declare approved project dependencies",
        ),
        (
            "plugin-file-dependency",
            "",
            'dependencies { compileOnly(files("bad.jar")) }\n',
            "",
            "may only declare approved project dependencies",
        ),
        (
            "forbidden-import",
            "",
            "",
            "import dev.turboism.core.parameter.ForbiddenType;\nclass Fixture {}\n",
            "Forbidden import",
        ),
        (
            "forbidden-static-import",
            "",
            "",
            "import static com.live2d.foo.Bar.baz;\nclass Fixture { void call() { baz(); } }\n",
            "Forbidden import",
        ),
        (
            "forbidden-qualified-reference",
            "",
            "",
            "class Fixture { Object value() { return dev.turboism.core.parameter.ForbiddenType.value; } }\n",
            "Forbidden fully-qualified reference",
        ),
        (
            "retired-event-package",
            "",
            "",
            "package dev.turboism.sdk.event.cubism;\nclass Fixture {}\n",
            "Retired package dev.turboism.sdk.event.cubism must not be reintroduced",
        ),
    ]
    for fixture in fixtures:
        run_fixture(fixture[0], *fixture[1:])
    run_fixture(
        "forbidden-import-in-resources",
        expected="Forbidden import",
        resource_source="import com.live2d.foo.Bar;\nclass Fixture {}\n",
    )
    run_fixture(
        "plugin-host-ui-traversal-whitespace",
        source="import javax.swing.SwingUtilities;\n"
        "class Fixture { void inspect(javax.swing.JComponent c) "
        "{ java.awt.Window w = SwingUtilities . getWindowAncestor(c); } }\n",
        expected="must not discover or mutate host UI trees",
    )
    run_clean_fixture(
        "host-ui-traversal-comment-ok",
        source="class Fixture { // SwingUtilities.getWindowAncestor( stays a comment\n"
        "    String note = \"SwingUtilities.getRoot(\";\n}\n",
    )
    for configuration in ("implementation", "runtimeOnly"):
        run_fixture(
            f"runtime-plugin-{configuration}",
            runtime_dependency=f'dependencies {{ {configuration}(project(":plugins:fixture")) }}\n',
            expected="Runtime may not depend on plugin component :plugins:fixture",
        )
    run_fixture(
        "contract-runtime-project",
        contract_dependency='dependencies { api(project(":runtime")) }\n',
        expected="Core-contract :core-contract may not depend on project component :runtime",
    )
    run_fixture(
        "plugin-contract-project",
        plugin_dependency='dependencies { compileOnly(project(":core-contract")) }\n',
        expected="Plugin :plugins:fixture may not depend on project component :core-contract",
    )
    run_fixture(
        "plugin-internal-contract-import",
        source="import dev.turboism.internal.core.ShellServices;\nclass Fixture {}\n",
        expected="Forbidden internal-contract import",
    )


if __name__ == "__main__":
    main()
