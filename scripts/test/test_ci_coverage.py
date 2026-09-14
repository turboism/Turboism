#!/usr/bin/env python3
"""Guard the CI workflow routing contract against coverage regressions.

The ordinary ``ci.yml`` gate must run the completed-commit verification entry
for every pull request and every ``main`` push, on the exact event commit with
read-only credentials, and may never publish, allocate product build numbers,
or launch a real host. ``unified-channel-checks.yml`` keeps its release-scoped
routing, three-channel QA-only matrix and report retention.

The guard parses the real workflow YAML with the pinned development tool
PyYAML==6.0.3. GitHub's ``on`` key must not be read through YAML 1.1 boolean
semantics: PyYAML resolves it to ``True``, so trigger lookup goes through
:func:`workflow_triggers`.

Verification steps are checked by execution shape, not by substring: a
``run`` block must be one direct command (comments and blank lines allowed,
no pipes/chains/redirection), the Gradle gate must be literally
``xvfb-run <opts> ./gradlew <args>`` with ``./gradlew`` in command position,
and Gradle arguments are restricted to a fixed allowlist of the two gate
tasks plus inert options. Anything the guard cannot confirm is rejected.

Usage: test_ci_coverage.py [repo-root]
"""
from __future__ import annotations

import copy
import re
import shlex
import sys
from pathlib import Path

import yaml

PINNED_YAML_VERSION = "6.0.3"

CI_WORKFLOW = ".github/workflows/ci.yml"
CHANNEL_WORKFLOW = ".github/workflows/unified-channel-checks.yml"

# Paths whose changes must route a pull request into the release-channel gate.
# The same set scopes main pushes so that ordinary source edits do not pay for
# the three-channel release matrix.
REQUIRED_CHANNEL_PATHS = {
    "gradle/**",
    "runtime/**",
    "scripts/release/**",
    "scripts/test/**",
    "packaging/**",
    ".github/workflows/**",
    "RELEASING.md",
    "build.gradle.kts",
    "settings.gradle.kts",
    "gradlew",
    "gradlew.bat",
    "buildSrc/**",
    "distribution/**",
}

EXPECTED_CHANNELS = {"stable", "beta", "nightly"}
QA_BUILD_NUMBERS = ("900000000", "900000001", "900000002")

CHECKOUT_PIN = re.compile(r"^actions/checkout@[0-9a-f]{40}$")
SETUP_JAVA_PIN = re.compile(r"^actions/setup-java@[0-9a-f]{40}$")

# Trigger keys that would narrow ordinary CI below "every pull request / every
# main push". Negative filters (branches-ignore, paths-ignore) are included:
# they exclude whole classes of changes just like positive whitelists do.
PR_NARROWING_KEYS = ("paths", "paths-ignore", "types", "branches", "branches-ignore")
PUSH_NARROWING_KEYS = ("paths", "paths-ignore", "tags", "tags-ignore", "branches-ignore")

# Shell control tokens are rejected in guarded run blocks so that a retained
# command string inside echo/comments/chains cannot fake execution.
SHELL_META = ("&&", "||", "|", ";", "`", "$(", ">", "<", "&", "\n")

# xvfb-run options this guard understands. Unknown options before ./gradlew are
# rejected so the display wrapper cannot silently run a different command.
XVFB_FLAG_OPTIONS = {"-a", "--auto-servernum", "-l", "--listen-tcp"}
XVFB_ARG_OPTIONS = {
    "-s", "--server-args", "-e", "--error-file", "-f", "-F", "--auth-file",
    "-n", "--server-num", "-p", "--xauth-protocol", "-w",
}

# The ordinary gate must invoke exactly these Gradle tasks; every other token
# must be an inert option. --dry-run/-m, -x/--exclude-task, --tests, -P/-D/-I
# properties, init scripts and additional tasks are all rejected by absence.
REQUIRED_GRADLE_TASKS = {"devCheck", "checkCompletedCommit"}
ALLOWED_GRADLE_OPTIONS = {
    "--no-daemon",
    "--offline",
    "--console=plain",
    "--stacktrace",
    "--continue",
}
REQUIRED_GRADLE_OPTIONS = {"--no-daemon"}

# Tokens that must never appear in the ordinary CI job: publishing, product
# build-number allocation, the release-only gate, real-host entry points and
# Graal launches.
FORBIDDEN_ORDINARY_TOKENS = (
    "checkRelease",
    "installerVersion",
    "turboismRelease",
    "TURBOISM_BUILD_NUMBER",
    "TURBOISM_BUILD_VERSION",
    "TURBOISM_BUILD_CHANNEL",
    "allocate-build",
    "allocateBuildNumber",
    "actions/upload-artifact",
    "gh release",
    "promote-github-release",
    "host-validation",
    "HostValidation",
    "CubismEditor",
    "validateParameterHost",
    "validateWorkspaceHost",
    "validateThemeHost",
    "validateFpsHost",
    "validateBoundingBoxOverlayHost",
    "validateSeparateSavePathHost",
    "validateClipMaskViewerHost",
    "validatePsdClipMaskHost",
    "validateStatusBarHost",
    "graalvm",
    "TURBOISM_GRAAL",
)

