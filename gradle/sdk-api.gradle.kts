import org.gradle.api.tasks.Exec
import org.gradle.jvm.tasks.Jar

val sdkApiBaselineTool = layout.projectDirectory.file("scripts/test/sdk_api_baseline_cli.py")
val sdkApiBaselineCore = layout.projectDirectory.file("scripts/test/sdk_api_baseline.py")
val sdkApiReferenceBuilder = layout.projectDirectory.file("scripts/test/build_sdk_api_reference.py")
val sdkPrePhaseBaseline = layout.projectDirectory.file("docs/sdk/baselines/sdk-api-pre-phase-v1.json")
val sdkPhase1ExactBaseline = layout.projectDirectory.file("docs/sdk/baselines/sdk-api-phase1-exact-v1.json")
val sdkV2ExactBaseline = layout.projectDirectory.file("docs/sdk/baselines/sdk-api-v2-exact.json")
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
    description = "Rebuilds the deterministic SDK API reference from the reviewed Stable compatibility anchor."
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
    group = "historical verification"
    description = "Historical audit of the superseded pre-Phase compatibility baseline."
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

val checkSdkPhase1ExactApiCompatibility by tasks.registering(Exec::class) {
    group = "historical verification"
    description = "Historical audit of the superseded Phase 1 exact SDK baseline."
    dependsOn(":sdk:jar")
    inputs.files(sdkApiHelperFiles, sdkPhase1ExactBaseline, sdkJarArtifact)
    doFirst {
        commandLine(
            "python3", sdkApiBaselineTool.asFile.absolutePath, "verify-exact",
            "--input", sdkJarArtifact.get().asFile.absolutePath,
            "--reference-input", sdkJarArtifact.get().asFile.absolutePath,
            "--package-prefix", "dev.turboism.sdk",
            "--baseline", sdkPhase1ExactBaseline.asFile.absolutePath,
            "--expected-commit", "4f7a85c81d24ac7904039adec8bd41c8a7fc66c5"
        )
    }
}

val checkSdkV2ExactApiCompatibility by tasks.registering(Exec::class) {
    group = "verification"
    description = "Verifies the compiled SDK exactly matches the reviewed breaking plugin API v2 baseline."
    dependsOn(":sdk:jar")
    inputs.files(sdkApiHelperFiles, sdkV2ExactBaseline, sdkJarArtifact)
    doFirst {
        commandLine(
            "python3", sdkApiBaselineTool.asFile.absolutePath, "verify-exact",
            "--input", sdkJarArtifact.get().asFile.absolutePath,
            "--reference-input", sdkJarArtifact.get().asFile.absolutePath,
            "--package-prefix", "dev.turboism.sdk",
            "--baseline", sdkV2ExactBaseline.asFile.absolutePath,
            "--expected-commit", "75a9fdc7c43fb3f34be18743fbe55c947fb39e16"
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
