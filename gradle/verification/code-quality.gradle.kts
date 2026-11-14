import org.gradle.api.tasks.Exec
import org.gradle.testing.jacoco.tasks.JacocoReport
import java.io.File
import java.nio.file.Files
import dev.turboism.gradle.CommandLine
import dev.turboism.gradle.internal.JavaToolchainHomes
import dev.turboism.gradle.internal.VerificationStamps
import dev.turboism.gradle.internal.boundary.DuplicateImports

/*
 * Source, package and repository hygiene gates: the rules that decide whether a change may
 * merge at all. They read tracked bytes only, so they stay in the daily gate and never need a
 * host process, a toolchain fork or a network fetch.
 *
 * The Javadoc, naming and asset rules are registered here as leaves; gradle/verification.gradle.kts
 * names them from gradle/gates.txt, and this script never re-opens an aggregate.
 */

val codeQualityJavaHome = JavaToolchainHomes.compilerHome(project, 17)

// The ratchet rule set the gate enforces unless -PturboismCodeQualityRules selects a subset.
val DEFAULT_CODE_QUALITY_RULES = "javadoc,naming,assets,edt-dispatch,controlchars,startup-dialogs"

/*
 * Every catch (Throwable) site must classify through FatalErrors.rethrowIfFatal, a preceding
 * ThreadDeath/VirtualMachineError clause, or an inline @containment-exempt marker, so the
 * fatal-vs-containable policy is decided once instead of per call site.
 */
tasks.register<Exec>("checkThrowableContainment") {
    group = "verification"
    description =
        "Rejects catch (Throwable) sites that contain fatal JVM conditions without an " +
            "auditable escape."
    workingDir(rootDir)
    inputs.files("scripts/test/check_throwable_containment.py")
    inputs.files(
        fileTree("sdk/src/main/java") { include("**/*.java") },
        fileTree("core/contract/src/main/java") { include("**/*.java") },
        fileTree("core/persistence/src/main/java") { include("**/*.java") },
        fileTree("core/compatibility-policy/src/main/java") { include("**/*.java") },
        fileTree("core/event-processor/src/main/java") { include("**/*.java") },
        fileTree("graal-host/src/main/java") { include("**/*.java") },
        fileTree("runtime/src/main/java") { include("**/*.java") },
        fileTree("runtime/src/tooling/java") { include("**/*.java") },
        fileTree("bootstrap/src/main/java") { include("**/*.java") },
        fileTree("plugins") { include("**/src/main/java/**/*.java") },
        fileTree("scripts/verification-sources/templates") { include("**/*.java") }
    )
    VerificationStamps.apply(this)
    commandLine("python3", "scripts/test/check_throwable_containment.py", rootDir.absolutePath)
}

/*
 * Wired into devCheck as a ratchet: the naming and asset rules are enforced absolutely,
 * and Javadoc is enforced as a non-increasing maximum so new undocumented public API is blocked
 * immediately while the existing backlog burns down.
 * -PturboismCodeQualityRules selects a subset; -PturboismCodeQualityStrict=true demands zero.
 */