# Gradle flags that silently turn a "verification" step into a graph print or
# task exclusion. Checked on every gradlew run block in the channel workflow;
# the ordinary gate rejects them harder through the argument allowlist.
# --tests is legitimate in the channel matrix, so it is not forbidden there.
GRADLE_NOOP_FLAGS = (
    re.compile(r"(?:^|\s)--dry-run(?:\s|$)"),
    re.compile(r"(?:^|\s)-m(?:\s|$)"),
    re.compile(r"(?:^|\s)--exclude-task(?:[\s=]|$)"),
    re.compile(r"(?:^|\s)-x(?:\s|$)"),
)

EXIT_SWALLOW_TOKENS = ("|| true", "|| exit 0", "; true", "||:")


def load_workflow(root: Path, relative: str) -> dict:
    path = root / relative
    if not path.is_file():
        raise AssertionError(f"missing workflow {relative}")
    document = yaml.safe_load(path.read_text(encoding="utf-8"))
    if not isinstance(document, dict):
        raise AssertionError(f"{relative} did not parse to a mapping")
    return document


def workflow_triggers(document: dict) -> dict:
    """Return the GitHub ``on`` mapping even though PyYAML reads it as ``True``."""
    triggers = document.get("on", document.get(True))
    return triggers if isinstance(triggers, dict) else {}


def event_config(triggers: dict, event: str) -> dict:
    config = triggers.get(event)
    if config is None:
        return {}
    return config if isinstance(config, dict) else {}


def job_steps(document: dict, job: str) -> list[dict]:
    jobs = document.get("jobs")
    if not isinstance(jobs, dict) or job not in jobs:
        return []
    steps = jobs[job].get("steps")
    return steps if isinstance(steps, list) else []


def run_texts(steps: list[dict]) -> list[str]:
    return [step["run"] for step in steps if isinstance(step, dict) and isinstance(step.get("run"), str)]


def checkout_steps(steps: list[dict]) -> list[dict]:
    return [
        step
        for step in steps
        if isinstance(step, dict) and str(step.get("uses", "")).startswith("actions/checkout@")
    ]


def single_command_tokens(run: str) -> list[str] | None:
    """Tokenize a run block that must be one direct shell command.

    Comment and blank lines are ignored. Anything else — extra commands,
    pipes, chains, redirection, substitution — is not a confirmable direct
    command and returns None so the caller fails closed.
    """
    lines = [
        line.strip()
        for line in run.splitlines()
        if line.strip() and not line.strip().startswith("#")
    ]
    if len(lines) != 1:
        return None
    try:
        tokens = shlex.split(lines[0])
    except ValueError:
        return None
    if not tokens:
        return None
    for token in tokens:
        if any(meta in token for meta in SHELL_META):
            return None
    return tokens


def ci_gradle_invocation(run: str) -> list[str] | None:
    """Parse ``xvfb-run <opts> ./gradlew <args>`` and return the Gradle args.

    Returns None unless xvfb-run is the command, every leading token is a
    known xvfb-run option, and ``./gradlew`` sits in command position.
    """
    tokens = single_command_tokens(run)
    if not tokens or tokens[0] != "xvfb-run":
        return None
    index = 1
    while index < len(tokens):
        token = tokens[index]
        if token in ("./gradlew", "gradlew"):
            break
        if token in XVFB_FLAG_OPTIONS:
            index += 1
        elif token in XVFB_ARG_OPTIONS:
            index += 2
        else:
            return None
    if index >= len(tokens) or tokens[index] not in ("./gradlew", "gradlew"):
        return None
    args = tokens[index + 1:]
    return args or None


def gradle_args_are_inert(args: list[str]) -> bool:
    """Every argument must be a required gate task or a whitelisted inert option.

    This rejects --dry-run/-m, -x/--exclude-task, --tests, -P/-D/-I property
    overrides, included builds, and any additional task by construction.
    """
    return all(arg in REQUIRED_GRADLE_TASKS or arg in ALLOWED_GRADLE_OPTIONS for arg in args)


def check_permissions(document: dict, label: str, problems: list[str]) -> None:
    if document.get("permissions") != {"contents": "read"}:
        problems.append(f"{label}: permissions must be exactly contents: read")


def check_pinned_checkout(step: dict, label: str, problems: list[str], require_event_ref: bool) -> None:
    uses = str(step.get("uses", ""))
    if not CHECKOUT_PIN.match(uses):
        problems.append(f"{label}: checkout is not pinned to a full commit SHA: {uses}")
    with_block = step.get("with") or {}
    if with_block.get("persist-credentials") is not False:
        problems.append(f"{label}: checkout must disable persist-credentials")
    if require_event_ref and with_block.get("ref") != "${{ github.sha }}":
        problems.append(f"{label}: checkout must pin ref to ${{{{ github.sha }}}}")


def check_ci_step_hygiene(steps: list[dict], label: str, problems: list[str]) -> None:
    """Every step in the ordinary gate must run unconditionally and fail the job."""
    for index, step in enumerate(steps):
        if not isinstance(step, dict):
            continue
        name = step.get("name", f"step {index}")
        if step.get("if") is not None:
            problems.append(f"{label}: step '{name}' carries an if condition")
        if step.get("continue-on-error") is True:
            problems.append(f"{label}: step '{name}' uses continue-on-error")
        run = str(step.get("run", ""))
        for token in EXIT_SWALLOW_TOKENS:
            if token in run:
                problems.append(f"{label}: step '{name}' swallows its exit code with '{token}'")


