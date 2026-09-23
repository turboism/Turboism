#!/usr/bin/env python3
"""Build ``templates/plugin-template`` the way an external consumer does.

Modes:

- ``check`` — copy the template into a unique temporary directory, drop the real
  ``:sdk:jar`` artifact into ``libs/`` under the release-asset name
  ``turboism-sdk-<version>.jar`` (real bytes, real version, no metadata
  rewriting), and run a nested Gradle ``build`` with every declared repository
  cleared through an init script. The produced plugin JAR is then validated
  with the production ``PluginMetaValidationCli`` and
  ``FirstPartyMetadataVerificationCli`` gate tools (runtime-internal; they are
  never part of the template compile classpath). Any build or CLI failure exits
  non-zero.

- ``selftest`` — fail-closed fixtures proving the check stays alive: a drifted
  descriptor whose declared entrypoint is absent from the built JAR, a manifest
  rejected by the schema validator, and a missing SDK resolved against an empty
  Maven repository. Every fixture must fail; a pass exits non-zero.

Isolation notes:

- The positive path clears all repositories, so nothing resolves from
  ``~/.m2`` or the monorepo; the SDK-absent fixture additionally pins
  ``maven.repo.local`` to an empty directory so a stale local Maven cache can
  never mask a missing artifact.
- The nested build reuses the repository's Gradle wrapper distribution and the
  machine's read-only dependency cache. On a cold checkout the wrapper
  downloads the pinned Gradle distribution once into ``GRADLE_USER_HOME`` —
  that is the only cold prerequisite, and it is shared Gradle infrastructure,
  not a credential or a local publish.

Usage: check_external_plugin_template.py {check|selftest} --sdk-jar <path>
       --runtime-classpath <path-list> [--sdk-version <v>] [--java <bin>]
       [--repo-root <dir>] [--work-dir <dir>]
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
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
TEMPLATE = Path("templates/plugin-template")
DESCRIPTOR = Path("src/main/resources/META-INF/turboism/plugin.json")
PLUGIN_JAR_GLOB = "hello-turboism-plugin-*.jar"
META_CLI = "dev.turboism.core.schema.plugin.PluginMetaValidationCli"
JAR_CLI = "dev.turboism.pluginmanagement.FirstPartyMetadataVerificationCli"
GRADLE_TIMEOUT = 900
JAVA_TIMEOUT = 120

ISOLATION_INIT = """// Written by check_external_plugin_template.py: strips every repository the
// template declares so the nested build proves zero-repository resolution.
allprojects { project ->
    project.afterEvaluate { project.repositories.clear() }
}
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


def sdk_version_from(jar: Path) -> str:
    name = jar.name
    if not (name.startswith("sdk-") and name.endswith(".jar")):
        fail(f"--sdk-jar does not look like the :sdk:jar artifact: {name}")
    return name[len("sdk-") : -len(".jar")]


def stage_template(destination: Path, repo_root: Path) -> Path:
    """Copy the public template plus the Gradle wrapper the README tells
    consumers to copy; returns the staged project directory."""
    project = destination / "plugin-template"
    shutil.copytree(repo_root / TEMPLATE, project)
    shutil.copy2(repo_root / "gradlew", project / "gradlew")
    shutil.copy2(repo_root / "gradlew.bat", project / "gradlew.bat")
    wrapper = project / "gradle" / "wrapper"
    wrapper.mkdir(parents=True, exist_ok=True)
    for name in ("gradle-wrapper.jar", "gradle-wrapper.properties"):
        shutil.copy2(repo_root / "gradle" / "wrapper" / name, wrapper / name)
    (project / "gradlew").chmod(
        (project / "gradlew").stat().st_mode | stat.S_IXUSR | stat.S_IXGRP | stat.S_IXOTH
    )
    return project


