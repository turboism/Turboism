import org.gradle.api.tasks.Exec
import org.gradle.jvm.tasks.Jar
import dev.turboism.gradle.internal.VerificationStamps

val sdkApiBaselineTool = layout.projectDirectory.file("scripts/test/sdk_api_baseline_cli.py")
val sdkApiReferenceBuilder = layout.projectDirectory.file("scripts/test/build_sdk_api_reference.py")
val sdkExactReferenceBuilder = layout.projectDirectory.file("scripts/test/reconstruct_sdk_gradle_jar.py")

private data class SdkBaselineAnchor(
    val version: Int,
    val commit: String,
    val description: String
)

// Reviewed SDK baseline anchors; each row registers its prepare/check task pair below.
private val sdkBaselineAnchors = listOf(
    SdkBaselineAnchor(2, "3854ef5f05d7dcc49d49bbcf7959dceee0573dd7",
        "Reconstructs the reviewed v2 SDK Gradle JAR from its pinned Git commit in an isolated archive."),
    SdkBaselineAnchor(3, "4b16ebed1f917352542fae1e0e6f3f6ef0d2909a",
        "Reconstructs the reviewed v3 SDK Gradle JAR from its pinned Git commit in an isolated archive."),
    SdkBaselineAnchor(4, "22774994bb3f13fdf027138c1afd7819642113a3",
        "Reconstructs the reviewed v4 SDK Gradle JAR from its pinned Git commit in an isolated archive."),
    SdkBaselineAnchor(5, "7b6a1fa890794396d00b56ab5fa55d88f4399f08",
        "Reconstructs the reviewed v5 SDK Gradle JAR from its pinned Git commit in an isolated archive."),
    SdkBaselineAnchor(6, "07f520755557b941cac1658bed931d21ef609b11",
        "Reconstructs the reviewed v6 SDK Gradle JAR from its pinned Git commit in an isolated archive."),
    SdkBaselineAnchor(7, "46ea5cb303a2a1a9191859885c56c059d1d538b6",
        "Reconstructs the reviewed v7 SDK Gradle JAR from its pinned Git commit in an isolated archive."),
    SdkBaselineAnchor(8, "959ca8c359f24b80c86bb9699c8111d067e75694",
        "Reconstructs the reviewed v8 SDK Gradle JAR from its pinned Git commit in an isolated archive."),
    SdkBaselineAnchor(9, "adc5ab88d8e30be6b7c572ebdcdabad09b251f7d",
        "Reconstructs the reviewed v9 integration SDK from its pinned Git commit."),
    SdkBaselineAnchor(10, "a2031aaa1d6f1233d0cc830db8499f80eba60a36",
        "Reconstructs the reviewed v10 canvas-hint SDK from its pinned Git commit."),
    SdkBaselineAnchor(11, "181e9e9756e5dbb5a028c8f7f2c3c4b4ca76647d",
        "Reconstructs the reviewed v11 inline-label SDK from its pinned Git commit."),
    SdkBaselineAnchor(12, "913ada16a231ee43f22b39c4adbf767bd3cb8a37",
        "Reconstructs the reviewed v12 selection-tool SDK from its pinned Git commit."),
    SdkBaselineAnchor(13, "77b9d6cff4aa7fa6afd6cefbea0f6a8bdffde501",
        "Reconstructs the reviewed v13 contract-convergence SDK from its pinned Git commit."),
    SdkBaselineAnchor(14, "34a68362b7d1b523094e6788f9346339c584f116",
        "Reconstructs the reviewed v14 parameter-read-plane SDK from its pinned Git commit."),
    SdkBaselineAnchor(15, "0003e82bf52daf65939ba0a27f0aa1563d22dc71",
        "Reconstructs the reviewed v15 permission-ids-single-source SDK from its pinned Git commit.")
)