def check_no_forbidden_tokens(document: dict, label: str, problems: list[str]) -> None:
    text = yaml.safe_dump(document)
    for token in FORBIDDEN_ORDINARY_TOKENS:
        if token in text:
            problems.append(f"{label}: forbidden publish/host/release token '{token}' present")


def check_ci_workflow(document: dict) -> list[str]:
    problems: list[str] = []
    label = "ci.yml"

    triggers = workflow_triggers(document)
    if "pull_request" not in triggers:
        problems.append(f"{label}: pull_request trigger is missing")
    else:
        pr = event_config(triggers, "pull_request")
        for filter_key in PR_NARROWING_KEYS:
            if filter_key in pr:
                problems.append(f"{label}: pull_request must cover every pull request, found '{filter_key}' filter")
    if "push" not in triggers:
        problems.append(f"{label}: push trigger is missing")
    else:
        push = event_config(triggers, "push")
        if sorted(push.get("branches") or []) != ["main"]:
            problems.append(f"{label}: push must target main only")
        for filter_key in PUSH_NARROWING_KEYS:
            if filter_key in push:
                problems.append(f"{label}: push must not be filtered by '{filter_key}'")

    check_permissions(document, label, problems)
    check_no_forbidden_tokens(document, label, problems)

    jobs = document.get("jobs") or {}
    job = jobs.get("dev-check")
    if not isinstance(job, dict):
        problems.append(f"{label}: job 'dev-check' is missing")
        return problems
    if job.get("name") != "devCheck":
        problems.append(f"{label}: job check name 'devCheck' changed")
    if job.get("permissions") not in (None, {"contents": "read"}):
        problems.append(f"{label}: job permissions exceed contents: read")
    if job.get("if") is not None:
        problems.append(f"{label}: job-level if condition would bypass verification")
    if job.get("continue-on-error") is True:
        problems.append(f"{label}: job-level continue-on-error softens verification")

    steps = job_steps(document, "dev-check")
    check_ci_step_hygiene(steps, label, problems)

    checkouts = checkout_steps(steps)
    if len(checkouts) != 1:
        problems.append(f"{label}: expected exactly one checkout step, found {len(checkouts)}")
    for step in checkouts:
        check_pinned_checkout(step, label, problems, require_event_ref=True)

    java_steps = [s for s in steps if str(s.get("uses", "")).startswith("actions/setup-java@")]
    if not java_steps:
        problems.append(f"{label}: Java setup step is missing")
    else:
        java_step = java_steps[0]
        if not SETUP_JAVA_PIN.match(str(java_step.get("uses", ""))):
            problems.append(f"{label}: setup-java is not pinned to a full commit SHA")
        if str((java_step.get("with") or {}).get("java-version")) != "17":
            problems.append(f"{label}: Java 17 setup step is missing")
    if not any("xvfb" in run and "install" in run for run in run_texts(steps)):
        problems.append(f"{label}: no step provisions the xvfb display server")

    guard_steps = [
        step for step in steps
        if "test_ci_coverage.py" in str(step.get("run", ""))
    ]
    if not guard_steps:
        problems.append(f"{label}: workflow does not execute this coverage guard")
    for step in guard_steps:
        tokens = single_command_tokens(str(step.get("run", "")))
        if (
            tokens is None
            or tokens[0] not in ("python3", "python")
            or not any(token.endswith("test_ci_coverage.py") for token in tokens[1:])
        ):
            problems.append(f"{label}: coverage guard step is not a direct python invocation")

    pip_steps = [step for step in steps if "PyYAML" in str(step.get("run", ""))]
    if not pip_steps:
        problems.append(f"{label}: pinned PyYAML=={PINNED_YAML_VERSION} install step is missing")
    for step in pip_steps:
        tokens = single_command_tokens(str(step.get("run", ""))) or []
        if (
            tokens[:4] != ["python3", "-m", "pip", "install"]
            or f"PyYAML=={PINNED_YAML_VERSION}" not in tokens[4:]
            or any("PyYAML" in token and token != f"PyYAML=={PINNED_YAML_VERSION}" for token in tokens[4:])
        ):
            problems.append(f"{label}: PyYAML install is not a pinned 'pip install PyYAML=={PINNED_YAML_VERSION}'")

    gradle_mentions = [step for step in steps if "gradlew" in str(step.get("run", ""))]
    if not gradle_mentions:
        problems.append(f"{label}: no Gradle verification step found")
    executed_tasks: set[str] = set()
    for step in gradle_mentions:
        name = step.get("name", "Gradle step")
        run = str(step.get("run", ""))
        args = ci_gradle_invocation(run)
        if args is None:
            problems.append(
                f"{label}: '{name}' mentions gradlew but is not a direct "
                "'xvfb-run <opts> ./gradlew <args>' command"
            )
            continue
        if not gradle_args_are_inert(args):
            problems.append(
                f"{label}: '{name}' carries non-allowlisted Gradle arguments: "
                f"{[a for a in args if a not in REQUIRED_GRADLE_TASKS and a not in ALLOWED_GRADLE_OPTIONS]}"
            )
            continue
        missing_options = REQUIRED_GRADLE_OPTIONS - set(args)
        if missing_options:
            problems.append(f"{label}: '{name}' is missing required option(s) {sorted(missing_options)}")
        executed_tasks.update(arg for arg in args if arg in REQUIRED_GRADLE_TASKS)
    missing_tasks = REQUIRED_GRADLE_TASKS - executed_tasks
    if missing_tasks:
        problems.append(f"{label}: executed Gradle tasks are missing {sorted(missing_tasks)}")

    return problems


