#!/usr/bin/env python3
"""Build a minimal external-consumer plugin fixture the way a third-party consumer does.

Modes:

- ``check`` — stage a deterministic minimal external-consumer Gradle project
  plus the repository's Gradle wrapper into a unique temporary directory, drop
  the real ``:sdk:jar`` artifact into ``libs/`` under the release-asset name
  ``turboism-sdk-<version>.jar``, and run a nested Gradle ``build`` with every
  declared repository cleared through an init script. When
  ``:event-processor:jar`` is supplied, a second pass enables
  ``@SubscribeEvent`` and verifies ``GeneratedSubscriberCatalog`` emission.
  Each produced plugin JAR is validated through ``PluginMetaValidationCli``
  and ``FirstPartyMetadataVerificationCli`` (runtime-internal; never on the
  consumer compile classpath).

- ``selftest`` — fail-closed fixtures proving the check stays alive: staging
  isolation against polluted sources, a drifted descriptor whose declared
  entrypoint is absent from the built JAR, a manifest rejected by the schema
  validator, a missing SDK resolved against an empty Maven repository even when
  the polluted source carried a real stale SDK jar, a command-echo control, and
  a poisoned user-level init script proving the isolated Gradle home does not
  inherit user configuration.

Usage: check_external_plugin_template.py {check|selftest} --sdk-jar <path>
       --runtime-classpath <path-list> [--event-processor-jar <path>]
       [--sdk-version <v>] [--java <bin>] [--repo-root <dir>]
       [--work-dir <dir>] [--shared-gradle-home <dir>]
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import shutil
import stat
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
DESCRIPTOR = Path("src/main/resources/META-INF/turboism/plugin.json")
PLUGIN_JAR_GLOB = "external-consumer-plugin-*.jar"
META_CLI = "dev.turboism.core.descriptor.validation.PluginMetaValidationCli"
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
// fixture declares so the nested build proves zero-repository resolution.
allprojects { project ->
    project.afterEvaluate { project.repositories.clear() }
}
"""

POISON_INIT = """// Selftest fixture: a user-level init script must break the build loudly.
allprojects { throw new GradleException("poisoned-user-init-marker") }
"""

BUILD_GRADLE_KTS = """plugins {
    `java-library`
}

group = "dev.example"
version = "0.1.0"

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

repositories {
    mavenLocal()
}

val sdkJars = fileTree("libs") { include("turboism-sdk-*.jar") }
val eventProcessorJars = fileTree("libs") { include("turboism-event-processor-*.jar") }
val turboismSdkVersion = providers.gradleProperty("turboismSdkVersion").orNull

dependencies {
    if (sdkJars.isEmpty) {
        if (turboismSdkVersion.isNullOrBlank()) {
            throw GradleException("No Turboism SDK found in libs/ or turboismSdkVersion.")
        }
        compileOnly("dev.turboism:sdk:$turboismSdkVersion")
        if (eventProcessorJars.isEmpty) {
            annotationProcessor("dev.turboism:event-processor:$turboismSdkVersion")
        } else {
            annotationProcessor(eventProcessorJars)
            annotationProcessor("dev.turboism:sdk:$turboismSdkVersion")
        }
    } else {
        compileOnly(sdkJars)
        if (!eventProcessorJars.isEmpty) {
            annotationProcessor(eventProcessorJars)
            annotationProcessor(sdkJars)
        }
    }
}

tasks.jar {
    archiveBaseName.set("external-consumer-plugin")
}
"""

SETTINGS_GRADLE_KTS = 'rootProject.name = "external-consumer-plugin"\n'

PLUGIN_JAVA_REL = "src/main/java/dev/example/externalconsumer/ExternalConsumerPlugin.java"

