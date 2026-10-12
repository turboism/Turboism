import dev.turboism.gradle.internal.MappingReviewArgsFile
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.SourceSetContainer
import com.bmuschko.gradle.izpack.CreateInstallerTask
import com.bmuschko.gradle.izpack.IzPackPluginExtension

plugins {
    id("java")
    id("java-library")
    // Pinned by the frozen java-installer spec; see packaging/java-installer/installer.gradle.kts
    id("org.izpack.gradle") version "3.2.3"
    id("com.diffplug.spotless") version "8.9.0" apply false
}

// scripts/dev/worktree-id.sh owns worktree ID resolution and validation.
// --resolve prints the sanitized ID on stdout and its validation verdict on
// stderr without failing, so configuration never fails on a forbidden or
// malformed ID; tasks that consume the ID fail closed on turboismWorktreeIdError.
val worktreeIdOverride = providers.gradleProperty("turboismWorktreeId")
    .orElse(providers.environmentVariable("TURBOISM_WORKTREE_ID"))
val worktreeIdProbe = providers.exec {
    commandLine("bash", "scripts/dev/worktree-id.sh", "--resolve")
    workingDir(rootProject.layout.projectDirectory)
    isIgnoreExitValue = true
    worktreeIdOverride.orNull?.let { environment("TURBOISM_WORKTREE_ID", it) }
}
val worktreeIdProbeSucceeded = runCatching {
    worktreeIdProbe.result.get().exitValue == 0
        && worktreeIdProbe.standardOutput.asText.get().trim().isNotBlank()
}.getOrDefault(false)
val probedWorktreeId = runCatching {
    worktreeIdProbe.standardOutput.asText.get().trim()
}.getOrDefault("")
val probedWorktreeIdError = runCatching {
    worktreeIdProbe.standardError.asText.get().trim()
}.getOrDefault("")

// Best-effort name mangling for the no-bash fallback only; the authoritative
// verdict stays with the script, so consumers fail closed when it cannot run.
fun sanitizeWorktreeId(raw: String): String = raw.lowercase()
    .replace(Regex("[^a-z0-9-]+"), "-")
    .trim('-')
    .replace(Regex("-{2,}"), "-")

val resolvedWorktreeId = probedWorktreeId
    .ifBlank { sanitizeWorktreeId(worktreeIdOverride.orElse("").get()) }
    .ifBlank { sanitizeWorktreeId(rootProject.layout.projectDirectory.asFile.name) }
    .let { if (it.isBlank() || it == "." || it == "..") "worktree" else it }

rootProject.extra["turboismResolvedWorktreeId"] = resolvedWorktreeId
rootProject.extra["turboismWorktreeIdError"] = if (worktreeIdProbeSucceeded) {
    probedWorktreeIdError.ifBlank { null }
} else {
    "Worktree ID could not be resolved by scripts/dev/worktree-id.sh " +
        "(bash unavailable or resolution failed); cannot validate $resolvedWorktreeId"
}

allprojects {
    repositories {
        mavenCentral()
    }
}

tasks.register<Exec>("checkInstallerLocalization") {
    group = "verification"
    description = "Checks installer and plugin metadata localization parity."
    commandLine("python3", "packaging/windows-installer/test-installer-localization.py")
}


// Self-contained: asserts the NSIS script, generated plugin sections, payload
// simulation and uninstall contract against files already in the repository.
tasks.register<Exec>("checkWindowsInstaller") {
    group = "verification"
    description = "Checks the Windows NSIS installer script, payload simulation and uninstall contract."
    dependsOn("checkWindowsInstallerPackaging")
    commandLine("python3", "packaging/windows-installer/test-config-merge.py")
}

tasks.register<Exec>("checkWindowsInstallerPackaging") {
    group = "verification"
    description = "Exercises the actual thin installer/offline ZIP generators and engine pins."
    commandLine("python3", "packaging/windows-installer/test-installer-packaging.py")
}

tasks.register("checkPluginInspectionRuntime") {
    group = "verification"
    description = "Runs the production-backed strict ZIP mutation matrix."
    dependsOn(":testing:integration-tests:pluginInspectionMutationTest")
}

tasks.register<JavaExec>("mappingReview") {
    group = "verification"
    description = "Run the local draft mapping review CLI; apply is dry-run unless --write is passed."
    dependsOn(":runtime:classes")
    classpath = project(":runtime").extensions.getByType<SourceSetContainer>()
        .named("main").get().runtimeClasspath
    mainClass.set("dev.turboism.mapping.draft.MappingReviewCli")
    val cliArgsFile = providers.gradleProperty("turboismMappingReviewArgsFile")
    val legacyCliArgs = providers.gradleProperty("turboismMappingReviewArgs")
    systemProperty("turboism.worktree.id", rootProject.extra["turboismResolvedWorktreeId"] as String)
    doFirst {
        (rootProject.extra["turboismWorktreeIdError"] as String?)?.let { throw GradleException(it) }
        if (legacyCliArgs.isPresent) {
            throw GradleException("-PturboismMappingReviewArgs is unsupported; pass -PturboismMappingReviewArgsFile=<path> instead.")
        }
        if (!cliArgsFile.isPresent || cliArgsFile.get().isBlank()) {
            throw GradleException("Pass -PturboismMappingReviewArgsFile=<path> containing one Base64-encoded UTF-8 argument per line.")
        }
        setArgs(MappingReviewArgsFile.readAndDelete(file(cliArgsFile.get()).toPath()))
    }
}

apply(from = "gradle/common-java.gradle.kts")
apply(from = "gradle/module-boundaries.gradle.kts")

/*
 * Formatting is machine-owned: palantir-java-format via Spotless on every
 * subproject's Java sources. `spotlessApply` rewrites in place;
 * `spotlessCheck` gates commits inside checkCompletedCommit.
 */
subprojects {
    plugins.apply("com.diffplug.spotless")
    extensions.configure<com.diffplug.gradle.spotless.SpotlessExtension>("spotless") {
        java {
            target("src/**/*.java")
            palantirJavaFormat("2.97.0")
        }
    }
}
apply(from = "gradle/asm-admission.gradle.kts")
apply(from = "gradle/runtime-verification.gradle.kts")
apply(from = "gradle/sdk-api.gradle.kts")
apply(from = "gradle/distribution-preview.gradle.kts")
apply(from = "gradle/verification.gradle.kts")
apply(from = "gradle/image-archive-validation.gradle.kts")
apply(from = "packaging/java-installer/installer.gradle.kts")

// ---------------------------------------------------------------------------
// IzPack installer task configuration (pinned org.izpack.gradle:3.2.3 + izpack-ant:5.2.6)
// ---------------------------------------------------------------------------
val installerVersionProvider = providers.gradleProperty("installerVersion")
extensions.configure<IzPackPluginExtension> {
    baseDir.set(layout.buildDirectory.dir("java-installer/izpack"))
    installFile.set(layout.buildDirectory.dir("java-installer/izpack").map { it.file("installer.xml") })
    outputFile.set(installerVersionProvider.map { version ->
        layout.buildDirectory.dir("windows-installer/dist").get().file("TurboismInstaller-$version.jar")
    })
    installerType.set("standard")
    compression.set("default")
}
tasks.named<CreateInstallerTask>("izPackCreateInstaller") {
    group = "packaging"
    description = "Builds the cross-platform Turboism IzPack installer JAR and its SHA-256 sidecar."
}