def check_channel_workflow(document: dict) -> list[str]:
    problems: list[str] = []
    label = "unified-channel-checks.yml"

    triggers = workflow_triggers(document)
    push = event_config(triggers, "push") if "push" in triggers else None
    if push is None:
        problems.append(f"{label}: push trigger is missing")
    else:
        if sorted(push.get("branches") or []) != ["main"]:
            problems.append(f"{label}: push must target main only (retired feature branch detected)")
        push_paths = set(push.get("paths") or [])
        if not push_paths:
            problems.append(f"{label}: main push must stay limited to release-related paths")
        elif push_paths != REQUIRED_CHANNEL_PATHS:
            problems.append(
                f"{label}: push paths drifted from the release set "
                f"(missing {sorted(REQUIRED_CHANNEL_PATHS - push_paths)}, "
                f"extra {sorted(push_paths - REQUIRED_CHANNEL_PATHS)})"
            )
    if "pull_request" not in triggers:
        problems.append(f"{label}: pull_request trigger is missing")
    else:
        pr_paths = set(event_config(triggers, "pull_request").get("paths") or [])
        missing = REQUIRED_CHANNEL_PATHS - pr_paths
        if missing:
            problems.append(f"{label}: pull_request paths lost coverage: {sorted(missing)}")

    check_permissions(document, label, problems)

    jobs = document.get("jobs") or {}
    for job_name in ("verify", "package-check"):
        if not isinstance(jobs.get(job_name), dict):
            problems.append(f"{label}: job '{job_name}' is missing")

    for job_name, job in jobs.items():
        if not isinstance(job, dict):
            continue
        if job.get("permissions") not in (None, {"contents": "read"}):
            problems.append(f"{label}: job '{job_name}' broadens permissions")
        if job.get("continue-on-error") is True:
            problems.append(f"{label}: job '{job_name}' uses continue-on-error")
        for step in job.get("steps") or []:
            if not isinstance(step, dict):
                continue
            name = step.get("name", "step")
            retention = (
                str(step.get("uses", "")).startswith("actions/upload-artifact@")
                or str(name).startswith("Retain")
            )
            if not retention:
                if step.get("if") is not None:
                    problems.append(f"{label}:{job_name}: verification step '{name}' carries an if condition")
                if step.get("continue-on-error") is True:
                    problems.append(f"{label}:{job_name}: step '{name}' uses continue-on-error")
            run = str(step.get("run", ""))
            if "gradlew" in run:
                for flag in GRADLE_NOOP_FLAGS:
                    if flag.search(run):
                        problems.append(f"{label}:{job_name}: '{name}' uses no-op Gradle flag '{flag.pattern}'")
        for step in checkout_steps(job.get("steps") or []):
            check_pinned_checkout(step, f"{label}:{job_name}", problems, require_event_ref=False)

    package_check = jobs.get("package-check") or {}
    needs = package_check.get("needs")
    if needs != "verify" and "verify" not in (needs or []):
        problems.append(f"{label}: package-check must depend on verify")
    matrix = ((package_check.get("strategy") or {}).get("matrix") or {})
    if set(matrix.get("channel") or []) != EXPECTED_CHANNELS:
        problems.append(f"{label}: channel matrix must be exactly {sorted(EXPECTED_CHANNELS)}")

    steps = job_steps(document, "package-check")
    runs = run_texts(steps)
    identity_steps = [
        step for step in steps
        if "QA-only" in str(step.get("name", "")) or "TURBOISM_BUILD_NUMBER" in str(step.get("run", ""))
    ]
    if not identity_steps:
        problems.append(f"{label}: QA-only identity step is missing")
    else:
        identity_run = "\n".join(str(step.get("run", "")) for step in identity_steps)
        for number in QA_BUILD_NUMBERS:
            if number not in identity_run:
                problems.append(f"{label}: QA build number {number} missing from identity step")
        if "matrix.channel" not in yaml.safe_dump(identity_steps):
            problems.append(f"{label}: identity step must select on matrix.channel")

    if not any("checkRelease" in run and "-PinstallerVersion" in run for run in runs):
        problems.append(f"{label}: package-check must run checkRelease with an explicit installer version")

    uploads = [
        step for step in (job_steps(document, "verify") + steps)
        if str(step.get("uses", "")).startswith("actions/upload-artifact@")
    ]
    if len(uploads) < 2:
        problems.append(f"{label}: report-retention upload steps are missing")
    for step in uploads:
        if (step.get("with") or {}).get("retention-days") is None:
            problems.append(f"{label}: artifact upload must keep a bounded retention-days")

    text = yaml.safe_dump(document)
    for token in ("CubismEditor", "host-validation", "run-parameter-host-validation"):
        if token in text:
            problems.append(f"{label}: real-host token '{token}' present")

    return problems


def check_repository(root: Path) -> list[str]:
    problems = check_ci_workflow(load_workflow(root, CI_WORKFLOW))
    problems += check_channel_workflow(load_workflow(root, CHANNEL_WORKFLOW))
    return problems


def assert_rejected(document: dict, checker, description: str) -> None:
    problems = checker(document)
    assert problems, f"mutation must be rejected: {description}"


def mutate(document: dict, mutation) -> dict:
    clone = copy.deepcopy(document)
    mutation(clone)
    return clone