tasks.register<Exec>("checkCodeQuality") {
    group = "verification"
    description =
        "Rejects new undocumented public API, version-encoding " +
            "type names, retired governance tokens in machine assets, and startup/warning " +
            "dialog handling that bypasses NativeFreeStartup."
    workingDir(rootDir)
    inputs.files("scripts/test/check_code_quality.py")
    inputs.files(fileTree("scripts/test/java") { include("**/*.java") })
    inputs.files(
        fileTree("sdk/src/main/java") { include("**/*.java") },
        fileTree("runtime/src/main/java") { include("**/*.java") },
        fileTree("runtime/src/tooling/java") { include("**/*.java") },
        fileTree("bootstrap/src/main/java") { include("**/*.java") },
        fileTree("core/contract/src/main/java") { include("**/*.java") },
        fileTree("core/persistence/src/main/java") { include("**/*.java") },
        fileTree("core/compatibility-policy/src/main/java") { include("**/*.java") },
        fileTree("core/event-processor/src/main/java") { include("**/*.java") },
        fileTree("graal-host/src/main/java") { include("**/*.java") },
        fileTree("plugins") { include("**/src/main/**/*.java") },
        fileTree("compatibility/cubism") { include("**/*.json") }
    )
    // The controlchars rule also scans these roots for literal control bytes.
    inputs.files(
        fileTree("runtime/src/test/java") { include("**/*.java") },
        fileTree("runtime/src/testFixtures/java") { include("**/*.java") },
        fileTree("core/persistence/src/test/java") { include("**/*.java") },
        fileTree("core/compatibility-policy/src/test/java") { include("**/*.java") },
        fileTree("bootstrap/src/test/java") { include("**/*.java") },
        fileTree("testing/integration-tests/src/test/java") { include("**/*.java") },
        fileTree("buildSrc") { include("**/*.java", "**/*.kts") },
        fileTree("scripts") { include("**/*.py") },
        fileTree("gradle") { include("**/*.kts") },
        "build.gradle.kts",
        "settings.gradle.kts"
    )
    // The startup-dialogs rule scans the validation/testing/probe sources for
    // license and warning answering that bypasses NativeFreeStartup.
    inputs.files(
        fileTree("validation") { include("**/*.java") },
        fileTree("testing") { include("**/*.java") },
        fileTree("sdk/src") { include("**/*.java") }
    )
    val selectedRules = providers.gradleProperty("turboismCodeQualityRules")
    val strict = providers.gradleProperty("turboismCodeQualityStrict")
    inputs.property("turboismCodeQualityRules", selectedRules.orElse(DEFAULT_CODE_QUALITY_RULES))
    inputs.property("turboismCodeQualityStrict", strict.orElse("false"))
    inputs.property("turboismCodeQualityJavaHome", codeQualityJavaHome)
    VerificationStamps.apply(this)
    // The action captures plain values only: a Project, a script property or a Task reference in
    // the closure is what the configuration cache refuses to store.
    val repositoryRoot = rootDir.absolutePath
    val javaHome = codeQualityJavaHome
    val defaultRules = DEFAULT_CODE_QUALITY_RULES
    doFirst {
        environment("TURBOISM_QUALITY_JAVA_HOME", javaHome.get())
        val command = mutableListOf(
            "python3", "scripts/test/check_code_quality.py", repositoryRoot,
            "--rules", selectedRules.getOrElse(defaultRules)
        )
        if (strict.getOrElse("false") != "true") {
            command += "--ratchet"
        }
        commandLine(command)
    }
}

/*
 * Host alias vocabulary. Every alias literal in a reviewed alias namespace, and every literal a
 * VerifiedMemberResolver alias accessor receives, has to be declared by an admitted record of a
 * family that declares a `vocabulary` block, or be pinned by name, file and reason in
 * no-record-aliases.json. Editor-model is the one family whose aliases are inline literals rather
 * than constants, so its admission stays exact: production use against the older exact-use
 * records plus only the fixed invoked subset of the broader 5.3.03 static record. The repository
 * test used a hand-maintained list instead, which drifted until it matched neither the
 * implementation nor the record; the pin file replaces that list.
 */
val checkPackageLayout by tasks.registering(Exec::class) {
    group = "verification"
    description = "Rejects deprecated SDK/runtime packages and package-only production Java shells."
    workingDir(rootDir)
    inputs.file("scripts/test/check_package_layout.py")
    inputs.files(
        fileTree("sdk/src/main/java") { include("**/*.java") },
        fileTree("runtime/src/main/java") { include("**/*.java") },
        fileTree("runtime/src/tooling/java") { include("**/*.java") }
    )
    VerificationStamps.apply(this)
    commandLine("python3", "scripts/test/check_package_layout.py", rootDir.absolutePath)
}

/*
 * The validation probes share one toolbox (jar relocator, child-first fixture
 * loader, code-source printer) compiled from validation/shared/src at build
 * time. Per-probe copies once forked this toolbox, so the basename and the
 * declared class are pinned to the shared path.
 */
tasks.register<Exec>("checkValidationToolsSync") {
    group = "verification"
    description = "Rejects duplicated validation probe tool sources outside validation/shared/src."
    workingDir(rootDir)
    inputs.file("scripts/test/check_validation_tools_sync.py")
    inputs.files(fileTree("validation") { include("**/*.java") })
    VerificationStamps.apply(this)
    commandLine("python3", "scripts/test/check_validation_tools_sync.py", rootDir.absolutePath)
}

