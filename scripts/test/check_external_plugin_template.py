#!/usr/bin/env python3
"""Build ``templates/plugin-template`` the way an external consumer does.

Modes:

- ``check`` — stage the template's tracked inputs (``git ls-files`` list,
  working-tree bytes: ignored/untracked ``build/`` output, ``.gradle/`` state
  and stale ``libs/turboism-sdk-*.jar`` artifacts can never leak in) into a
  unique temporary directory, drop the real ``:sdk:jar`` artifact into
  ``libs/`` under the release-asset name ``turboism-sdk-<version>.jar`` (real
  bytes, real version, no metadata rewriting), and run a nested Gradle
  ``build`` with every declared repository cleared through an init script.
  The produced plugin JAR is then validated with the production
  ``PluginMetaValidationCli`` and ``FirstPartyMetadataVerificationCli`` gate
  tools (runtime-internal; they are never part of the template compile
  classpath). Any build or CLI failure exits non-zero.

- ``selftest`` — fail-closed fixtures proving the check stays alive: a drifted
  descriptor whose declared entrypoint is absent from the built JAR, a manifest
  rejected by the schema validator, a missing SDK resolved against an empty
  Maven repository even when the polluted source carried a real stale SDK jar,
  staging-input isolation against polluted sources, a poisoned user-level init
  script proving the isolated Gradle home does not silently inherit user
  configuration, and a pure-Python control proving command-line echo cannot
  satisfy output needles. Every fixture must fail (or hold, for the staging
  assertion); a pass exits non-zero.

Isolation notes:

- Only ``git ls-files``-tracked template inputs are staged, so local build
  output and stray SDK jars under ``templates/plugin-template`` cannot reach
  the consumer copy.
- The nested build runs with ``--no-daemon`` and ``--gradle-user-home``
  pointed at a private per-run home, so user-level init scripts and
  ``~/.gradle/gradle.properties`` never apply and no idle daemon outlives the
  check. The shared wrapper ``dists`` directory is symlinked into that home
  from the Gradle user home the outer build actually runs under (passed via
  ``--shared-gradle-home``): an already-downloaded Gradle distribution is
  reused, and on a cold checkout the wrapper downloads the pinned
  distribution once through the standard shared cache — the only cold
  prerequisite, recorded here.
- The positive path additionally clears all project repositories, so nothing
  resolves from ``~/.m2`` or the monorepo; the SDK-absent fixture pins
  ``maven.repo.local`` to an empty directory so a stale local Maven cache can
  never mask a missing artifact.
- Validation CLIs run on the ``:runtime:classes`` classpath supplied by the
  Gradle task; that classpath is confined to the ``java -cp`` invocations and
  never enters the template build.

Usage: check_external_plugin_template.py {check|selftest} --sdk-jar <path>
       --runtime-classpath <path-list> [--sdk-version <v>] [--java <bin>]
       [--repo-root <dir>] [--work-dir <dir>] [--shared-gradle-home <dir>]
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import shutil
import stat
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
TEMPLATE = Path("templates/plugin-template")
DESCRIPTOR = Path("src/main/resources/META-INF/turboism/plugin.json")
PLUGIN_JAR_GLOB = "hello-turboism-plugin-*.jar"
META_CLI = "dev.turboism.core.schema.plugin.PluginMetaValidationCli"
JAR_CLI = "dev.turboism.pluginmanagement.FirstPartyMetadataVerificationCli"
GRADLE_TIMEOUT = 900
JAVA_TIMEOUT = 120

WRAPPER_FILES = (
    "gradlew",
    "gradlew.bat",
    "gradle/wrapper/gradle-wrapper.jar",
    "gradle/wrapper/gradle-wrapper.properties",
)

ISOLATION_INIT = """// Written by check_external_plugin_template.py: strips every repository the
// template declares so the nested build proves zero-repository resolution.
allprojects { project ->
    project.afterEvaluate { project.repositories.clear() }
}
"""

POISON_INIT = """// Selftest fixture: a user-level init script must break the build loudly.
allprojects { throw new GradleException("poisoned-user-init-marker") }
"""


class CheckFailure(Exception):
    pass


def fail(message: str) -> None:
    raise CheckFailure(message)


def run(cmd: list[str], cwd: Path, log: Path, timeout: int) -> subprocess.CompletedProcess:
    log.parent.mkdir(parents=True, exist_ok=True)
    with log.open("w", encoding="utf-8") as handle:
        handle.write("$ " + " ".join(cmd) + "\n\n")
        handle.flush()
        result = subprocess.run(
            cmd,
            cwd=cwd,
            stdout=handle,
            stderr=subprocess.STDOUT,
            timeout=timeout,
            text=True,
        )
    return result


def log_tail(log: Path, lines: int = 40) -> str:
    try:
        return "".join(log.read_text(encoding="utf-8").splitlines(keepends=True)[-lines:])
    except OSError:
        return "<unreadable log>"


def log_output(log: Path) -> str:
    """Subprocess output only — the first ``$ cmd`` echo line is evidence, not
    output, and must never satisfy a needle (a path embedded in an argument
    like ``-Dmaven.repo.local=…`` says nothing about where resolution looked)."""
    text = log.read_text(encoding="utf-8", errors="replace")
    lines = text.splitlines(keepends=True)
    if lines and lines[0].startswith("$ "):
        lines = lines[1:]
    return "".join(lines)


def sdk_version_from(jar: Path) -> str:
    name = jar.name
    if not (name.startswith("sdk-") and name.endswith(".jar")):
        fail(f"--sdk-jar does not look like the :sdk:jar artifact: {name}")
    return name[len("sdk-") : -len(".jar")]


def template_inputs(repo_root: Path) -> list[str]:
    """Tracked template inputs only — the public surface a consumer receives."""
    out = subprocess.run(
        ["git", "-C", str(repo_root), "ls-files", "-z", "--", str(TEMPLATE)],
        capture_output=True,
        check=True,
    ).stdout
    inputs = [str(Path(p).relative_to(TEMPLATE)) for p in out.decode().split("\0") if p]
    if not inputs:
        fail(f"git ls-files returned no inputs under {TEMPLATE}")
    return sorted(inputs)


def stage_template(destination: Path, repo_root: Path, inputs: list[str], source_root: Path | None = None) -> Path:
    """Copy exactly the tracked template inputs (working-tree bytes) plus the
    Gradle wrapper the README tells consumers to copy; returns the staged
    project directory. ``source_root`` lets selftests point the same input list
    at a polluted tree."""
    source = source_root or (repo_root / TEMPLATE)
    project = destination / "plugin-template"
    for rel in inputs:
        target = project / rel
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(source / rel, target)
    for rel in WRAPPER_FILES:
        target = project / rel
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(repo_root / rel, target)
    (project / "gradlew").chmod(
        (project / "gradlew").stat().st_mode | stat.S_IXUSR | stat.S_IXGRP | stat.S_IXOTH
    )
    return project


def isolated_gradle_home(work: Path, shared_gradle_home: Path | None = None) -> Path:
    """Private Gradle user home for the nested build: no user init scripts, no
    user gradle.properties, no inherited caches. The wrapper ``dists``
    directory is linked in from the Gradle user home the outer build actually
    runs under so the downloaded Gradle distribution is reused instead of
    re-fetched per run."""
    home = work / "gradle-home"
    (home / "wrapper").mkdir(parents=True, exist_ok=True)
    base = shared_gradle_home or Path(os.environ.get("GRADLE_USER_HOME", Path.home() / ".gradle"))
    shared = base / "wrapper" / "dists"
    if shared.is_dir():
        (home / "wrapper" / "dists").symlink_to(shared)
    return home


def gradle_build(
    project: Path,
    log: Path,
    gradle_home: Path,
    sdk_version: str,
    extra_args: list[str],
) -> subprocess.CompletedProcess:
    cmd = [
        "./gradlew",
        "--console=plain",
        "--no-daemon",
        "--gradle-user-home",
        str(gradle_home),
        "--max-workers=2",
        f"-PturboismSdkVersion={sdk_version}",
        "build",
    ] + extra_args
    return run(cmd, cwd=project, log=log, timeout=GRADLE_TIMEOUT)


def validate_descriptor(java: str, classpath: str, descriptor: Path, log: Path) -> subprocess.CompletedProcess:
    return run(
        [java, "-cp", classpath, META_CLI, str(descriptor)],
        cwd=ROOT,
        log=log,
        timeout=JAVA_TIMEOUT,
    )


def validate_jar(java: str, classpath: str, descriptor: Path, jar: Path, log: Path) -> subprocess.CompletedProcess:
    return run(
        [java, "-cp", classpath, JAR_CLI, str(descriptor), str(jar)],
        cwd=ROOT,
        log=log,
        timeout=JAVA_TIMEOUT,
    )


def built_jar(project: Path) -> Path:
    jars = sorted(project.glob(f"build/libs/{PLUGIN_JAR_GLOB}"))
    if len(jars) != 1:
        fail(f"expected exactly one {PLUGIN_JAR_GLOB} under {project / 'build/libs'}, found {jars}")
    return jars[0]


def sdk_jars_in_libs(project: Path) -> list[Path]:
    return sorted((project / "libs").glob("turboism-sdk-*.jar"))


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def check(args: argparse.Namespace) -> int:
    work = Path(tempfile.mkdtemp(prefix="run-", dir=args.work_dir))
    sdk_jar = Path(args.sdk_jar)
    sdk_version = args.sdk_version or sdk_version_from(sdk_jar)
    inputs = template_inputs(Path(args.repo_root))
    project = stage_template(work, Path(args.repo_root), inputs)

    # Staged inputs are tracked-only: a pre-existing SDK jar here would mean a
    # tracked artifact or a staging leak, both of which must fail loudly.
    stale = sdk_jars_in_libs(project)
    if stale:
        fail(f"staged template already carries SDK jars: {stale}")

    # libs/ carries the real :sdk:jar bytes under the release-asset name.
    placed = project / "libs" / f"turboism-sdk-{sdk_version}.jar"
    placed.write_bytes(sdk_jar.read_bytes())

    init_script = work / "clear-repositories.init.gradle"
    init_script.write_text(ISOLATION_INIT, encoding="utf-8")

    build = gradle_build(
        project,
        work / "gradle-build.log",
        isolated_gradle_home(work, args.shared_gradle_home),
        sdk_version,
        ["-I", str(init_script)],
    )
    if build.returncode != 0:
        fail(f"template build failed (see {work / 'gradle-build.log'}):\n{log_tail(work / 'gradle-build.log')}")
    jar = built_jar(project)

    descriptor = project / DESCRIPTOR
    meta = validate_descriptor(args.java, args.runtime_classpath, descriptor, work / "plugin-meta-validation.log")
    if meta.returncode != 0:
        fail(f"PluginMetaValidationCli rejected the template descriptor:\n{log_tail(work / 'plugin-meta-validation.log')}")
    contract = validate_jar(args.java, args.runtime_classpath, descriptor, jar, work / "jar-contract-validation.log")
    if contract.returncode != 0:
        fail(f"FirstPartyMetadataVerificationCli rejected the built plugin jar:\n{log_tail(work / 'jar-contract-validation.log')}")

    has_tests = (project / "src" / "test").is_dir()
    print(f"external-consumer build OK: {jar.name} sha256={sha256(jar)}")
    print(f"  sdk artifact: {sdk_jar.name} version={sdk_version} sha256={sha256(sdk_jar)}")
    print("  descriptor: PluginMetaValidationCli PASS; jar contract: FirstPartyMetadataVerificationCli PASS")
    if not has_tests:
        print("  note: template ships no src/test — Gradle 'test' is NO-SOURCE and is not a test pass")
    print(f"  workspace: {work}")
    return 0


def expect_failure(label: str, result: subprocess.CompletedProcess, log: Path, needles: list[str]) -> None:
    if result.returncode == 0:
        fail(f"selftest fixture '{label}' unexpectedly passed (see {log})")
    text = log_output(log)
    for needle in needles:
        if needle not in text:
            fail(f"selftest fixture '{label}' failed without expected marker {needle!r} (see {log})")
    print(f"  fixture '{label}' failed as expected (exit {result.returncode}, markers: {', '.join(needles)})")


def write_bogus_sdk_jar(path: Path) -> None:
    """A well-formed JAR containing no SDK classes — stands in for foreign
    turboism-sdk-*.jar leftovers when only their zip validity matters."""
    with zipfile.ZipFile(path, "w") as archive:
        archive.writestr("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\n")


def selftest(args: argparse.Namespace) -> int:
    work = Path(tempfile.mkdtemp(prefix="selftest-", dir=args.work_dir))
    sdk_jar = Path(args.sdk_jar)
    sdk_version = args.sdk_version or sdk_version_from(sdk_jar)
    repo_root = Path(args.repo_root)
    inputs = template_inputs(repo_root)
    gradle_home = isolated_gradle_home(work, args.shared_gradle_home)
    init_script = work / "clear-repositories.init.gradle"
    init_script.write_text(ISOLATION_INIT, encoding="utf-8")

    # Fixture 0 — staging isolation: a consumer source tree polluted with
    # pre-existing build output, .gradle state and a *real* stale SDK jar must
    # stage only tracked inputs — the stale artifact can never reach the build.
    polluted = work / "polluted-source" / "plugin-template"
    stage_template(work / "polluted-source", repo_root, inputs)
    for directory in ("build/libs", ".gradle"):
        (polluted / directory).mkdir(parents=True, exist_ok=True)
    (polluted / "build" / "libs" / "hello-turboism-plugin-0.1.0.jar").write_bytes(b"stale")
    (polluted / ".gradle" / "fileHashes.bin").write_bytes(b"stale")
    (polluted / "libs" / "turboism-sdk-0.0.0-stale.jar").write_bytes(sdk_jar.read_bytes())
    write_bogus_sdk_jar(polluted / "libs" / "turboism-sdk-9.9.9-foreign.jar")
    (polluted / "libs" / "scratch.txt").write_text("untracked scratch", encoding="utf-8")
    staged = stage_template(work / "staging-isolation", repo_root, inputs, source_root=polluted)
    staged_files = {
        str(p.relative_to(staged)) for p in staged.rglob("*") if p.is_file()
    }
    unexpected = staged_files - set(inputs) - set(WRAPPER_FILES)
    if unexpected:
        fail(f"staged consumer copy contains non-input files: {sorted(unexpected)}")
    if sdk_jars_in_libs(staged):
        fail(f"stale SDK jars leaked into the staged copy: {sdk_jars_in_libs(staged)}")
    print(f"  fixture 'staging-isolation' holds: {len(staged_files)} files, all tracked inputs or wrapper")

    # Fixture 1 — drifted descriptor: entrypoint class absent from the JAR must
    # be rejected by the production PluginJarContract through the verifier CLI.
    drifted = stage_template(work / "drifted-descriptor", repo_root, inputs)
    (drifted / "libs" / f"turboism-sdk-{sdk_version}.jar").write_bytes(sdk_jar.read_bytes())
    descriptor_path = drifted / DESCRIPTOR
    descriptor = json.loads(descriptor_path.read_text(encoding="utf-8"))
    descriptor["entrypoints"] = ["dev.example.hello.AbsentEntrypoint"]
    descriptor_path.write_text(json.dumps(descriptor, indent=2) + "\n", encoding="utf-8")
    build = gradle_build(drifted, work / "drifted-build.log", gradle_home, sdk_version, ["-I", str(init_script)])
    if build.returncode != 0:
        fail(f"selftest fixture 'drifted-descriptor' build unexpectedly failed (see {work / 'drifted-build.log'})")
    result = validate_jar(
        args.java,
        args.runtime_classpath,
        descriptor_path,
        built_jar(drifted),
        work / "drifted-jar-validation.log",
    )
    expect_failure(
        "drifted-descriptor",
        result,
        work / "drifted-jar-validation.log",
        ["PLUGIN_JAR_CONTRACT_PLUGIN_ENTRYPOINT_CLASS_MISSING"],
    )

    # Fixture 2 — manifest rejected by the schema validator itself.
    descriptor["entrypoints"] = ["dev.example.hello.HelloPlugin"]
    descriptor.pop("id")
    descriptor_path.write_text(json.dumps(descriptor, indent=2) + "\n", encoding="utf-8")
    result = validate_descriptor(args.java, args.runtime_classpath, descriptor_path, work / "invalid-meta-validation.log")
    expect_failure("invalid-manifest", result, work / "invalid-meta-validation.log", ["PLUGIN_META_MISSING"])

    # Fixture 3 — SDK absent: staged from the *polluted* source (which carries
    # a real stale SDK jar), so libs/ empty is asserted against staging output;
    # the template's declared mavenLocal() is redirected to an empty repository.
    # Resolution must fail closed with a real dependency-miss whose searched
    # locations list the isolated repo — not an unrelated Gradle failure — and
    # a stale jar or populated ~/.m2 can never mask the missing artifact.
    orphan = stage_template(work / "sdk-absent", repo_root, inputs, source_root=polluted)
    if sdk_jars_in_libs(orphan):
        fail("selftest fixture 'sdk-absent' staged an SDK jar — libs must be empty")
    empty_repo = work / "empty-maven-local"
    empty_repo.mkdir(parents=True, exist_ok=True)
    result = gradle_build(
        orphan,
        work / "sdk-absent-build.log",
        gradle_home,
        sdk_version,
        [f"-Dmaven.repo.local={empty_repo}"],
    )
    expect_failure(
        "sdk-absent",
        result,
        work / "sdk-absent-build.log",
        [
            f"Could not find dev.turboism:sdk:{sdk_version}",
            f"file:{empty_repo}/dev/turboism/sdk/{sdk_version}/",
        ],
    )

    # Fixture 4 — command-echo control: needles must match subprocess output
    # only. The fake log carries the real missing-coordinate marker in the
    # output portion while the exact `file:` searched-location needle exists
    # ONLY in the echoed command line — a parser that scanned the whole log
    # (echo included) would wrongly accept this as a dependency-miss.
    # The "unexpectedly accepted" failure is raised in `else`, outside the
    # except, so it can never be swallowed by the CheckFailure catch itself.
    echo_only = work / "command-echo-only.log"
    echo_only.write_text(
        f"$ ./gradlew -Dmaven.repo.local=file:{empty_repo}/dev/turboism/sdk/{sdk_version}/ build\n"
        f"> Could not find dev.turboism:sdk:{sdk_version}.\n",
        encoding="utf-8",
    )
    try:
        expect_failure(
            "command-echo-only",
            subprocess.CompletedProcess(args=[], returncode=1),
            echo_only,
            [
                f"Could not find dev.turboism:sdk:{sdk_version}",
                f"file:{empty_repo}/dev/turboism/sdk/{sdk_version}/",
            ],
        )
    except CheckFailure:
        print("  fixture 'command-echo-only' rejected a needle present only in the command echo")
    else:
        fail("selftest fixture 'command-echo-only' unexpectedly accepted a command-echo-only log")

    # Fixture 5 — user-level Gradle config is really isolated: an init script in
    # the private Gradle home DOES break the build (positive control), so the
    # clean private home used everywhere above proves no user init script or
    # gradle.properties was loaded.
    poisoned_home = isolated_gradle_home(work / "poisoned-gradle-run", args.shared_gradle_home)
    (poisoned_home / "init.d").mkdir(parents=True, exist_ok=True)
    (poisoned_home / "init.d" / "poison.gradle").write_text(POISON_INIT, encoding="utf-8")
    poisoned = stage_template(work / "user-init-poisoned", repo_root, inputs)
    (poisoned / "libs" / f"turboism-sdk-{sdk_version}.jar").write_bytes(sdk_jar.read_bytes())
    result = gradle_build(
        poisoned,
        work / "user-init-poisoned-build.log",
        poisoned_home,
        sdk_version,
        ["-I", str(init_script)],
    )
    expect_failure(
        "user-init-poisoned",
        result,
        work / "user-init-poisoned-build.log",
        ["poisoned-user-init-marker"],
    )
    for leaked in ("init.gradle", "init.d", "gradle.properties"):
        if (gradle_home / leaked).exists():
            fail(f"isolated Gradle home unexpectedly contains user config: {leaked}")

    print(f"selftest OK: all fixtures failed closed (workspace: {work})")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode", choices=("check", "selftest"))
    parser.add_argument("--repo-root", default=str(ROOT))
    parser.add_argument("--sdk-jar", required=True)
    parser.add_argument("--sdk-version", default=None)
    parser.add_argument("--runtime-classpath", required=True)
    parser.add_argument("--java", default="java")
    parser.add_argument("--work-dir", default=None)
    parser.add_argument(
        "--shared-gradle-home",
        default=None,
        help="Gradle user home of the outer build; its wrapper dists are reused "
        "by the nested build's private Gradle home.",
    )
    args = parser.parse_args()

    if args.work_dir is None:
        args.work_dir = tempfile.mkdtemp(prefix="turboism-external-consumer-")
    Path(args.work_dir).mkdir(parents=True, exist_ok=True)
    if args.shared_gradle_home is not None:
        args.shared_gradle_home = Path(args.shared_gradle_home)

    try:
        if args.mode == "check":
            return check(args)
        return selftest(args)
    except CheckFailure as failure:
        print(f"FAIL {failure}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
