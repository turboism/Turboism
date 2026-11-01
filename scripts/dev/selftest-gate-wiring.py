#!/usr/bin/env python3
"""Structural self-test for the verification gates this lane rewired.

Each gate can exist and still never run. These assertions pin the wiring that
makes a gate real:

  1. `hostValidationScriptContractTest` is aggregated by `checkIntegration`
  2. `verifyGeneratedSubscriberCatalog` reaches `devCheck` through the root
     `checkGeneratedSubscriberCatalogs` collector, not only a subproject `check`
  3. the ASM admission tasks run inside `devCheck`, not only inside `checkRelease`
  4. the installer plugin-sections byte comparison runs inside `devCheck`
  5. `checkShellLint` fails closed instead of skipping when shellcheck is absent
     and CONTRIBUTING.md lists shellcheck as a build requirement
  6. the `TestExecutionGuard` unit tests run inside `devCheck`; Gradle builds
     buildSrc but never runs its `test` task, so an unwired test suite would be
     dead weight

The checks are textual because the subject is build wiring; every referenced
task name is also asserted to exist so a rename cannot leave a dangling edge.
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
VERIFICATION = ROOT / "gradle/verification.gradle.kts"
ASM_ADMISSION = ROOT / "gradle/asm-admission.gradle.kts"
EXTENSION = ROOT / "buildSrc/src/main/kotlin/dev/turboism/gradle/TurboismPluginExtension.kt"
INTEGRATION_BUILD = ROOT / "testing/integration-tests/build.gradle.kts"
SHELL_LINT = ROOT / "scripts/check_shell_lint.sh"
CONTRIBUTING = ROOT / "CONTRIBUTING.md"

failures: list[str] = []


def check(name: str, condition: bool, detail: str = "") -> None:
    if condition:
        print(f"ok: {name}")
    else:
        failures.append(name)
        print(f"FAIL: {name}" + (f" ({detail})" if detail else ""))


def task_block(text: str, declaration: str) -> str:
    """Return the body of the first block that starts with `declaration`."""
    start = text.find(declaration)
    if start < 0:
        return ""
    depth = 0
    for index in range(start, len(text)):
        char = text[index]
        if char == "{":
            depth += 1
        elif char == "}":
            depth -= 1
            if depth == 0:
                return text[start:index + 1]
    return ""


def main() -> int:
    verification = VERIFICATION.read_text(encoding="utf-8")
    integration_build = INTEGRATION_BUILD.read_text(encoding="utf-8")
    extension = EXTENSION.read_text(encoding="utf-8")
    asm_admission = ASM_ADMISSION.read_text(encoding="utf-8")

    dev_check = task_block(verification, "val devCheck by tasks.registering {")
    check_integration = task_block(verification, 'tasks.register("checkIntegration")')
    check("devCheck block is locatable", bool(dev_check))
    check("checkIntegration block is locatable", bool(check_integration))

    # 1. host-validation script contract test must be aggregated.
    check(
        "hostValidationScriptContractTest task is registered",
        '"hostValidationScriptContractTest"' in integration_build,
    )
    check(
        "checkIntegration depends on :testing:integration-tests:hostValidationScriptContractTest",
        '":testing:integration-tests:hostValidationScriptContractTest"' in check_integration,
    )

    # 2. generated subscriber catalog verification must reach devCheck.
    check(
        "subproject check still verifies its generated subscriber catalog",
        'project.tasks.named("check")' in extension,
    )
    check(
        "extension registers the root checkGeneratedSubscriberCatalogs collector",
        "checkGeneratedSubscriberCatalogs" in extension and "dependsOn(verifyTask)" in extension,
    )
    check(
        "devCheck aggregates the subscriber catalog collector",
        'tasks.matching { it.name == "checkGeneratedSubscriberCatalogs" }' in dev_check,
    )
    plugin_builds = sorted((ROOT / "plugins").glob("*/build.gradle.kts"))
    callers = [path for path in plugin_builds if "verifySubscriberCatalog" in path.read_text(encoding="utf-8")]
    check("at least one plugin registers a generated subscriber catalog", bool(callers), str(len(callers)))

    # 3. ASM admission must run in devCheck.
    check('ASM task "checkResolvedBytecodeDependencyGraph" exists', '"checkResolvedBytecodeDependencyGraph"' in asm_admission)
    check('ASM task "checkAsmDependencyModel" exists', '"checkAsmDependencyModel"' in asm_admission)
    check(
        "devCheck runs the resolved bytecode dependency graph",
        '"checkResolvedBytecodeDependencyGraph"' in dev_check,
    )
    check("devCheck runs the ASM dependency model", '"checkAsmDependencyModel"' in dev_check)

    # 4. installer plugin sections byte comparison must run in devCheck.
    check(
        "devCheck runs the installer plugin-sections comparison",
        '"checkInstallerPluginSections"' in dev_check,
    )
    check(
        "installer still includes the generated plugin sections",
        '!include "plugin-sections.nsh"' in (ROOT / "packaging/windows-installer/installer.nsi").read_text(encoding="utf-8"),
    )

    # 4b. the shared probe-terminal contract must run in devCheck with complete inputs.
    probe_terminal = task_block(verification, "val checkProbeTerminalSelftest by tasks.registering(Exec::class)")
    check('devCheck runs the shared probe-terminal contract', 'checkProbeTerminalSelftest' in dev_check)
    check(
        'probe-terminal gate is registered',
        "check-probe-terminal.sh" in probe_terminal,
    )
    for required_input in ('"validation/shared"', '"scripts/preview/host_validation_evidence.py"'):
        check(
            f"probe-terminal gate declares input {required_input.strip(chr(34))}",
            required_input in probe_terminal,
        )
    check(
        "probe-terminal gate consumes the probe directory set",
        "hostProbeDirectories" in probe_terminal,
    )
    for probe in (
        "validation/animation-timeline-host-probe",
        "validation/atlas-polygon-host-probe",
        "validation/clipmask-viewer-host-probe",
        "validation/parameter-batch-transfer-host-probe",
    ):
        check(f"probe-terminal inputs cover {probe}", f'"{probe}"' in verification)

    # 5. shell lint must fail closed and be documented as a build requirement.
    shell_lint = SHELL_LINT.read_text(encoding="utf-8")
    check("checkShellLint stays in devCheck", '"checkShellLint"' in dev_check)
    check(
        "check_shell_lint.sh fails when shellcheck is missing",
        "skipping shell lint gate" not in shell_lint
        and re.search(r"shellcheck.*not found|not found on PATH", shell_lint) is not None
        and "exit 1" in shell_lint,
    )
    contributing = CONTRIBUTING.read_text(encoding="utf-8")
    check(
        "CONTRIBUTING.md lists shellcheck as a build requirement",
        re.search(r"shellcheck", contributing, re.IGNORECASE) is not None
        and "checkShellLint" in contributing,
    )

    # 6. the execution-count guard's own tests must run inside devCheck.
    guard_task = task_block(verification, "val checkTestExecutionGuard by tasks.registering(Exec::class)")
    check('execution-count guard self-test task is registered', bool(guard_task))
    check('devCheck aggregates the execution-count guard self-test', "checkTestExecutionGuard" in dev_check)
    check(
        'execution-count guard self-test runs the buildSrc test task',
        '"-p", "buildSrc", "test"' in guard_task,
    )
    check(
        'execution-count guard unit tests exist',
        (ROOT / 'buildSrc/src/test/java/dev/turboism/gradle/internal/TestExecutionGuardTest.java').is_file(),
    )
    check(
        'zero-execution allowlist is tracked',
        (ROOT / 'gradle/test-zero-executed-allowlist.txt').is_file(),
    )

    print(f"gate wiring self-test: {'all checks passed' if not failures else f'{len(failures)} failed'}")
    return 0 if not failures else 1


if __name__ == "__main__":
    sys.exit(main())