val checkDuplicateJavaImports by tasks.registering {
    group = "verification"
    description = "Rejects duplicate Java import declarations within one source file."
    val sources = fileTree(rootDir) {
        include("**/src/**/*.java")
        exclude(".worktrees/**", ".claude/**", "**/build/**")
    }
    inputs.files(sources)
    // The wiring lives in this script, so an edit here still invalidates the gate.
    inputs.file("gradle/verification.gradle.kts")
    val repositoryRoot = rootDir.toPath()
    doLast {
        // The scan itself lives in buildSrc (DuplicateImports), so the import-block grammar is
        // covered by unit tests; this action is the walk, the ordering and the single failure.
        val duplicates = mutableListOf<String>()
        sources.files
            .map { it.toPath() }
            .sortedBy { repositoryRoot.relativize(it).toString().replace('\\', '/') }
            .forEach { source ->
                duplicates += DuplicateImports.duplicates(
                    repositoryRoot.relativize(source).toString().replace('\\', '/'),
                    Files.readAllLines(source)
                )
            }
        if (duplicates.isNotEmpty()) {
            throw GradleException(duplicates.sorted().joinToString("\n", prefix = "Duplicate Java import declarations:\n"))
        }
    }
    VerificationStamps.apply(this)
}

val repositoryHygieneIndex = provider {
    val gitPointer = file(".git")
    when {
        gitPointer.isDirectory -> gitPointer.resolve("index")
        gitPointer.isFile -> gitPointer.readLines()
            .firstOrNull { it.startsWith("gitdir:") }
            ?.substringAfter(':')?.trim()
            ?.let { file(it).resolve("index") }
        else -> null
    }
}

val checkRepositoryHygiene by tasks.registering(Exec::class) {
    group = "verification"
    description = "Rejects tracked secrets, developer-machine paths, and local-only configuration before daily development checks pass."
    workingDir(rootDir)
    inputs.file("scripts/check_remote_hygiene.py")
    inputs.files(fileTree(rootDir) {
        exclude(".git", ".git/**", "**/.git/**")
        exclude("**/build/**", "**/.gradle/**", "**/__pycache__/**", "**/.pytest_cache/**")
        exclude(".worktrees/**", ".codegraph/**", ".claude/**", ".idea/**")
    })
    inputs.files(repositoryHygieneIndex)
    VerificationStamps.apply(this)
    commandLine("python3", "scripts/check_remote_hygiene.py", "--worktree")
}

/*
 * shellcheck runs at error severity only: warnings stay advisory so the gate only
 * blocks on real error-class findings. A missing shellcheck binary fails the gate
 * instead of silently skipping it; CI installs it via apt (see .github/workflows/ci.yml)
 * and .github/CONTRIBUTING.md lists it as a local build requirement.
 */
tasks.register<Exec>("checkShellLint") {
    group = "verification"
    description = "Runs shellcheck and its fail-closed regression fixtures over tracked shell scripts."
    workingDir(rootDir)
    inputs.file("scripts/check_shell_lint.sh")
    inputs.file("scripts/dev/selftest-shell-lint.sh")

    // Both probes are ValueSources: the git and shellcheck commands run at most once per build,
    // and only their plain results become task inputs. Reading them here keeps the input set
    // exact, because the tracked-script list is what decides which files the gate reads.
    //
    // The mapped lambdas capture nothing: a lambda that touched `file()` would capture the script
    // scope, which the configuration cache cannot store. The resolved paths are therefore built
    // from the repository root as a plain Path.
    val repositoryRootPath = rootDir.toPath()
    val trackedShellScriptPaths = CommandLine.value(
        project,
        commandLine = listOf("git", "ls-files", "-z", "*.sh"),
        workingDirectory = rootDir,
    ).map { result -> result.standardOutput.split('\u0000').filter { it.isNotEmpty() } }
    inputs.property("trackedShellScriptPaths", trackedShellScriptPaths)
    inputs.files(trackedShellScriptPaths.map { paths -> paths.map { repositoryRootPath.resolve(it).toFile() } })

    val shellcheckBinPath = CommandLine.value(
        project,
        commandLine = listOf("bash", "-c", "command -v -- \"\${SHELLCHECK_BIN:-shellcheck}\" || true"),
        workingDirectory = rootDir,
    ).map { it.trimmedStandardOutput() }

    inputs.property("shellcheckPath", shellcheckBinPath.map { it.ifEmpty { "absent" } })
    inputs.files(shellcheckBinPath.map { path ->
        if (path.isNotEmpty()) listOf(File(path)) else emptyList()
    })

    VerificationStamps.apply(this) {
        // Absence of the binary now fails the gate, so a successful run always stamps.
        shellcheckBinPath.get().isNotEmpty()
    }
    val repositoryRoot = rootDir.toPath()
    commandLine("bash", "scripts/check_shell_lint.sh")
    doFirst {
        // A nested Gradle exec would capture the script scope; a plain process keeps the action
        // free of build state, which is what the configuration cache requires.
        val selfTest = ProcessBuilder("bash", "scripts/dev/selftest-shell-lint.sh")
            .directory(repositoryRoot.toFile())
            .redirectErrorStream(true)
            .start()
        selfTest.inputStream.bufferedReader().use { it.readText() }
        if (selfTest.waitFor() != 0) {
            throw GradleException("checkShellLintRequiresShellcheck failed: shell lint must fail closed.")
        }
    }
}