PLUGIN_JAVA_TEMPLATE = """package dev.example.externalconsumer;

import dev.turboism.sdk.action.ActionRegistry;
import dev.turboism.sdk.event.TurboismEvent;
import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.sdk.plugin.PluginLogger;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.plugin.TurboismPlugin;

import java.util.function.Consumer;

public class ExternalConsumerPlugin implements TurboismPlugin {

    private PluginContext context;
    private PluginLogger logger;

    @Override
    public void init(PluginContext context) throws Exception {
        this.context = context;
        this.logger = context.logger();
    }

    @Override
    public void enable() throws Exception {
        Registration action = context.actions().register("externalconsumer.ping", new ActionRegistry.Action() {
            @Override
            public String id() {
                return "externalconsumer.ping";
            }

            @Override
            public String label() {
                return context.localization().text("externalconsumer.action.label");
            }

            @Override
            public Consumer<ActionRegistry.ActionContext> handler() {
                return ctx -> context.eventBus().publish(new PingEvent("ping"));
            }
        });
        context.disposableScope().register(action);

        Registration subscription = context.eventBus().subscribe(
            PingEvent.class,
            event -> logger.info("ExternalConsumerPlugin received event: " + event.message())
        );
        context.disposableScope().register(subscription);
    }

    @Override
    public void disable() throws Exception {
    }

    @Override
    public void shutdown() throws Exception {
    }

    __SUBSCRIBE_METHOD__

    public record PingEvent(String message) implements TurboismEvent {
    }
}
"""

SUBSCRIBE_COMMENTED = (
    "// @dev.turboism.sdk.event.SubscribeEvent\n"
    "    // public void onPing(PingEvent event) {\n"
    '    //     logger.info("ExternalConsumerPlugin received event: " + event.message());\n'
    "    // }"
)

SUBSCRIBE_ENABLED = (
    "@dev.turboism.sdk.event.SubscribeEvent\n"
    "    public void onPing(PingEvent event) {\n"
    '        logger.info("ExternalConsumerPlugin received event: " + event.message());\n'
    "    }"
)

CUBISM_VERSIONS_JSON = {
    "format": "turboism.plugin-cubism-versions/v1",
    "plugin": "dev.example.externalconsumer",
    "supportedVersions": ["5.0.00", "5.2.03", "5.3.02", "5.3.03", "5.3.04"],
    "fullFeatureVersions": ["5.0.00", "5.2.03", "5.3.02", "5.3.03", "5.3.04"],
}

MESSAGES_PROPERTIES = (
    "externalconsumer.action.label=Ping\n"
    "plugin.name=External Consumer Plugin\n"
    "plugin.description=Minimal external-consumer contract fixture.\n"
)


class CheckFailure(Exception):
    pass


def fail(message: str) -> None:
    raise CheckFailure(message)


def run(cmd: list[str], cwd: Path, log: Path, timeout: int) -> subprocess.CompletedProcess:
    log.parent.mkdir(parents=True, exist_ok=True)
    with log.open("w", encoding="utf-8") as handle:
        handle.write("$ " + " ".join(cmd) + "\n\n")
        handle.flush()
        return subprocess.run(
            cmd,
            cwd=cwd,
            stdout=handle,
            stderr=subprocess.STDOUT,
            timeout=timeout,
            text=True,
        )


def log_tail(log: Path, lines: int = 40) -> str:
    try:
        return "".join(log.read_text(encoding="utf-8").splitlines(keepends=True)[-lines:])
    except OSError:
        return "<unreadable log>"


def log_output(log: Path) -> str:
    """Subprocess output only — the first ``$ cmd`` echo line is evidence, not
    output, and must never satisfy a needle."""
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


def api_range_for(sdk_version: str) -> str:
    match = re.match(r"^(\d+)\.(\d+)\.(\d+)", sdk_version)
    if not match:
        return "[0.67.0,1.0.0)"
    major = int(match.group(1))
    minor = int(match.group(2))
    return f"[{major}.{minor}.0,{major + 1}.0.0)"


def build_descriptor(sdk_version: str) -> str:
    doc = {
        "format": "turboism.plugin.meta",
        "schemaVersion": 3,
        "id": "dev.example.externalconsumer",
        "name": "External Consumer Plugin",
        "version": "0.1.0",
        "description": "Minimal external-consumer contract fixture.",
        "entrypoints": ["dev.example.externalconsumer.ExternalConsumerPlugin"],
        "turboismApi": api_range_for(sdk_version),
        "authors": [{"name": "Turboism Contributors"}],
        "license": "Apache-2.0",
        "website": "https://github.com/turboism/Turboism",
        "resources": [],
        "i18n": {
            "baseName": "META-INF/turboism/i18n/messages",
            "locales": ["en"],
        },
        "dependencies": [],
        "permissions": [
            {
                "id": "turboism.action.register",
                "scope": "application",
                "reason": "Registers the external consumer ping action.",
            },
            {
                "id": "turboism.event.subscribe",
                "scope": "application",
                "reason": "Subscribes to ping events.",
            },
            {
                "id": "turboism.event.publish",
                "scope": "application",
                "reason": "Publishes ping events.",
            },
        ],
        "capabilities": [],
        "environment": {
            "requiresCubism": False,
            "ui": "none",
        },
        "tags": ["example"],
        "category": "development",
    }
    return json.dumps(doc, indent=2) + "\n"


