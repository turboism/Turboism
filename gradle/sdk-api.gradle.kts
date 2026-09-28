import org.gradle.api.tasks.Exec
import org.gradle.jvm.tasks.Jar

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
        "Reconstructs the reviewed v11 inline-label SDK from its pinned Git commit.")
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
    commandLine("bash", "scripts/test/test_sdk_api_baseline.sh")
}

val checkSdkApiReferenceBuilder by tasks.registering(Exec::class) {
    group = "verification"
    description = "Verifies deterministic SDK reference reconstruction from the immutable Git anchor."
    workingDir(rootDir)
    inputs.files(sdkApiReferenceBuilder, "scripts/test/test_sdk_api_reference_builder.sh")
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

// v2–v10 audit their reconstructed historical artifact; the v11 live-JAR audit and the
// linkage checks below are deliberately hand-written because their inputs differ.
sdkBaselineAnchors.filter { it.version <= 10 }.forEach { anchor ->
    val version = anchor.version
    tasks.register<Exec>("checkSdkV${version}ExactApiCompatibility") {
        group = "historical verification"
        description = "Audits the reviewed v$version baseline's historical artifact and canonical binding."
        dependsOn("prepareSdkV${version}ExactReference")
        inputs.files(sdkApiHelperFiles, sdkExactBaseline(version), sdkExactReferenceBuilder,
            sdkExactReferenceArtifact(version))
        inputs.property("expectedCommit", anchor.commit)
        outputs.upToDateWhen { false }
        commandLine(
            "python3", sdkApiBaselineTool.asFile.absolutePath, "verify-exact",
            "--input", sdkExactReferenceArtifact(version).get().asFile.absolutePath,
            "--reference-input", sdkExactReferenceArtifact(version).get().asFile.absolutePath,
            "--package-prefix", "dev.turboism.sdk",
            "--baseline", sdkExactBaseline(version).asFile.absolutePath,
            "--expected-commit", anchor.commit
        )
    }
}

val checkSdkV11ExactApiCompatibility by tasks.registering(Exec::class) {
    group = "release verification"
    description = "Verifies the live SDK remains byte-exact to the reviewed v11 inline-label anchor."
    dependsOn(":sdk:jar", "prepareSdkV11ExactReference")
    inputs.files(sdkApiHelperFiles, sdkExactBaseline(11), sdkExactReferenceBuilder,
        sdkExactReferenceArtifact(11), sdkJarArtifact)
    inputs.property("expectedCommit", "181e9e9756e5dbb5a028c8f7f2c3c4b4ca76647d")
    outputs.upToDateWhen { false }
    commandLine(
        "python3", sdkApiBaselineTool.asFile.absolutePath, "verify-exact",
        "--input", sdkJarArtifact.get().asFile.absolutePath,
        "--reference-input", sdkExactReferenceArtifact(11).get().asFile.absolutePath,
        "--package-prefix", "dev.turboism.sdk",
        "--baseline", sdkExactBaseline(11).asFile.absolutePath,
        "--expected-commit", "181e9e9756e5dbb5a028c8f7f2c3c4b4ca76647d"
    )
}

val checkSdkV8Linkage by tasks.registering(Exec::class) {
    group = "verification"
    description = "Compiles history/settings/atlas entry points against v8 and runs that bytecode on the live SDK."
    dependsOn(":sdk:jar", "prepareSdkV8ExactReference")
    inputs.files("scripts/test/test_sdk_v8_linkage.sh", sdkExactReferenceArtifact(8), sdkJarArtifact)
    commandLine("bash", "scripts/test/test_sdk_v8_linkage.sh",
        sdkExactReferenceArtifact(8).get().asFile.absolutePath, sdkJarArtifact.get().asFile.absolutePath)
}

val checkTextureAtlasSdkV7Linkage by tasks.registering(Exec::class) {
    group = "verification"
    description = "Compiles the legacy texture-atlas constructors against v7 and runs that bytecode on the live SDK."
    dependsOn(":sdk:jar", "prepareSdkV7ExactReference")
    inputs.files("scripts/test/test_texture_atlas_sdk_linkage.sh", sdkExactReferenceArtifact(7), sdkJarArtifact)
    commandLine("bash", "scripts/test/test_texture_atlas_sdk_linkage.sh",
        sdkExactReferenceArtifact(7).get().asFile.absolutePath, sdkJarArtifact.get().asFile.absolutePath)
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
