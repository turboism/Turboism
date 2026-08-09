#!/usr/bin/env python3
"""Run minimal negative Gradle fixtures for module-boundary enforcement."""

from __future__ import annotations

import subprocess
import tempfile
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
GRADLEW = ROOT / "gradlew"
BOUNDARY_SCRIPT = (ROOT / "gradle/module-boundaries.gradle.kts").as_posix()


def write_project(project: Path, dependency: str = "", source: str = "") -> None:
    project.mkdir(parents=True, exist_ok=True)
    (project / "build.gradle.kts").write_text(
        "plugins {\n    `java-library`\n}\n" + dependency,
        encoding="utf-8",
    )
    if source:
        source_file = project / "src/main/java/Fixture.java"
        source_file.parent.mkdir(parents=True, exist_ok=True)
        source_file.write_text(source, encoding="utf-8")


def run_fixture(name: str, sdk_dependency: str = "", plugin_dependency: str = "", source: str = "", expected: str = "") -> None:
    with tempfile.TemporaryDirectory(prefix=f"turboism-boundary-{name}-") as directory:
        root = Path(directory)
        (root / "settings.gradle.kts").write_text(
            'rootProject.name = "boundary-fixture"\n'
            'include(":sdk", ":runtime", ":plugins:fixture")\n',
            encoding="utf-8",
        )
        (root / "build.gradle.kts").write_text(
            'tasks.register("checkSdkV4ExactApiCompatibility")\n'
            f'apply(from = "{BOUNDARY_SCRIPT}")\n',
            encoding="utf-8",
        )
        write_project(root / "sdk", sdk_dependency)
        write_project(root / "runtime")
        write_project(root / "plugins/fixture", plugin_dependency, source)
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
            "forbidden-qualified-reference",
            "",
            "",
            "class Fixture { Object value() { return dev.turboism.core.parameter.ForbiddenType.value; } }\n",
            "Forbidden fully-qualified reference",
        ),
    ]
    for fixture in fixtures:
        run_fixture(fixture[0], *fixture[1:])


if __name__ == "__main__":
    main()