def fixture_files(sdk_version: str) -> dict[str, str]:
    return {
        "build.gradle.kts": BUILD_GRADLE_KTS,
        "settings.gradle.kts": SETTINGS_GRADLE_KTS,
        "gradle.properties": f"turboismSdkVersion={sdk_version}\n",
        PLUGIN_JAVA_REL: PLUGIN_JAVA_TEMPLATE.replace("__SUBSCRIBE_METHOD__", SUBSCRIBE_COMMENTED),
        str(DESCRIPTOR): build_descriptor(sdk_version),
        "src/main/resources/META-INF/turboism/cubism-versions.json": json.dumps(CUBISM_VERSIONS_JSON, indent=2) + "\n",
        "src/main/resources/META-INF/turboism/i18n/messages.properties": MESSAGES_PROPERTIES,
        "src/main/resources/META-INF/turboism/i18n/messages_en.properties": MESSAGES_PROPERTIES,
    }


def stage_fixture(
    destination: Path,
    repo_root: Path,
    sdk_version: str,
    source_root: Path | None = None,
) -> Path:
    """Write or copy the tracked external-consumer fixture plus the Gradle wrapper."""
    project = destination / "external-consumer-plugin"
    files = fixture_files(sdk_version)
    if source_root is not None:
        for rel in sorted(files):
            target = project / rel
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(source_root / rel, target)
    else:
        for rel, content in sorted(files.items()):
            target = project / rel
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(content, encoding="utf-8")
    (project / "libs").mkdir(parents=True, exist_ok=True)
    for rel in WRAPPER_FILES:
        target = project / rel
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(repo_root / rel, target)
    (project / "gradlew").chmod(
        (project / "gradlew").stat().st_mode | stat.S_IXUSR | stat.S_IXGRP | stat.S_IXOTH
    )
    return project


def isolated_gradle_home(work: Path, shared_gradle_home: Path | None = None) -> Path:
    """Private Gradle user home for the nested build."""
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


def built_jar(project: Path, glob_pattern: str = PLUGIN_JAR_GLOB) -> Path:
    jars = sorted(project.glob(f"build/libs/{glob_pattern}"))
    if len(jars) != 1:
        fail(f"expected exactly one {glob_pattern} under {project / 'build/libs'}, found {jars}")
    return jars[0]


def sdk_jars_in_libs(project: Path) -> list[Path]:
    return sorted((project / "libs").glob("turboism-sdk-*.jar"))


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(65536), b""):
            digest.update(chunk)
    return digest.hexdigest()


def resolve_processor_jar(args: argparse.Namespace, sdk_jar: Path, sdk_version: str) -> Path | None:
    if args.event_processor_jar:
        path = Path(args.event_processor_jar)
        if not path.is_file():
            fail(f"--event-processor-jar does not exist: {path}")
        return path
    candidate = sdk_jar.parents[2] / "event-processor" / "libs" / f"event-processor-{sdk_version}.jar"
    if candidate.is_file():
        return candidate
    return None


def verify_subscriber_catalog(jar_path: Path, expected_provider: str) -> None:
    service_entry = "META-INF/services/dev.turboism.sdk.event.GeneratedSubscriberCatalog"
    class_entry = expected_provider.replace(".", "/") + ".class"
    with zipfile.ZipFile(jar_path) as archive:
        names = set(archive.namelist())
        if service_entry not in names:
            fail(f"built plugin jar {jar_path.name} is missing {service_entry} (event-processor did not run)")
        if class_entry not in names:
            fail(f"built plugin jar {jar_path.name} is missing generated catalog class {class_entry}")
        providers = [
            line.strip()
            for line in archive.read(service_entry).decode("utf-8").splitlines()
            if line.strip() and not line.strip().startswith("#")
        ]
        if providers != [expected_provider]:
            fail(f"unexpected subscriber catalog providers in {jar_path.name}: {providers}")
        if "META-INF/turboism/cubism-versions.json" not in names:
            fail(f"built plugin jar {jar_path.name} is missing META-INF/turboism/cubism-versions.json")