private fun sdkExactBaseline(version: Int) =
    layout.projectDirectory.file("sdk/api-contracts/baselines/sdk-api-v$version-exact.json")

private fun sdkExactReferenceArtifact(version: Int) =
    layout.buildDirectory.file("sdk-api-baseline/v$version-exact-reference.jar")

val sdkHistoryGradleUserHome = providers.gradleProperty("turboismSdkHistoryGradleUserHome")
    .map { file(it).canonicalFile }
    .orElse(provider { gradle.gradleUserHomeDir.canonicalFile })
val sdkJarArtifact = project(":sdk").tasks.named<Jar>("jar").flatMap { it.archiveFile }
val sdkApiHelperFiles = fileTree("scripts/test") {
    include("sdk_api_baseline*.py")
}

// gradle.gradleHomeDir is null under embedded/tooling-API launches, and the launcher
// is bin/gradle.bat on Windows; resolve it at execution time so other builds and
// tasks are unaffected.
fun sdkHistoryGradleLauncher(): String {
    val home = gradle.gradleHomeDir ?: throw GradleException(
        "Gradle home is unavailable in this launch mode; run SDK history reconstruction through the Gradle wrapper"
    )
    val script = if (System.getProperty("os.name", "").lowercase().contains("win")) "gradle.bat" else "gradle"
    return home.resolve("bin/$script").absolutePath
}

tasks.matching { it.name.startsWith("prepareSdk") && it.name.endsWith("ExactReference") }
    .withType<Exec>().configureEach {
        doFirst {
            args("--gradle", sdkHistoryGradleLauncher())
        }
    }

val checkSdkApiBaselineTool by tasks.registering(Exec::class) {
    group = "verification"
    description = "Runs deterministic SDK API baseline mutation and compatibility selftests."
    workingDir(rootDir)
    inputs.files(sdkApiHelperFiles, "scripts/test/test_sdk_api_baseline.sh")
    VerificationStamps.apply(this)
    commandLine("bash", "scripts/test/test_sdk_api_baseline.sh")
}

val checkSdkApiReferenceBuilder by tasks.registering(Exec::class) {
    group = "verification"
    description = "Verifies deterministic SDK reference reconstruction from the immutable Git anchor."
    workingDir(rootDir)
    inputs.files(sdkApiReferenceBuilder, "scripts/test/test_sdk_api_reference_builder.sh")
    VerificationStamps.apply(this)
    commandLine("bash", "scripts/test/test_sdk_api_reference_builder.sh")
}

sdkBaselineAnchors.forEach { anchor ->
    val version = anchor.version
    tasks.register<Exec>("prepareSdkV${version}ExactReference") {
        group = "historical verification"
        description = anchor.description
        workingDir(rootDir)
        inputs.file(sdkExactReferenceBuilder)
        inputs.property("historicalCommit", anchor.commit)
        inputs.property("historicalGradleUserHome", sdkHistoryGradleUserHome.map { it.absolutePath })
        outputs.file(sdkExactReferenceArtifact(version))
        outputs.upToDateWhen { false }
        commandLine(
            "python3", sdkExactReferenceBuilder.asFile.absolutePath,
            "--root", rootDir.absolutePath,
            "--commit", anchor.commit,
            "--output", sdkExactReferenceArtifact(version).get().asFile.absolutePath,
            "--reuse-gradle-user-home", sdkHistoryGradleUserHome.get().absolutePath
        )
    }
}

/*
 * Every anchor registers an exact-API compatibility check. The newest anchor is the
 * live gate: it audits the freshly built :sdk:jar, so it depends on the jar task and
 * reads that artifact as its check input. Older anchors audit their reconstructed
 * historical artifact against itself; their check input and reference are the same
 * file and the SDK jar is not needed.
 */
private val sdkLiveBaselineAnchor = sdkBaselineAnchors.maxBy { it.version }

