import dev.turboism.gradle.CommandLine
import dev.turboism.gradle.internal.MappingReviewArgsFile
import dev.turboism.gradle.internal.VerificationStamps
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
//
// The probe is a ValueSource, not providers.exec: the script runs at most once per build and only
// its plain result enters the configuration cache. The value therefore stays lazy here, and the
// consumers below read it through a Provider instead of blocking configuration.
val worktreeIdOverride = providers.gradleProperty("turboismWorktreeId")
    .orElse(providers.environmentVariable("TURBOISM_WORKTREE_ID"))
val worktreeIdProbe = CommandLine.value(
    project,
    commandLine = listOf("bash", "scripts/dev/worktree-id.sh", "--resolve"),
    workingDirectory = rootProject.layout.projectDirectory.asFile,
    environment = worktreeIdOverride.orNull?.let { mapOf("TURBOISM_WORKTREE_ID" to it) }.orEmpty(),
)

// Best-effort name mangling for the no-bash fallback only; the authoritative
// verdict stays with the script, so consumers fail closed when it cannot run.
fun sanitizeWorktreeId(raw: String): String = raw.lowercase()
    .replace(Regex("[^a-z0-9-]+"), "-")
    .trim('-')
    .replace(Regex("-{2,}"), "-")

val probedWorktreeId = worktreeIdProbe.map { it.trimmedStandardOutput() }
val probedWorktreeIdError = worktreeIdProbe.map { it.standardError.trim() }
val worktreeIdProbeSucceeded = worktreeIdProbe.map { it.succeeded() && it.trimmedStandardOutput().isNotBlank() }

val resolvedWorktreeId = probedWorktreeId.zip(worktreeIdProbeSucceeded) { probed, succeeded ->
    if (succeeded) probed else ""
}.zip(worktreeIdOverride.orElse("")) { probed, override ->
    probed.ifBlank { sanitizeWorktreeId(override) }
}.map { candidate ->
    candidate.ifBlank { sanitizeWorktreeId(rootProject.layout.projectDirectory.asFile.name) }
}.map { candidate ->
    if (candidate.isBlank() || candidate == "." || candidate == "..") "worktree" else candidate
}

val worktreeIdError = worktreeIdProbeSucceeded.zip(resolvedWorktreeId) { succeeded, id -> succeeded to id }
    .zip(probedWorktreeIdError) { (succeeded, id), error ->
        if (succeeded) {
            error
        } else {
            "Worktree ID could not be resolved by scripts/dev/worktree-id.sh " +
                "(bash unavailable or resolution failed); cannot validate $id"
        }
    }

/*
 * The build directory of every module is build/worktree/<id>/<module>, so the ID has to be a
 * concrete string while Gradle configures the build: a Provider cannot name a directory. This is
 * the one read of the probe at configuration time, and it stays configuration-cache compatible
 * because the ValueSource stores only the resolved result. Every other consumer reads the same
 * resolved value from rootProject.extra.
 */
rootProject.extra["turboismResolvedWorktreeId"] = resolvedWorktreeId.get()
// An empty verdict means "the script approved this ID"; consumers test for null, not for "".
rootProject.extra["turboismWorktreeIdError"] = worktreeIdError.get().ifBlank { null }

allprojects {
    repositories {
        mavenCentral()
    }
}

tasks.register<Exec>("checkInstallerLocalization") {
    group = "verification"
    description = "Checks installer and plugin metadata localization parity."
    inputs.files(
        "packaging/windows-installer/test-installer-localization.py",
        fileTree("packaging") { exclude("**/build/**") },
        fileTree("plugins") { include("*/src/main/resources/META-INF/turboism/**") }
    )
    VerificationStamps.apply(this)
    commandLine("python3", "packaging/windows-installer/test-installer-localization.py")
}


// Self-contained: asserts the NSIS script, generated plugin sections, payload
// simulation and uninstall contract against files already in the repository.
tasks.register<Exec>("checkWindowsInstaller") {
    group = "verification"
    description = "Checks the Windows NSIS installer script, payload simulation and uninstall contract."
    dependsOn("checkWindowsInstallerPackaging")
    inputs.files(
        "packaging/windows-installer/test-config-merge.py",
        "gradle/libs.versions.toml",
        fileTree("packaging") { exclude("**/build/**") },
        fileTree("plugins") { include("*/src/main/resources/META-INF/turboism/**", "*/README*.md") }
    )
    VerificationStamps.apply(this)
    commandLine("python3", "packaging/windows-installer/test-config-merge.py")
}

tasks.register<Exec>("checkWindowsInstallerPackaging") {
    group = "verification"
    description = "Exercises the actual thin installer/offline ZIP generators and engine pins."
    inputs.files(
        "packaging/windows-installer/test-installer-packaging.py",
        fileTree("packaging") { exclude("**/build/**") }
    )
    VerificationStamps.apply(this)
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
    dependsOn(":runtime:toolingClasses", "materializeDraftContracts")
    classpath = project(":runtime").extensions.getByType<SourceSetContainer>()
        .named("tooling").get().runtimeClasspath
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
            if (supplied.startsWith(generatedPrefix) || supplied.startsWith(sourcePrefix)) {
                throw GradleException(
                    "Retained DRAFT evidence is a per-family baseline plus override table; " +
                        "edit compatibility/cubism/mapping-packs/draft/{baseline,overrides}/<family>.json " +
                        "and regenerate with materializeDraftContracts."
                )
            }
        }
        setArgs(reviewArgs)
    }
}

apply(from = "gradle/common-java.gradle.kts")
/*
 * Every subproject must be evaluated before the gate scripts below configure, and the reason is
 * not the `@token` collections (those are providers now). gradle/asm-admission.gradle.kts builds
 * `productionMainSourceSets` by reading each subproject's SourceSetContainer while it configures;
 * an unevaluated subproject has no source set yet, so it silently drops out of
 * checkAsmSupplyChainAdmission and checkResolvedBytecodeDependencyGraph. A narrowed list therefore
 * turns the supply-chain gate green over a smaller module set, which is a silent gate shrink.
 *
 * This stays the one configuration-time cost the build accepts. gradle.properties records it as a
 * configuration-cache blocker: the evaluation order itself is not the blocker, but the eager
 * cross-project reads that need it are.
 */
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
        fileTree("compatibility/cubism/mapping-packs/draft") { include("*.json", "baseline/*.json", "overrides/*.json") },
        materializeVerificationRecords
    )
    outputs.dir(rootProject.file("build/generated/draft"))
    outputs.cacheIf { true }
    commandLine("python3", "scripts/materialize_draft_contracts.py")
}

val checkDraftContractDiagnostics by tasks.registering(Exec::class) {
    group = "verification"
    description = "Checks actionable DRAFT declaration errors using tiny synthetic inputs."
    workingDir(rootDir)
    inputs.files(
        "scripts/materialize_draft_contracts.py",
        "scripts/materialize_verification_records.py",
        "scripts/preview/reviewed_host_artifacts.py",
        "scripts/test/test_materialize_draft_contracts.py",
        "scripts/test/reviewed_releases_fixture.py"
    )
    VerificationStamps.apply(this)
    commandLine("python3", "-m", "unittest", "scripts.test.test_materialize_draft_contracts", "-v")
}

// Gate dependencies for the four aggregate verification gates live in exactly one
// place (gradle/verification.gradle.kts), so the scripts that own their member tasks
// must be applied before it and never re-open the aggregates themselves.
apply(from = "gradle/architecture-governance.gradle.kts")
apply(from = "gradle/dependency-inventory.gradle.kts")
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
