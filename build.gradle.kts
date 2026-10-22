import dev.turboism.gradle.internal.MappingReviewArgsFile
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.SourceSetContainer
import com.bmuschko.gradle.izpack.CreateInstallerTask
import com.bmuschko.gradle.izpack.IzPackPluginExtension

plugins {
    id("java")
    id("java-library")
    // Pinned by the frozen java-installer spec; see packaging/java-installer/installer.gradle.kts
    alias(libs.plugins.izpack)
    id("com.diffplug.spotless") apply false
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
    dependsOn(":runtime:classes", "materializeDraftContracts")
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
        val reviewArgs = MappingReviewArgsFile.readAndDelete(file(cliArgsFile.get()).toPath()).toMutableList()
        val packArgument = reviewArgs.indexOf("--pack") + 1
        if (packArgument > 0 && packArgument < reviewArgs.size) {
            val supplied = reviewArgs[packArgument]
            val generatedPrefix = "build/generated/draft/mapping-packs/"
            val sourcePrefix = "compatibility/cubism/mapping-packs/draft/"
            val name = when {
                supplied.startsWith(generatedPrefix) -> supplied.removePrefix(generatedPrefix)
                supplied.startsWith(sourcePrefix) -> supplied.removePrefix(sourcePrefix)
                else -> null
            }
            if (name != null && Regex("[^/\\\\:]+\\.json").matches(name) && !name.contains("..")) {
                val generated = file(generatedPrefix + name)
                val source = file(sourcePrefix + name)
                if (generated.isFile) {
                    val pack = groovy.json.JsonSlurper().parse(generated) as Map<*, *>
                    if (pack["source"] in setOf("derived-from-verified-record", "exact-5.3.03-classfile-observation-and-semantic-remap")) {
                        throw GradleException("Derived DRAFT pack: edit verification-src/draft-src and regenerate; mapping-review writes observation sources only.")
                    }
                    if (supplied.startsWith(generatedPrefix) && source.isFile) {
                        reviewArgs[packArgument] = sourcePrefix + name
                    }
                }
            }
        }
        setArgs(reviewArgs)
    }
}

apply(from = "gradle/common-java.gradle.kts")
subprojects.forEach { evaluationDependsOn(it.path) }
apply(from = "gradle/module-boundaries.gradle.kts")
apply(from = "gradle/asm-admission.gradle.kts")
apply(from = "gradle/runtime-verification.gradle.kts")
apply(from = "gradle/sdk-api.gradle.kts")
apply(from = "gradle/distribution-preview.gradle.kts")
val materializeVerificationRecords by tasks.registering(Exec::class) {
    group = "build"
    description = "Expands reviewed releases and family sources into byte-preserved verification records."
    workingDir(rootDir)
    inputs.files(
        "scripts/materialize_verification_records.py",
        "scripts/preview/reviewed_host_artifacts.py",
        "compatibility/cubism/reviewed-releases.json",
        fileTree("compatibility/cubism/verification-src") { include("*.json") }
    )
    outputs.dir(rootProject.file("build/generated/verification"))
    outputs.cacheIf { true }
    commandLine("python3", "scripts/materialize_verification_records.py")
}

val materializeDraftContracts by tasks.registering(Exec::class) {
    group = "build"
    description = "Projects DRAFT packs and profiles from materialized records and historical annotations."
    dependsOn(materializeVerificationRecords)
    workingDir(rootDir)
    inputs.files(
        "scripts/materialize_draft_contracts.py",
        "scripts/materialize_verification_records.py",
        "scripts/preview/reviewed_host_artifacts.py",
        "compatibility/cubism/reviewed-releases.json",
        fileTree("compatibility/cubism/draft-src") { include("*.json") },
        fileTree("compatibility/cubism/mapping-packs/draft") { include("*.json") },
        materializeVerificationRecords
    )
    outputs.dir(rootProject.file("build/generated/draft"))
    outputs.cacheIf { true }
    commandLine("python3", "scripts/materialize_draft_contracts.py")
}

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