def ci_gradle_step(document: dict) -> dict:
    return next(
        step for step in job_steps(document, "dev-check") if "gradlew" in str(step.get("run", ""))
    )


def case_real_workflows_satisfy_contract(root: Path) -> None:
    problems = check_repository(root)
    assert not problems, "workflow contract violations:\n" + "\n".join(problems)


def case_on_key_not_yaml11_boolean(root: Path) -> None:
    document = yaml.safe_load("on:\n  push:\n    branches: [main]\n")
    assert True in document and "on" not in document, "PyYAML must expose the YAML 1.1 bool key"
    assert workflow_triggers(document) == {"push": {"branches": ["main"]}}
    quoted = yaml.safe_load('"on":\n  pull_request:\n')
    assert workflow_triggers(quoted) == {"pull_request": None}


def _ci(root: Path) -> dict:
    return load_workflow(root, CI_WORKFLOW)


def _channel(root: Path) -> dict:
    return load_workflow(root, CHANNEL_WORKFLOW)


def _append_gradle_arg(document: dict, extra: str) -> None:
    step = ci_gradle_step(document)
    step["run"] = f"{step['run']} {extra}"


def case_ci_only_devcheck_rejected(root: Path) -> None:
    def strip(document: dict) -> None:
        step = ci_gradle_step(document)
        step["run"] = str(step["run"]).replace("checkCompletedCommit", "")
    assert_rejected(mutate(_ci(root), strip), check_ci_workflow, "devCheck-only Gradle gate")


def case_ci_test_filter_rejected(root: Path) -> None:
    assert_rejected(
        mutate(_ci(root), lambda d: _append_gradle_arg(d, "--tests dev.turboism.core.FrameworkBuildInfoTest")),
        check_ci_workflow,
        "--tests filter",
    )


def case_ci_property_filter_rejected(root: Path) -> None:
    assert_rejected(
        mutate(_ci(root), lambda d: _append_gradle_arg(d, "-PtestFilter=sdk")),
        check_ci_workflow,
        "-P test filter",
    )


def case_ci_dry_run_rejected(root: Path) -> None:
    """--dry-run only prints the task graph; nothing executes."""
    assert_rejected(
        mutate(_ci(root), lambda d: _append_gradle_arg(d, "--dry-run")),
        check_ci_workflow,
        "--dry-run graph printing",
    )


def case_ci_dry_run_short_rejected(root: Path) -> None:
    assert_rejected(
        mutate(_ci(root), lambda d: _append_gradle_arg(d, "-m")),
        check_ci_workflow,
        "-m dry-run alias",
    )


def case_ci_exclude_test_task_rejected(root: Path) -> None:
    """-x test removes every module test task from the graph."""
    assert_rejected(
        mutate(_ci(root), lambda d: _append_gradle_arg(d, "-x test")),
        check_ci_workflow,
        "-x test exclusion",
    )


def case_ci_exclude_task_long_rejected(root: Path) -> None:
    assert_rejected(
        mutate(_ci(root), lambda d: _append_gradle_arg(d, "--exclude-task :sdk:test")),
        check_ci_workflow,
        "--exclude-task exclusion",
    )


def case_ci_extra_task_rejected(root: Path) -> None:
    """Only the two gate tasks may be invoked; substitutions are not allowed."""
    assert_rejected(
        mutate(_ci(root), lambda d: _append_gradle_arg(d, "checkRelease")),
        check_ci_workflow,
        "extra task argument",
    )


def case_ci_continue_on_error_rejected(root: Path) -> None:
    def soften(document: dict) -> None:
        ci_gradle_step(document)["continue-on-error"] = True
    assert_rejected(mutate(_ci(root), soften), check_ci_workflow, "continue-on-error")


def case_ci_job_continue_on_error_rejected(root: Path) -> None:
    def soften(document: dict) -> None:
        document["jobs"]["dev-check"]["continue-on-error"] = True
    assert_rejected(mutate(_ci(root), soften), check_ci_workflow, "job-level continue-on-error")


def case_ci_if_bypass_rejected(root: Path) -> None:
    def bypass(document: dict) -> None:
        ci_gradle_step(document)["if"] = "github.event_name == 'push'"
    assert_rejected(mutate(_ci(root), bypass), check_ci_workflow, "conditional verification bypass")


def case_ci_guard_step_if_rejected(root: Path) -> None:
    """if: 'false' on the guard step skips the guard while keeping its name."""
    def bypass(document: dict) -> None:
        for step in job_steps(document, "dev-check"):
            if "test_ci_coverage.py" in str(step.get("run", "")):
                step["if"] = "false"
    assert_rejected(mutate(_ci(root), bypass), check_ci_workflow, "guard step skipped by if")


def case_ci_setup_step_if_rejected(root: Path) -> None:
    def bypass(document: dict) -> None:
        for step in job_steps(document, "dev-check"):
            if str(step.get("uses", "")).startswith("actions/setup-java@"):
                step["if"] = "matrix.experimental"
    assert_rejected(mutate(_ci(root), bypass), check_ci_workflow, "setup step skipped by if")


def case_ci_swallowed_exit_rejected(root: Path) -> None:
    def swallow(document: dict) -> None:
        step = ci_gradle_step(document)
        step["run"] += " || true"
    assert_rejected(mutate(_ci(root), swallow), check_ci_workflow, "swallowed Gradle exit code")