def gradle_build(project: Path, log: Path, extra_args: list[str]) -> subprocess.CompletedProcess:
    cmd = ["./gradlew", "--console=plain", "build"] + extra_args
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


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def check(args: argparse.Namespace) -> int:
    work = Path(tempfile.mkdtemp(prefix="run-", dir=args.work_dir))
    sdk_jar = Path(args.sdk_jar)
    sdk_version = args.sdk_version or sdk_version_from(sdk_jar)
    project = stage_template(work, Path(args.repo_root))

    # libs/ carries the real :sdk:jar bytes under the release-asset name.
    (project / "libs" / f"turboism-sdk-{sdk_version}.jar").write_bytes(sdk_jar.read_bytes())

    init_script = work / "clear-repositories.init.gradle"
    init_script.write_text(ISOLATION_INIT, encoding="utf-8")

    build = gradle_build(project, work / "gradle-build.log", ["-I", str(init_script)])
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


def expect_failure(label: str, result: subprocess.CompletedProcess, log: Path, needle: str | None = None) -> None:
    if result.returncode == 0:
        fail(f"selftest fixture '{label}' unexpectedly passed (see {log})")
    if needle is not None and needle not in log.read_text(encoding="utf-8", errors="replace"):
        fail(f"selftest fixture '{label}' failed without expected marker {needle!r} (see {log})")
    print(f"  fixture '{label}' failed as expected (exit {result.returncode})")


def selftest(args: argparse.Namespace) -> int:
    work = Path(tempfile.mkdtemp(prefix="selftest-", dir=args.work_dir))
    sdk_jar = Path(args.sdk_jar)
    sdk_version = args.sdk_version or sdk_version_from(sdk_jar)

    # Fixture 1 — drifted descriptor: entrypoint class absent from the JAR must
    # be rejected by the production PluginJarContract through the verifier CLI.
    drifted = stage_template(work / "drifted-descriptor", Path(args.repo_root))
    (drifted / "libs" / f"turboism-sdk-{sdk_version}.jar").write_bytes(sdk_jar.read_bytes())
    init_script = work / "clear-repositories.init.gradle"
    init_script.write_text(ISOLATION_INIT, encoding="utf-8")
    descriptor_path = drifted / DESCRIPTOR
    descriptor = json.loads(descriptor_path.read_text(encoding="utf-8"))
    descriptor["entrypoints"] = ["dev.example.hello.AbsentEntrypoint"]
    descriptor_path.write_text(json.dumps(descriptor, indent=2) + "\n", encoding="utf-8")
    build = gradle_build(drifted, work / "drifted-build.log", ["-I", str(init_script)])
    if build.returncode != 0:
        fail(f"selftest fixture 'drifted-descriptor' build unexpectedly failed (see {work / 'drifted-build.log'})")
    result = validate_jar(
        args.java,
        args.runtime_classpath,
        descriptor_path,
        built_jar(drifted),
        work / "drifted-jar-validation.log",
    )
    expect_failure("drifted-descriptor", result, work / "drifted-jar-validation.log", "PLUGIN_ENTRYPOINT_CLASS_MISSING")

    # Fixture 2 — manifest rejected by the schema validator itself.
    descriptor["entrypoints"] = ["dev.example.hello.HelloPlugin"]
    descriptor.pop("id")
    descriptor_path.write_text(json.dumps(descriptor, indent=2) + "\n", encoding="utf-8")
    result = validate_descriptor(args.java, args.runtime_classpath, descriptor_path, work / "invalid-meta-validation.log")
    expect_failure("invalid-manifest", result, work / "invalid-meta-validation.log")

    # Fixture 3 — SDK absent: libs/ empty and the template's declared
    # mavenLocal() redirected to an empty repository. Resolution must fail
    # closed instead of silently reaching a populated ~/.m2.
    orphan = stage_template(work / "sdk-absent", Path(args.repo_root))
    empty_repo = work / "empty-maven-local"
    empty_repo.mkdir(parents=True, exist_ok=True)
    result = gradle_build(
        orphan,
        work / "sdk-absent-build.log",
        ["--no-daemon", f"-Dmaven.repo.local={empty_repo}"],
    )
    expect_failure("sdk-absent", result, work / "sdk-absent-build.log", "dev.turboism:sdk")

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
    args = parser.parse_args()

    if args.work_dir is None:
        args.work_dir = tempfile.mkdtemp(prefix="turboism-external-consumer-")
    Path(args.work_dir).mkdir(parents=True, exist_ok=True)

    try:
        if args.mode == "check":
            return check(args)
        return selftest(args)
    except CheckFailure as failure:
        print(f"FAIL {failure}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
