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
}

// scripts/dev/worktree-id.sh owns worktree ID resolution. --resolve only sanitizes,
// so configuration never fails on a forbidden/invalid ID; tasks that consume the ID
// fail closed on turboismWorktreeIdError instead.
val worktreeIdOverride = providers.gradleProperty("turboismWorktreeId")
    .orElse(providers.environmentVariable("TURBOISM_WORKTREE_ID"))
val worktreeIdProbe = providers.exec {
    commandLine("bash", "scripts/dev/worktree-id.sh", "--resolve")
    workingDir(rootProject.layout.projectDirectory)
    isIgnoreExitValue = true
    worktreeIdOverride.orNull?.let { environment("TURBOISM_WORKTREE_ID", it) }
}
val probedWorktreeId = runCatching {
    worktreeIdProbe.result.get().takeIf { it.exitValue == 0 }
        ?.let { worktreeIdProbe.standardOutput.asText.get().trim() }
}.getOrNull().orEmpty()

fun sanitizeWorktreeId(raw: String): String = raw.lowercase()
    .replace(Regex("[^a-z0-9.-]+"), "-")
    .trim('-')
    .replace(Regex("-{2,}"), "-")

val resolvedWorktreeId = probedWorktreeId
    .ifBlank { sanitizeWorktreeId(worktreeIdOverride.orElse("").get()) }
    .ifBlank { sanitizeWorktreeId(rootProject.layout.projectDirectory.asFile.name) }
    .let { if (it.isBlank() || it == "." || it == "..") "worktree" else it }

// Mirrors validate_id in scripts/dev/worktree-id.sh; keep messages identical.
fun worktreeIdValidationError(id: String): String? = when {
    !id.matches(Regex("[a-z][a-z0-9-]{2,63}")) ->
        "Invalid worktree ID: $id (must match [a-z][a-z0-9-]{2,63})"
    id in setOf("test", "tmp", "new", "main-copy", "my-work") ->
        "Forbidden worktree ID: $id"
    else -> null
}

rootProject.extra["turboismResolvedWorktreeId"] = resolvedWorktreeId
rootProject.extra["turboismWorktreeIdError"] = worktreeIdValidationError(resolvedWorktreeId)

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
