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

Usage: test_ci_coverage.py [repo-root]
"""
from __future__ import annotations

import copy
import re
import sys
import tempfile
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


def gradle_steps(steps: list[dict]) -> list[dict]:
    return [
        step
        for step in steps
        if isinstance(step, dict) and "gradlew" in str(step.get("run", ""))
    ]


def checkout_steps(steps: list[dict]) -> list[dict]:
    return [
        step
        for step in steps
        if isinstance(step, dict) and str(step.get("uses", "")).startswith("actions/checkout@")
    ]


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


def check_no_soft_failures(steps: list[dict], label: str, problems: list[str]) -> None:
    for index, step in enumerate(steps):
        if not isinstance(step, dict):
            continue
        name = step.get("name", f"step {index}")
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
        for filter_key in ("paths", "paths-ignore", "types", "branches"):
            if filter_key in pr:
                problems.append(f"{label}: pull_request must cover every pull request, found '{filter_key}' filter")
    if "push" not in triggers:
        problems.append(f"{label}: push trigger is missing")
    else:
        push = event_config(triggers, "push")
        if sorted(push.get("branches") or []) != ["main"]:
            problems.append(f"{label}: push must target main only")
        for filter_key in ("paths", "paths-ignore", "tags"):
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

    steps = job_steps(document, "dev-check")
    check_no_soft_failures(steps, label, problems)

    checkouts = checkout_steps(steps)
    if len(checkouts) != 1:
        problems.append(f"{label}: expected exactly one checkout step, found {len(checkouts)}")
    for step in checkouts:
        check_pinned_checkout(step, label, problems, require_event_ref=True)

    java_steps = [s for s in steps if str(s.get("uses", "")).startswith("actions/setup-java@")]
    if not java_steps or str((java_steps[0].get("with") or {}).get("java-version")) != "17":
        problems.append(f"{label}: Java 17 setup step is missing")

    runs = run_texts(steps)
    if not any("test_ci_coverage.py" in run for run in runs):
        problems.append(f"{label}: workflow does not execute this coverage guard")
    if not any(re.search(r"PyYAML==6\.0\.3", run) and "pip" in run for run in runs):
        problems.append(f"{label}: pinned PyYAML==6.0.3 install step is missing")

    verification = gradle_steps(steps)
    if not verification:
        problems.append(f"{label}: no Gradle verification step found")
    combined = "\n".join(str(step.get("run", "")) for step in verification)
    for gate in ("devCheck", "checkCompletedCommit"):
        if not re.search(rf"(?<![\w-]){gate}(?![\w-])", combined):
            problems.append(f"{label}: Gradle verification does not run {gate}")
    for step in verification:
        run = str(step.get("run", ""))
        name = step.get("name", "Gradle step")
        if step.get("if") is not None:
            problems.append(f"{label}: '{name}' carries an if bypass")
        if "xvfb-run" not in run:
            problems.append(f"{label}: '{name}' does not isolate the display with xvfb-run")
        if re.search(r"--tests\b", run):
            problems.append(f"{label}: '{name}' filters tests with --tests")
        if re.search(r"-P\w*[Tt]est", run):
            problems.append(f"{label}: '{name}' filters tests with a -P property")

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
    return gradle_steps(job_steps(document, "dev-check"))[0]


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


def case_ci_only_devcheck_rejected(root: Path) -> None:
    def strip(document: dict) -> None:
        for step in gradle_steps(job_steps(document, "dev-check")):
            step["run"] = str(step["run"]).replace("checkCompletedCommit", "")
    assert_rejected(mutate(_ci(root), strip), check_ci_workflow, "devCheck-only Gradle gate")


def case_ci_test_filter_rejected(root: Path) -> None:
    def narrow(document: dict) -> None:
        step = ci_gradle_step(document)
        step["run"] += " --tests dev.turboism.core.FrameworkBuildInfoTest"
    assert_rejected(mutate(_ci(root), narrow), check_ci_workflow, "--tests filter")


def case_ci_property_filter_rejected(root: Path) -> None:
    def narrow(document: dict) -> None:
        step = ci_gradle_step(document)
        step["run"] += " -PtestFilter=sdk"
    assert_rejected(mutate(_ci(root), narrow), check_ci_workflow, "-P test filter")


def case_ci_continue_on_error_rejected(root: Path) -> None:
    def soften(document: dict) -> None:
        ci_gradle_step(document)["continue-on-error"] = True
    assert_rejected(mutate(_ci(root), soften), check_ci_workflow, "continue-on-error")


def case_ci_if_bypass_rejected(root: Path) -> None:
    def bypass(document: dict) -> None:
        ci_gradle_step(document)["if"] = "github.event_name == 'push'"
    assert_rejected(mutate(_ci(root), bypass), check_ci_workflow, "conditional verification bypass")


def case_ci_swallowed_exit_rejected(root: Path) -> None:
    def swallow(document: dict) -> None:
        step = ci_gradle_step(document)
        step["run"] += " || true"
    assert_rejected(mutate(_ci(root), swallow), check_ci_workflow, "swallowed Gradle exit code")


def case_ci_pull_request_path_filter_rejected(root: Path) -> None:
    def filter_pr(document: dict) -> None:
        workflow_triggers(document)["pull_request"] = {"paths": ["sdk/**", "runtime/**"]}
    assert_rejected(mutate(_ci(root), filter_pr), check_ci_workflow, "pull_request path whitelist")


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
        for step in gradle_steps(job_steps(document, "dev-check")):
            step["run"] = str(step["run"]).replace("xvfb-run -a -s '-screen 0 1920x1080x24' ", "")
    assert_rejected(mutate(_ci(root), bare), check_ci_workflow, "Gradle run without xvfb isolation")


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
    case_ci_continue_on_error_rejected,
    case_ci_if_bypass_rejected,
    case_ci_swallowed_exit_rejected,
    case_ci_pull_request_path_filter_rejected,
    case_ci_missing_push_rejected,
    case_ci_old_branch_rejected,
    case_ci_permissions_rejected,
    case_ci_checkout_credentials_rejected,
    case_ci_checkout_unpinned_rejected,
    case_ci_checkout_ref_drift_rejected,
    case_ci_guard_removed_rejected,
    case_ci_unpinned_tool_rejected,
    case_ci_release_step_rejected,
    case_ci_host_step_rejected,
    case_ci_unisolated_display_rejected,
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