val sdkExactCompatibilityCheckTasks = sdkBaselineAnchors.map { anchor ->
    val version = anchor.version
    val live = anchor.version == sdkLiveBaselineAnchor.version
    tasks.register<Exec>("checkSdkV${version}ExactApiCompatibility") {
        group = if (live) "release verification" else "historical verification"
        description = if (live) {
            "Verifies the live SDK's canonical API matches the reviewed v$version anchor."
        } else {
            "Audits the reviewed v$version baseline's historical artifact and canonical binding."
        }
        val auditedArtifact = if (live) sdkJarArtifact else sdkExactReferenceArtifact(version)
        dependsOn("prepareSdkV${version}ExactReference")
        if (live) {
            dependsOn(":sdk:jar")
        }
        inputs.files(sdkApiHelperFiles, sdkExactBaseline(version), sdkExactReferenceBuilder,
            sdkExactReferenceArtifact(version), auditedArtifact)
        inputs.property("expectedCommit", anchor.commit)
        outputs.upToDateWhen { false }
        commandLine(
            "python3", sdkApiBaselineTool.asFile.absolutePath, "verify-exact",
            "--input", auditedArtifact.get().asFile.absolutePath,
            "--reference-input", sdkExactReferenceArtifact(version).get().asFile.absolutePath,
            "--package-prefix", "dev.turboism.sdk",
            "--baseline", sdkExactBaseline(version).asFile.absolutePath,
            "--expected-commit", anchor.commit
        )
    }
}

// checkRelease in gradle/verification.gradle.kts consumes this list so the release
// gate gains a new anchored check without naming the version there.
extensions.extraProperties["sdkExactCompatibilityCheckTasks"] = sdkExactCompatibilityCheckTasks

val checkSdkV8Linkage by tasks.registering(Exec::class) {
    group = "verification"
    description = "Compiles history/settings/atlas entry points against v8 and runs that bytecode on the live SDK."
    dependsOn(":sdk:jar", "prepareSdkV8ExactReference")
    inputs.files("scripts/test/test_sdk_v8_linkage.sh", sdkExactReferenceArtifact(8), sdkJarArtifact)
    VerificationStamps.apply(this)
    commandLine("bash", "scripts/test/test_sdk_v8_linkage.sh",
        sdkExactReferenceArtifact(8).get().asFile.absolutePath, sdkJarArtifact.get().asFile.absolutePath)
}

val checkTextureAtlasSdkV7Linkage by tasks.registering(Exec::class) {
    group = "verification"
    description = "Compiles the legacy texture-atlas constructors against v7 and runs that bytecode on the live SDK."
    dependsOn(":sdk:jar", "prepareSdkV7ExactReference")
    inputs.files("scripts/test/test_texture_atlas_sdk_linkage.sh", sdkExactReferenceArtifact(7), sdkJarArtifact)
    VerificationStamps.apply(this)
    commandLine("bash", "scripts/test/test_texture_atlas_sdk_linkage.sh",
        sdkExactReferenceArtifact(7).get().asFile.absolutePath, sdkJarArtifact.get().asFile.absolutePath)
}

/*
 * Anchor-table consistency self-check. v2–v6 were anchored before per-version review
 * documents existed, so they are grandfathered here; the exemption set must match the
 * versions that actually lack a document, which fails closed both when a new anchor
 * forgets its review and when a grandfathered version later gains one.
 */
private val sdkAnchorVersionsWithoutReviewDocs = setOf(2, 3, 4, 5, 6)

