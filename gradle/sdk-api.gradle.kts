import org.gradle.api.tasks.Exec
import org.gradle.jvm.tasks.Jar

val sdkApiBaselineTool = layout.projectDirectory.file("scripts/test/sdk_api_baseline_cli.py")
val sdkApiBaselineCore = layout.projectDirectory.file("scripts/test/sdk_api_baseline.py")
val sdkApiReferenceBuilder = layout.projectDirectory.file("scripts/test/build_sdk_api_reference.py")
val sdkPrePhaseBaseline = layout.projectDirectory.file("docs/sdk/baselines/sdk-api-pre-phase-v1.json")
val sdkApiTierPolicy = layout.projectDirectory.file("docs/sdk/baselines/sdk-api-tier-policy-v1.json")
val sdkInitialPreviewLedger = layout.projectDirectory.file("docs/sdk/baselines/sdk-api-initial-preview-v1.json")
val sdkApiTierSelftest = layout.projectDirectory.file("scripts/test/test_sdk_api_tiers.py")
val sdkBaselineAnchorCommit = "fa76a90c236af7f1b393c807176c8e38dac6977e"
val sdkPrePhaseReferenceArtifact = layout.buildDirectory.file("sdk-api-baseline/pre-phase-reference.jar")
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

val prepareSdkPrePhaseApiReference by tasks.registering(Exec::class) {
    group = "verification"
    description = "Rebuilds the deterministic SDK API reference from the immutable pre-Phase Git anchor."
    workingDir(rootDir)
    inputs.file(sdkApiReferenceBuilder)
    inputs.property("anchorCommit", sdkBaselineAnchorCommit)
    outputs.file(sdkPrePhaseReferenceArtifact)
    doFirst {
        val output = sdkPrePhaseReferenceArtifact.get().asFile
        output.parentFile.mkdirs()
        commandLine(
            "python3", sdkApiReferenceBuilder.asFile.absolutePath, "--root", rootDir.absolutePath,
            "--commit", sdkBaselineAnchorCommit, "--output", output.absolutePath
        )
    }
}

val checkSdkPrePhaseApiCompatibility by tasks.registering(Exec::class) {
    group = "verification"
    description = "Verifies the compiled SDK remains compatible with the reviewed pre-Phase API baseline."
    dependsOn(":sdk:jar", prepareSdkPrePhaseApiReference)
    inputs.files(sdkApiHelperFiles)
    inputs.files(sdkPrePhaseBaseline, sdkApiTierPolicy, sdkInitialPreviewLedger, sdkPrePhaseReferenceArtifact, sdkJarArtifact)
    doFirst {
        commandLine(
            "python3", sdkApiBaselineTool.asFile.absolutePath, "verify-compatible",
            "--input", sdkJarArtifact.get().asFile.absolutePath,
            "--reference-input", sdkPrePhaseReferenceArtifact.get().asFile.absolutePath,
            "--package-prefix", "dev.turboism.sdk", "--baseline", sdkPrePhaseBaseline.asFile.absolutePath,
            "--expected-commit", sdkBaselineAnchorCommit, "--tier-policy", sdkApiTierPolicy.asFile.absolutePath,
            "--initial-preview-ledger", sdkInitialPreviewLedger.asFile.absolutePath
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
    val reviewedDirectory = file("docs/sdk/baselines").canonicalFile
    if (output.toPath().startsWith(reviewedDirectory.toPath())) {
        throw GradleException(
            "Baseline generation must write to a caller-selected review path outside docs/sdk/baselines; " +
                "the check lifecycle never overwrites reviewed baselines."
        )
    }
}