/*
 * Negative self-tests for the cleanup and gate-hardening lane. Each one drives a
 * temporary fixture through the real script and asserts the refusal, exit status,
 * or wiring that the fix introduced; a regression here means a gate silently
 * stopped proving its subject.
 */
val checkCleanWorktreeSafety by tasks.registering(Exec::class) {
    group = "verification"
    description = "Proves clean-worktree.sh refuses dirty worktrees and unmerged branches without --force."
    workingDir(rootDir)
    inputs.files(
        "scripts/dev/clean-worktree.sh",
        "scripts/dev/worktree-id-lib.sh",
        "scripts/dev/selftest-clean-worktree.sh"
    )
    VerificationStamps.apply(this)
    commandLine("bash", "scripts/dev/selftest-clean-worktree.sh")
}

val checkCleanupFailurePropagation by tasks.registering(Exec::class) {
    group = "verification"
    description = "Proves the Paseo teardown hook returns non-zero when host artifact cleanup fails."
    workingDir(rootDir)
    inputs.files("scripts/dev/paseo-worktree-teardown.sh", "scripts/dev/selftest-paseo-teardown.sh")
    VerificationStamps.apply(this)
    commandLine("bash", "scripts/dev/selftest-paseo-teardown.sh")
}

val checkHostCleanupEnvMerge by tasks.registering(Exec::class) {
    group = "verification"
    description = "Proves host artifact cleanup merges .env with the process environment and process wins."
    workingDir(rootDir)
    inputs.files("scripts/dev/cleanup_host_artifacts.py", "scripts/dev/selftest-host-cleanup-env.py")
    VerificationStamps.apply(this)
    commandLine("python3", "scripts/dev/selftest-host-cleanup-env.py")
}

/*
 * Gradle compiles buildSrc before the main build but never runs its `test` task, so the
 * execution-count guard's own negative cases (a suite whose entire selection was skipped)
 * would live in a suite nobody executes. Replay them from the daily gate through a nested
 * build (GradleBuild cannot target buildSrc, whose name is reserved).
 */
tasks.register<JacocoReport>("jacocoAggregateReport") {
    group = "verification"
    description = "Aggregates JaCoCo coverage across all Java modules; advisory only, no thresholds."
    dependsOn(subprojects.map { "${it.path}:test" })
    reports {
        html.required.set(true)
        xml.required.set(true)
    }
    subprojects.forEach { sub ->
        executionData(fileTree(sub.layout.buildDirectory.dir("jacoco")) { include("*.exec") })
        val mainSources = sub.extensions.getByType<SourceSetContainer>()["main"]
        sourceDirectories.from(mainSources.allJava.srcDirs)
        classDirectories.from(mainSources.output.classesDirs)
        // :runtime compiles its build-time review tooling into a separate source set; the tests
        // that execute it still run in :runtime:test, so its coverage is attributed too.
        sub.extensions.getByType<SourceSetContainer>().findByName("tooling")?.let { toolingSources ->
            sourceDirectories.from(toolingSources.allJava.srcDirs)
            classDirectories.from(toolingSources.output.classesDirs)
        }
    }
}

/*
 * scripts/ and validation/ probe lint is part of the daily gate: tooling/ruff.toml pins the
 * rule set and the wrapper pins the ruff release, so the baseline cannot drift with
 * ambient tools.
 */
val ruffConfig = "tooling/ruff.toml"

tasks.register<Exec>("checkPythonLint") {
    group = "verification"
    description = "Lints scripts/ and validation/ with the pinned ruff ruleset in tooling/ruff.toml."
    workingDir(rootDir)
    inputs.file(ruffConfig)
    inputs.files("scripts/check_python_lint.py")
    inputs.files(fileTree("scripts") { include("**/*.py") })
    inputs.files(fileTree("validation") { include("**/*.py") })
    VerificationStamps.apply(this)
    commandLine("python3", "scripts/check_python_lint.py")
}

