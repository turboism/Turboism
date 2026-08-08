import org.gradle.api.tasks.Exec
import org.gradle.jvm.tasks.Jar

val sdkApiBaselineTool = layout.projectDirectory.file("scripts/test/sdk_api_baseline_cli.py")
val sdkApiReferenceBuilder = layout.projectDirectory.file("scripts/test/build_sdk_api_reference.py")
val sdkV2ExactBaseline = layout.projectDirectory.file("docs/sdk/baselines/sdk-api-v2-exact.json")
val sdkApiTierPolicy = layout.projectDirectory.file("docs/sdk/baselines/sdk-api-tier-policy-v1.json")
val sdkInitialPreviewLedger = layout.projectDirectory.file("docs/sdk/baselines/sdk-api-initial-preview-v1.json")
val sdkApiTierSelftest = layout.projectDirectory.file("scripts/test/test_sdk_api_tiers.py")
val sdkV2ExactReferenceBuilder = layout.projectDirectory.file("scripts/test/reconstruct_sdk_gradle_jar.py")
val sdkV2ExactCommit = "208442a6b677e55f472af0dd6425a830088e55ce"
val sdkV2ExactReferenceArtifact = layout.buildDirectory.file("sdk-api-baseline/v2-exact-reference.jar")
val sdkJarArtifact = project(":sdk").tasks.named<Jar>("jar").flatMap { it.archiveFile }
val sdkApiHelperFiles = fileTree("scripts/test") {
    include("sdk_api_baseline*.py")
    include("sdk_api_tiers*.py")
}


val checkSdkApiBaselineTool by tasks.registering(Exec::class) {
    group = "verification"
    description = "Runs deterministic SDK API baseline mutation and compatibility selftests."
    workingDir(rootDir)
    inputs.files(
        sdkApiHelperFiles, sdkApiTierSelftest, sdkApiTierPolicy, sdkInitialPreviewLedger,
        "scripts/test/test_sdk_api_baseline.sh"
    )
    commandLine("bash", "scripts/test/test_sdk_api_baseline.sh")
}

val checkSdkApiReferenceBuilder by tasks.registering(Exec::class) {
    group = "verification"
    description = "Verifies deterministic SDK reference reconstruction from the immutable Git anchor."
    workingDir(rootDir)
    inputs.files(sdkApiReferenceBuilder, "scripts/test/test_sdk_api_reference_builder.sh")
    commandLine("bash", "scripts/test/test_sdk_api_reference_builder.sh")
}

val prepareSdkV2ExactReference by tasks.registering(Exec::class) {
    group = "historical verification"
    description = "Reconstructs the reviewed v2 SDK Gradle JAR from its pinned Git commit in an isolated archive."
    workingDir(rootDir)
    inputs.file(sdkV2ExactReferenceBuilder)
    inputs.property("historicalCommit", sdkV2ExactCommit)
    outputs.file(sdkV2ExactReferenceArtifact)
    outputs.upToDateWhen { false }
    commandLine(
        "python3", sdkV2ExactReferenceBuilder.asFile.absolutePath,
        "--root", rootDir.absolutePath,
        "--commit", sdkV2ExactCommit,
        "--gradle", gradle.gradleHomeDir!!.resolve("bin/gradle").absolutePath,
        "--output", sdkV2ExactReferenceArtifact.get().asFile.absolutePath
    )
}

val checkSdkV2ExactApiCompatibility by tasks.registering(Exec::class) {
    group = "historical verification"
    description = "Audits the reviewed v2 baseline's historical artifact and canonical binding."
    dependsOn(prepareSdkV2ExactReference)
    inputs.files(sdkApiHelperFiles, sdkV2ExactBaseline, sdkV2ExactReferenceBuilder, sdkV2ExactReferenceArtifact)
    inputs.property("expectedCommit", sdkV2ExactCommit)
    outputs.upToDateWhen { false }
    commandLine(
        "python3", sdkApiBaselineTool.asFile.absolutePath, "verify-exact",
        "--input", sdkV2ExactReferenceArtifact.get().asFile.absolutePath,
        "--reference-input", sdkV2ExactReferenceArtifact.get().asFile.absolutePath,
        "--package-prefix", "dev.turboism.sdk",
        "--baseline", sdkV2ExactBaseline.asFile.absolutePath,
        "--expected-commit", sdkV2ExactCommit
    )
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
    val reviewedDirectory = file("docs/sdk/baselines").canonicalFile
    if (output.toPath().startsWith(reviewedDirectory.toPath())) {
        throw GradleException(
            "Baseline generation must write to a caller-selected review path outside docs/sdk/baselines; " +
                "the check lifecycle never overwrites reviewed baselines."
        )
    }
}