def check(args: argparse.Namespace) -> int:
    work = Path(tempfile.mkdtemp(prefix="run-", dir=args.work_dir))
    sdk_jar = Path(args.sdk_jar)
    sdk_version = args.sdk_version or sdk_version_from(sdk_jar)
    processor_jar = resolve_processor_jar(args, sdk_jar, sdk_version)
    project = stage_fixture(work, Path(args.repo_root), sdk_version)

    stale = sdk_jars_in_libs(project)
    if stale:
        fail(f"staged fixture already carries SDK jars: {stale}")

    init_script = work / "clear-repositories.init.gradle"
    init_script.write_text(ISOLATION_INIT, encoding="utf-8")
    gradle_home = isolated_gradle_home(work, args.shared_gradle_home)

    # 1. SDK-only external consumer mode (no annotation processor in libs/).
    (project / "libs" / f"turboism-sdk-{sdk_version}.jar").write_bytes(sdk_jar.read_bytes())
    build = gradle_build(
        project,
        work / "gradle-build.log",
        gradle_home,
        sdk_version,
        ["-I", str(init_script)],
    )
    if build.returncode != 0:
        fail(f"external-consumer build failed (see {work / 'gradle-build.log'}):\n{log_tail(work / 'gradle-build.log')}")
    jar = built_jar(project)
    jar_name = jar.name
    jar_sha = sha256(jar)

    descriptor = project / DESCRIPTOR
    meta = validate_descriptor(args.java, args.runtime_classpath, descriptor, work / "plugin-meta-validation.log")
    if meta.returncode != 0:
        fail(f"PluginMetaValidationCli rejected the fixture descriptor:\n{log_tail(work / 'plugin-meta-validation.log')}")
    contract = validate_jar(args.java, args.runtime_classpath, descriptor, jar, work / "jar-contract-validation.log")
    if contract.returncode != 0:
        fail(f"FirstPartyMetadataVerificationCli rejected the built plugin jar:\n{log_tail(work / 'jar-contract-validation.log')}")

    if processor_jar is not None:
        # 2. Full SDK + event-processor mode with @SubscribeEvent enabled.
        (project / "libs" / f"turboism-event-processor-{sdk_version}.jar").write_bytes(processor_jar.read_bytes())
        java_path = project / PLUGIN_JAVA_REL
        java_path.write_text(
            PLUGIN_JAVA_TEMPLATE.replace("__SUBSCRIBE_METHOD__", SUBSCRIBE_ENABLED),
            encoding="utf-8",
        )
        shutil.rmtree(project / "build", ignore_errors=True)
        proc_build = gradle_build(
            project,
            work / "gradle-processor-build.log",
            gradle_home,
            sdk_version,
            ["-I", str(init_script)],
        )
        if proc_build.returncode != 0:
            fail(
                f"external-consumer processor build failed (see {work / 'gradle-processor-build.log'}):\n"
                f"{log_tail(work / 'gradle-processor-build.log')}"
            )
        proc_jar = built_jar(project)
        proc_meta = validate_descriptor(
            args.java, args.runtime_classpath, descriptor, work / "processor-meta-validation.log"
        )
        if proc_meta.returncode != 0:
            fail(
                f"PluginMetaValidationCli rejected the processor fixture descriptor:\n"
                f"{log_tail(work / 'processor-meta-validation.log')}"
            )
        proc_contract = validate_jar(
            args.java, args.runtime_classpath, descriptor, proc_jar, work / "processor-jar-validation.log"
        )
        if proc_contract.returncode != 0:
            fail(
                f"FirstPartyMetadataVerificationCli rejected the processor plugin jar:\n"
                f"{log_tail(work / 'processor-jar-validation.log')}"
            )
        verify_subscriber_catalog(
            proc_jar,
            "dev.example.externalconsumer.ExternalConsumerPlugin__TurboismSubscriberCatalog",
        )

    print(f"external-consumer build OK: {jar_name} sha256={jar_sha}")
    print(f"  sdk artifact: {sdk_jar.name} version={sdk_version} sha256={sha256(sdk_jar)}")
    if processor_jar is not None:
        print(f"  event-processor artifact: {processor_jar.name} sha256={sha256(processor_jar)}")
    print("  descriptor: PluginMetaValidationCli PASS; jar contract: FirstPartyMetadataVerificationCli PASS")
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
    """A well-formed JAR containing no SDK classes."""
    with zipfile.ZipFile(path, "w") as archive:
        archive.writestr("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\n")