def case_ci_echo_fakes_gradle_rejected(root: Path) -> None:
    """echo <command> keeps every token but never executes Gradle."""
    def fake(document: dict) -> None:
        step = ci_gradle_step(document)
        step["run"] = f"echo {step['run']}"
    assert_rejected(mutate(_ci(root), fake), check_ci_workflow, "echo wrapping the gate command")


def case_ci_prepended_command_rejected(root: Path) -> None:
    """A second line means the run block is not one confirmable direct command."""
    def prepend(document: dict) -> None:
        step = ci_gradle_step(document)
        step["run"] = f"echo starting\n{step['run']}"
    assert_rejected(mutate(_ci(root), prepend), check_ci_workflow, "multi-command run block")


def case_ci_xvfb_wraps_echo_rejected(root: Path) -> None:
    """xvfb-run must launch ./gradlew itself, not an echo of it."""
    def fake(document: dict) -> None:
        step = ci_gradle_step(document)
        step["run"] = "xvfb-run -a echo './gradlew --no-daemon devCheck checkCompletedCommit'"
    assert_rejected(mutate(_ci(root), fake), check_ci_workflow, "xvfb-run running echo")


def case_ci_gradle_in_comment_rejected(root: Path) -> None:
    def comment(document: dict) -> None:
        step = ci_gradle_step(document)
        step["run"] = f"# {step['run']}\necho done"
    assert_rejected(mutate(_ci(root), comment), check_ci_workflow, "gate command commented out")


def case_ci_pull_request_path_filter_rejected(root: Path) -> None:
    def filter_pr(document: dict) -> None:
        workflow_triggers(document)["pull_request"] = {"paths": ["sdk/**", "runtime/**"]}
    assert_rejected(mutate(_ci(root), filter_pr), check_ci_workflow, "pull_request path whitelist")


def case_ci_pull_request_branches_ignore_rejected(root: Path) -> None:
    """branches-ignore silently drops main-targeted pull requests."""
    def filter_pr(document: dict) -> None:
        workflow_triggers(document)["pull_request"] = {"branches-ignore": ["main"]}
    assert_rejected(mutate(_ci(root), filter_pr), check_ci_workflow, "pull_request branches-ignore")


def case_ci_missing_push_rejected(root: Path) -> None:
    assert_rejected(
        mutate(_ci(root), lambda d: workflow_triggers(d).pop("push")),
        check_ci_workflow,
        "missing main push trigger",
    )


def case_ci_old_branch_rejected(root: Path) -> None:
    def old_branch(document: dict) -> None:
        workflow_triggers(document)["push"]["branches"] = ["feat/unified-build-channels-20260909"]
    assert_rejected(mutate(_ci(root), old_branch), check_ci_workflow, "push to a retired feature branch")


def case_ci_permissions_rejected(root: Path) -> None:
    def widen(document: dict) -> None:
        document["permissions"] = {"contents": "read", "packages": "write"}
    assert_rejected(mutate(_ci(root), widen), check_ci_workflow, "broadened permissions")


def case_ci_checkout_credentials_rejected(root: Path) -> None:
    def persist(document: dict) -> None:
        checkout_steps(job_steps(document, "dev-check"))[0]["with"]["persist-credentials"] = True
    assert_rejected(mutate(_ci(root), persist), check_ci_workflow, "persisted checkout credentials")


def case_ci_checkout_unpinned_rejected(root: Path) -> None:
    def unpinned(document: dict) -> None:
        checkout_steps(job_steps(document, "dev-check"))[0]["uses"] = "actions/checkout@v4"
    assert_rejected(mutate(_ci(root), unpinned), check_ci_workflow, "unpinned checkout action")


def case_ci_checkout_ref_drift_rejected(root: Path) -> None:
    def drift(document: dict) -> None:
        checkout_steps(job_steps(document, "dev-check"))[0]["with"]["ref"] = "${{ github.ref }}"
    assert_rejected(mutate(_ci(root), drift), check_ci_workflow, "checkout not pinned to github.sha")


def case_ci_guard_removed_rejected(root: Path) -> None:
    def drop(document: dict) -> None:
        steps = job_steps(document, "dev-check")
        steps[:] = [s for s in steps if "test_ci_coverage.py" not in str(s.get("run", ""))]
    assert_rejected(mutate(_ci(root), drop), check_ci_workflow, "coverage guard step removed")


def case_ci_guard_commented_rejected(root: Path) -> None:
    """A commented-out guard line keeps the token but runs nothing."""
    def comment(document: dict) -> None:
        for step in job_steps(document, "dev-check"):
            run = str(step.get("run", ""))
            if "test_ci_coverage.py" in run:
                step["run"] = f"# {run}"
    assert_rejected(mutate(_ci(root), comment), check_ci_workflow, "guard command commented out")


def case_ci_unpinned_tool_rejected(root: Path) -> None:
    def loosen(document: dict) -> None:
        for step in job_steps(document, "dev-check"):
            run = str(step.get("run", ""))
            if "PyYAML" in run:
                step["run"] = run.replace("PyYAML==6.0.3", "PyYAML")
    assert_rejected(mutate(_ci(root), loosen), check_ci_workflow, "unpinned PyYAML install")


def case_ci_release_step_rejected(root: Path) -> None:
    def publish(document: dict) -> None:
        job_steps(document, "dev-check").append(
            {"name": "Release", "run": "./gradlew checkRelease -PinstallerVersion=1.0.0"}
        )
    assert_rejected(mutate(_ci(root), publish), check_ci_workflow, "release gate inside ordinary CI")


