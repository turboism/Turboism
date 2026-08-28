import org.gradle.api.tasks.Exec
import org.gradle.jvm.tasks.Jar

val sdkApiBaselineTool = layout.projectDirectory.file("scripts/test/sdk_api_baseline_cli.py")
val sdkApiReferenceBuilder = layout.projectDirectory.file("scripts/test/build_sdk_api_reference.py")
val sdkV2ExactBaseline = layout.projectDirectory.file("sdk/api-contracts/baselines/sdk-api-v2-exact.json")
val sdkV2ExactReferenceBuilder = layout.projectDirectory.file("scripts/test/reconstruct_sdk_gradle_jar.py")
val sdkV2ExactCommit = "3854ef5f05d7dcc49d49bbcf7959dceee0573dd7"
val sdkV2ExactReferenceArtifact = layout.buildDirectory.file("sdk-api-baseline/v2-exact-reference.jar")
val sdkV3ExactBaseline = layout.projectDirectory.file("sdk/api-contracts/baselines/sdk-api-v3-exact.json")
val sdkV3ExactCommit = "4b16ebed1f917352542fae1e0e6f3f6ef0d2909a"
val sdkV3ExactReferenceArtifact = layout.buildDirectory.file("sdk-api-baseline/v3-exact-reference.jar")
val sdkV4ExactBaseline = layout.projectDirectory.file("sdk/api-contracts/baselines/sdk-api-v4-exact.json")
val sdkV4ExactCommit = "22774994bb3f13fdf027138c1afd7819642113a3"
val sdkV4ExactReferenceArtifact = layout.buildDirectory.file("sdk-api-baseline/v4-exact-reference.jar")
val sdkHistoryGradleUserHome = providers.gradleProperty("turboismSdkHistoryGradleUserHome")
    .map { file(it).canonicalFile }
    .orElse(provider { gradle.gradleUserHomeDir.canonicalFile })
val sdkJarArtifact = project(":sdk").tasks.named<Jar>("jar").flatMap { it.archiveFile }
val sdkApiHelperFiles = fileTree("scripts/test") {
    include("sdk_api_baseline*.py")
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

val prepareSdkV2ExactReference by tasks.registering(Exec::class) {
    group = "historical verification"
    description = "Reconstructs the reviewed v2 SDK Gradle JAR from its pinned Git commit in an isolated archive."
    workingDir(rootDir)
    inputs.file(sdkV2ExactReferenceBuilder)
    inputs.property("historicalCommit", sdkV2ExactCommit)
    inputs.property("historicalGradleUserHome", sdkHistoryGradleUserHome.map { it.absolutePath })
    outputs.file(sdkV2ExactReferenceArtifact)
    outputs.upToDateWhen { false }
    commandLine(
        "python3", sdkV2ExactReferenceBuilder.asFile.absolutePath,
        "--root", rootDir.absolutePath,
        "--commit", sdkV2ExactCommit,
        "--gradle", gradle.gradleHomeDir!!.resolve("bin/gradle").absolutePath,
        "--output", sdkV2ExactReferenceArtifact.get().asFile.absolutePath,
        "--reuse-gradle-user-home", sdkHistoryGradleUserHome.get().absolutePath
    )
}

val prepareSdkV3ExactReference by tasks.registering(Exec::class) {
    group = "historical verification"
    description = "Reconstructs the reviewed v3 SDK Gradle JAR from its pinned Git commit in an isolated archive."
    workingDir(rootDir)
    inputs.file(sdkV2ExactReferenceBuilder)
    inputs.property("historicalCommit", sdkV3ExactCommit)
    inputs.property("historicalGradleUserHome", sdkHistoryGradleUserHome.map { it.absolutePath })
    outputs.file(sdkV3ExactReferenceArtifact)
    outputs.upToDateWhen { false }
    commandLine(
        "python3", sdkV2ExactReferenceBuilder.asFile.absolutePath,
        "--root", rootDir.absolutePath,
        "--commit", sdkV3ExactCommit,
        "--gradle", gradle.gradleHomeDir!!.resolve("bin/gradle").absolutePath,
        "--output", sdkV3ExactReferenceArtifact.get().asFile.absolutePath,
        "--reuse-gradle-user-home", sdkHistoryGradleUserHome.get().absolutePath
    )
}

val prepareSdkV4ExactReference by tasks.registering(Exec::class) {
    group = "historical verification"
    description = "Reconstructs the reviewed v4 SDK Gradle JAR from its pinned Git commit in an isolated archive."
    workingDir(rootDir)
    inputs.file(sdkV2ExactReferenceBuilder)
    inputs.property("historicalCommit", sdkV4ExactCommit)
    inputs.property("historicalGradleUserHome", sdkHistoryGradleUserHome.map { it.absolutePath })
    outputs.file(sdkV4ExactReferenceArtifact)
    outputs.upToDateWhen { false }
    commandLine(
        "python3", sdkV2ExactReferenceBuilder.asFile.absolutePath,
        "--root", rootDir.absolutePath,
        "--commit", sdkV4ExactCommit,
        "--gradle", gradle.gradleHomeDir!!.resolve("bin/gradle").absolutePath,
        "--output", sdkV4ExactReferenceArtifact.get().asFile.absolutePath,
        "--reuse-gradle-user-home", sdkHistoryGradleUserHome.get().absolutePath
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

val checkSdkV3ExactApiCompatibility by tasks.registering(Exec::class) {
    group = "historical verification"
    description = "Audits the reviewed v3 baseline's historical artifact and canonical binding."
    dependsOn(prepareSdkV3ExactReference)
    inputs.files(sdkApiHelperFiles, sdkV3ExactBaseline, sdkV2ExactReferenceBuilder, sdkV3ExactReferenceArtifact)
    inputs.property("expectedCommit", sdkV3ExactCommit)
    outputs.upToDateWhen { false }
    commandLine(
        "python3", sdkApiBaselineTool.asFile.absolutePath, "verify-exact",
        "--input", sdkV3ExactReferenceArtifact.get().asFile.absolutePath,
        "--reference-input", sdkV3ExactReferenceArtifact.get().asFile.absolutePath,
        "--package-prefix", "dev.turboism.sdk",
        "--baseline", sdkV3ExactBaseline.asFile.absolutePath,
        "--expected-commit", sdkV3ExactCommit
    )
}

val checkSdkV4ExactApiCompatibility by tasks.registering(Exec::class) {
    group = "historical verification"
    description = "Audits the reviewed v4 baseline's historical artifact and canonical binding."
    dependsOn(prepareSdkV4ExactReference)
    inputs.files(sdkApiHelperFiles, sdkV4ExactBaseline, sdkV2ExactReferenceBuilder, sdkV4ExactReferenceArtifact)
    inputs.property("expectedCommit", sdkV4ExactCommit)
    outputs.upToDateWhen { false }
    commandLine(
        "python3", sdkApiBaselineTool.asFile.absolutePath, "verify-exact",
        "--input", sdkV4ExactReferenceArtifact.get().asFile.absolutePath,
        "--reference-input", sdkV4ExactReferenceArtifact.get().asFile.absolutePath,
        "--package-prefix", "dev.turboism.sdk",
        "--baseline", sdkV4ExactBaseline.asFile.absolutePath,
        "--expected-commit", sdkV4ExactCommit
    )
}

val generateSdkApiReport by tasks.registering(Exec::class) {
    group = "verification"
    description = "Generates the current pre-release public SDK surface for review without changing a baseline."
    dependsOn(":sdk:jar")
    val output = layout.buildDirectory.file("reports/sdk-api/current.txt")
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