val checkSdkBaselineAnchorConsistency by tasks.registering {
    group = "verification"
    description = "Verifies every anchored SDK version has one exact baseline and one review document."
    inputs.dir("sdk/api-contracts/baselines")
    inputs.files(fileTree("sdk/api-contracts") { include("sdk-api-v*-review.md") })
    inputs.property("anchorVersions", sdkBaselineAnchors.map { it.version })
    VerificationStamps.apply(this)
    doLast {
        val versions = sdkBaselineAnchors.map { it.version }
        val duplicated = versions.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        if (duplicated.isNotEmpty()) {
            throw GradleException("SDK anchor table lists duplicate versions: ${duplicated.sorted()}.")
        }
        val baselineName = Regex("sdk-api-v(\\d+)-exact\\.json")
        val baselineVersions = file("sdk/api-contracts/baselines").listFiles().orEmpty()
            .mapNotNull { baselineName.matchEntire(it.name)?.groupValues?.get(1)?.toInt() }
            .toSet()
        if (baselineVersions != versions.toSet()) {
            throw GradleException(
                "SDK anchor table and sdk/api-contracts/baselines disagree: " +
                    "anchors=${versions.sorted()}, baselines=${baselineVersions.sorted()}."
            )
        }
        val missingReviews = versions.filter { version ->
            !file("sdk/api-contracts/sdk-api-v$version-review.md").isFile
        }.toSet()
        if (missingReviews != sdkAnchorVersionsWithoutReviewDocs) {
            throw GradleException(
                "SDK anchors without a review document are ${missingReviews.sorted()}; expected " +
                    "${sdkAnchorVersionsWithoutReviewDocs.sorted()}. Write sdk-api-v<N>-review.md for " +
                    "the new anchor or trim the exemption once a grandfathered version gains one."
            )
        }
    }
}

val generateSdkApiReport by tasks.registering(Exec::class) {
    group = "verification"
    description = "Generates the current pre-release public SDK surface for review without changing a baseline."
    dependsOn(":sdk:jar")
    val output = layout.buildDirectory.file("reports/sdk-api/current.txt")
    inputs.file(sdkJarArtifact)
    inputs.files(sdkApiHelperFiles)
    outputs.file(output)
    doFirst {
        commandLine(
            "python3", sdkApiBaselineTool.asFile.absolutePath, "dump",
            "--input", sdkJarArtifact.get().asFile.absolutePath,
            "--package-prefix", "dev.turboism.sdk",
            "--output", output.get().asFile.absolutePath
        )
    }
}

tasks.register<Exec>("generateSdkApiBaseline") {
    group = "build setup"
    description = "Explicitly generate an SDK API baseline to a caller-selected non-repository output path."
    dependsOn(":sdk:jar")
    val outputPath = providers.gradleProperty("turboismSdkBaselineOutput")
    val role = providers.gradleProperty("turboismSdkBaselineRole")
    val commit = providers.gradleProperty("turboismSdkBaselineCommit")
    doFirst {
        validateSdkBaselineGenerationArguments(outputPath.isPresent, role.isPresent, commit.isPresent)
        val output = file(outputPath.get()).canonicalFile
        rejectReviewedBaselineOutput(output)
        commandLine(
            "python3", sdkApiBaselineTool.asFile.absolutePath, "capture", "--input", sdkJarArtifact.get().asFile.absolutePath,
            "--package-prefix", "dev.turboism.sdk", "--role", role.get(), "--commit", commit.get(), "--output", output.absolutePath
        )
    }
}

private fun validateSdkBaselineGenerationArguments(output: Boolean, role: Boolean, commit: Boolean) {
    if (!output || !role || !commit) {
        throw GradleException(
            "Pass -PturboismSdkBaselineOutput=<path> -PturboismSdkBaselineRole=<pre-phase|exact> " +
                "-PturboismSdkBaselineCommit=<40-hex-commit>."
        )
    }
}

private fun rejectReviewedBaselineOutput(output: java.io.File) {
    val reviewedDirectory = file("sdk/api-contracts/baselines").canonicalFile
    if (output.toPath().startsWith(reviewedDirectory.toPath())) {
        throw GradleException(
            "Baseline generation must write to a caller-selected review path outside sdk/api-contracts/baselines; " +
                "the check lifecycle never overwrites reviewed baselines."
        )
    }
}