def case_ci_host_step_rejected(root: Path) -> None:
    def host(document: dict) -> None:
        job_steps(document, "dev-check").append(
            {"name": "Host", "run": "bash scripts/preview/run-parameter-host-validation.sh 5302"}
        )
    assert_rejected(mutate(_ci(root), host), check_ci_workflow, "real-host validation inside ordinary CI")


def case_ci_unisolated_display_rejected(root: Path) -> None:
    def bare(document: dict) -> None:
        for step in job_steps(document, "dev-check"):
            run = str(step.get("run", ""))
            if "gradlew" in run:
                step["run"] = run.replace("xvfb-run -a -s '-screen 0 1920x1080x24' ", "")
    assert_rejected(mutate(_ci(root), bare), check_ci_workflow, "Gradle run without xvfb isolation")


def case_ci_missing_no_daemon_rejected(root: Path) -> None:
    """Without --no-daemon the gate could share a stale Gradle daemon."""
    def daemon(document: dict) -> None:
        step = ci_gradle_step(document)
        step["run"] = str(step["run"]).replace("--no-daemon ", "")
    assert_rejected(mutate(_ci(root), daemon), check_ci_workflow, "missing --no-daemon")


def case_ci_job_renamed_rejected(root: Path) -> None:
    def rename(document: dict) -> None:
        document["jobs"]["dev-check"]["name"] = "build"
    assert_rejected(mutate(_ci(root), rename), check_ci_workflow, "check name drift")


def case_channel_old_branch_rejected(root: Path) -> None:
    def old_branch(document: dict) -> None:
        push = workflow_triggers(document)["push"]
        push["branches"] = ["feat/unified-build-channels-20260909"]
    assert_rejected(mutate(_channel(root), old_branch), check_channel_workflow, "push to retired feature branch")


def case_channel_push_unscoped_rejected(root: Path) -> None:
    def unscoped(document: dict) -> None:
        workflow_triggers(document)["push"].pop("paths")
    assert_rejected(mutate(_channel(root), unscoped), check_channel_workflow, "unscoped main push")


def case_channel_push_path_added_rejected(root: Path) -> None:
    def widen(document: dict) -> None:
        workflow_triggers(document)["push"]["paths"].append("sdk/**")
    assert_rejected(mutate(_channel(root), widen), check_channel_workflow, "push widened beyond release paths")


def case_channel_pr_path_removed_rejected(root: Path) -> None:
    def drop(document: dict) -> None:
        paths = event_config(workflow_triggers(document), "pull_request")["paths"]
        paths.remove("runtime/**")
    assert_rejected(mutate(_channel(root), drop), check_channel_workflow, "pull_request lost runtime coverage")


def case_channel_pr_new_path_removed_rejected(root: Path) -> None:
    def drop(document: dict) -> None:
        paths = event_config(workflow_triggers(document), "pull_request")["paths"]
        paths.remove("buildSrc/**")
    assert_rejected(mutate(_channel(root), drop), check_channel_workflow, "pull_request lost buildSrc coverage")


def case_channel_pr_path_renamed_rejected(root: Path) -> None:
    def rename(document: dict) -> None:
        paths = event_config(workflow_triggers(document), "pull_request")["paths"]
        paths[paths.index("scripts/release/**")] = "scripts/releases/**"
    assert_rejected(mutate(_channel(root), rename), check_channel_workflow, "renamed release path")


def case_channel_pr_mixed_drift_rejected(root: Path) -> None:
    def mixed(document: dict) -> None:
        paths = event_config(workflow_triggers(document), "pull_request")["paths"]
        paths.remove("distribution/**")
        paths.append("docs/**")
    assert_rejected(mutate(_channel(root), mixed), check_channel_workflow, "mixed path add/remove drift")


def case_channel_matrix_drift_rejected(root: Path) -> None:
    def drift(document: dict) -> None:
        document["jobs"]["package-check"]["strategy"]["matrix"]["channel"].remove("nightly")
    assert_rejected(mutate(_channel(root), drift), check_channel_workflow, "channel matrix lost nightly")


def case_channel_extra_channel_rejected(root: Path) -> None:
    def widen(document: dict) -> None:
        document["jobs"]["package-check"]["strategy"]["matrix"]["channel"].append("canary")
    assert_rejected(mutate(_channel(root), widen), check_channel_workflow, "unreviewed channel added")


def case_channel_identity_removed_rejected(root: Path) -> None:
    def drop(document: dict) -> None:
        steps = job_steps(document, "package-check")
        steps[:] = [
            s for s in steps
            if "QA-only" not in str(s.get("name", "")) and "TURBOISM_BUILD_NUMBER" not in str(s.get("run", ""))
        ]
    assert_rejected(mutate(_channel(root), drop), check_channel_workflow, "QA-only identity step removed")


def case_channel_identity_real_numbers_rejected(root: Path) -> None:
    def real(document: dict) -> None:
        for step in job_steps(document, "package-check"):
            run = str(step.get("run", ""))
            if "900000000" in run:
                step["run"] = run.replace("900000000", "100000000")
    assert_rejected(mutate(_channel(root), real), check_channel_workflow, "non-QA build number allocated")