def selftest(args: argparse.Namespace) -> int:
    work = Path(tempfile.mkdtemp(prefix="selftest-", dir=args.work_dir))
    sdk_jar = Path(args.sdk_jar)
    sdk_version = args.sdk_version or sdk_version_from(sdk_jar)
    repo_root = Path(args.repo_root)
    expected_inputs = set(fixture_files(sdk_version))
    gradle_home = isolated_gradle_home(work, args.shared_gradle_home)
    init_script = work / "clear-repositories.init.gradle"
    init_script.write_text(ISOLATION_INIT, encoding="utf-8")

    # Fixture 0 — staging isolation against polluted source tree.
    polluted = stage_fixture(work / "polluted-source", repo_root, sdk_version)
    for directory in ("build/libs", ".gradle"):
        (polluted / directory).mkdir(parents=True, exist_ok=True)
    (polluted / "build" / "libs" / "external-consumer-plugin-0.1.0.jar").write_bytes(b"stale")
    (polluted / ".gradle" / "fileHashes.bin").write_bytes(b"stale")
    (polluted / "libs" / "turboism-sdk-0.0.0-stale.jar").write_bytes(sdk_jar.read_bytes())
    write_bogus_sdk_jar(polluted / "libs" / "turboism-sdk-9.9.9-foreign.jar")
    (polluted / "libs" / "scratch.txt").write_text("untracked scratch", encoding="utf-8")
    staged = stage_fixture(work / "staging-isolation", repo_root, sdk_version, source_root=polluted)
    staged_files = {
        str(p.relative_to(staged)) for p in staged.rglob("*") if p.is_file()
    }
    unexpected = staged_files - expected_inputs - set(WRAPPER_FILES)
    if unexpected:
        fail(f"staged consumer copy contains non-input files: {sorted(unexpected)}")
    if sdk_jars_in_libs(staged):
        fail(f"stale SDK jars leaked into the staged copy: {sdk_jars_in_libs(staged)}")
    print(f"  fixture 'staging-isolation' holds: {len(staged_files)} files, all tracked inputs or wrapper")

    # Fixture 1 — drifted descriptor: entrypoint class absent from the JAR.
    drifted = stage_fixture(work / "drifted-descriptor", repo_root, sdk_version)
    (drifted / "libs" / f"turboism-sdk-{sdk_version}.jar").write_bytes(sdk_jar.read_bytes())
    descriptor_path = drifted / DESCRIPTOR
    descriptor = json.loads(descriptor_path.read_text(encoding="utf-8"))
    descriptor["entrypoints"] = ["dev.example.externalconsumer.AbsentEntrypoint"]
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
    descriptor["entrypoints"] = ["dev.example.externalconsumer.ExternalConsumerPlugin"]
    descriptor.pop("id")
    descriptor_path.write_text(json.dumps(descriptor, indent=2) + "\n", encoding="utf-8")
    result = validate_descriptor(args.java, args.runtime_classpath, descriptor_path, work / "invalid-meta-validation.log")
    expect_failure("invalid-manifest", result, work / "invalid-meta-validation.log", ["PLUGIN_META_MISSING"])

    # Fixture 3 — SDK absent with redirected empty mavenLocal().
    orphan = stage_fixture(work / "sdk-absent", repo_root, sdk_version, source_root=polluted)
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

    # Fixture 4 — command-echo control.
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

    # Fixture 5 — poisoned user-level init script proves isolated Gradle home.
    poisoned_home = isolated_gradle_home(work / "poisoned-gradle-run", args.shared_gradle_home)
    (poisoned_home / "init.d").mkdir(parents=True, exist_ok=True)
    (poisoned_home / "init.d" / "poison.gradle").write_text(POISON_INIT, encoding="utf-8")
    poisoned = stage_fixture(work / "user-init-poisoned", repo_root, sdk_version)
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
    parser.add_argument("--event-processor-jar", default=None)
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