def case_channel_credentials_rejected(root: Path) -> None:
    def persist(document: dict) -> None:
        for job in document["jobs"].values():
            for step in checkout_steps(job.get("steps") or []):
                step["with"]["persist-credentials"] = True
    assert_rejected(mutate(_channel(root), persist), check_channel_workflow, "persisted checkout credentials")


def case_channel_release_gate_removed_rejected(root: Path) -> None:
    def drop(document: dict) -> None:
        for step in job_steps(document, "package-check"):
            if "checkRelease" in str(step.get("run", "")):
                step["run"] = "echo skipped"
    assert_rejected(mutate(_channel(root), drop), check_channel_workflow, "checkRelease step removed")


def case_channel_release_step_if_rejected(root: Path) -> None:
    """A conditional checkRelease step would silently skip the release gate."""
    def bypass(document: dict) -> None:
        for step in job_steps(document, "package-check"):
            if "checkRelease" in str(step.get("run", "")):
                step["if"] = "false"
    assert_rejected(mutate(_channel(root), bypass), check_channel_workflow, "checkRelease skipped by if")


def case_channel_gradle_dry_run_rejected(root: Path) -> None:
    def noop(document: dict) -> None:
        for step in job_steps(document, "package-check"):
            run = str(step.get("run", ""))
            if "gradlew" in run and "checkRelease" in run:
                step["run"] = run + " --dry-run"
    assert_rejected(mutate(_channel(root), noop), check_channel_workflow, "dry-run release gate")


def case_channel_gradle_exclude_rejected(root: Path) -> None:
    def exclude(document: dict) -> None:
        for step in job_steps(document, "package-check"):
            run = str(step.get("run", ""))
            if "gradlew" in run and "checkRelease" in run:
                step["run"] = run + " -x test"
    assert_rejected(mutate(_channel(root), exclude), check_channel_workflow, "release gate excluding tests")


def case_channel_job_soft_fail_rejected(root: Path) -> None:
    def soften(document: dict) -> None:
        document["jobs"]["package-check"]["continue-on-error"] = True
    assert_rejected(mutate(_channel(root), soften), check_channel_workflow, "package-check continue-on-error")


def case_channel_reports_removed_rejected(root: Path) -> None:
    def drop(document: dict) -> None:
        for job in document["jobs"].values():
            steps = job.get("steps") or []
            steps[:] = [
                s for s in steps
                if not str(s.get("uses", "")).startswith("actions/upload-artifact@")
            ]
    assert_rejected(mutate(_channel(root), drop), check_channel_workflow, "report retention removed")


CASES = (
    case_real_workflows_satisfy_contract,
    case_on_key_not_yaml11_boolean,
    case_ci_only_devcheck_rejected,
    case_ci_test_filter_rejected,
    case_ci_property_filter_rejected,
    case_ci_dry_run_rejected,
    case_ci_dry_run_short_rejected,
    case_ci_exclude_test_task_rejected,
    case_ci_exclude_task_long_rejected,
    case_ci_extra_task_rejected,
    case_ci_continue_on_error_rejected,
    case_ci_job_continue_on_error_rejected,
    case_ci_if_bypass_rejected,
    case_ci_guard_step_if_rejected,
    case_ci_setup_step_if_rejected,
    case_ci_swallowed_exit_rejected,
    case_ci_echo_fakes_gradle_rejected,
    case_ci_prepended_command_rejected,
    case_ci_xvfb_wraps_echo_rejected,
    case_ci_gradle_in_comment_rejected,
    case_ci_pull_request_path_filter_rejected,
    case_ci_pull_request_branches_ignore_rejected,
    case_ci_missing_push_rejected,
    case_ci_old_branch_rejected,
    case_ci_permissions_rejected,
    case_ci_checkout_credentials_rejected,
    case_ci_checkout_unpinned_rejected,
    case_ci_checkout_ref_drift_rejected,
    case_ci_guard_removed_rejected,
    case_ci_guard_commented_rejected,
    case_ci_unpinned_tool_rejected,
    case_ci_release_step_rejected,
    case_ci_host_step_rejected,
    case_ci_unisolated_display_rejected,
    case_ci_missing_no_daemon_rejected,
    case_ci_job_renamed_rejected,
    case_channel_old_branch_rejected,
    case_channel_push_unscoped_rejected,
    case_channel_push_path_added_rejected,
    case_channel_pr_path_removed_rejected,
    case_channel_pr_new_path_removed_rejected,
    case_channel_pr_path_renamed_rejected,
    case_channel_pr_mixed_drift_rejected,
    case_channel_matrix_drift_rejected,
    case_channel_extra_channel_rejected,
    case_channel_identity_removed_rejected,
    case_channel_identity_real_numbers_rejected,
    case_channel_credentials_rejected,
    case_channel_release_gate_removed_rejected,
    case_channel_release_step_if_rejected,
    case_channel_gradle_dry_run_rejected,
    case_channel_gradle_exclude_rejected,
    case_channel_job_soft_fail_rejected,
    case_channel_reports_removed_rejected,
)


def main() -> int:
    root = Path(sys.argv[1]).resolve() if len(sys.argv) > 1 else Path(__file__).resolve().parents[2]
    assert yaml.__version__ == PINNED_YAML_VERSION, (
        f"guarded tool must be PyYAML=={PINNED_YAML_VERSION}, found {yaml.__version__}"
    )
    for case in CASES:
        case(root)
        print(f"ok {case.__name__}")
    print(f"\nPASS: {len(CASES)} CI coverage guard checks")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
