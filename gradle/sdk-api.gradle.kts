import org.gradle.api.tasks.Exec
import org.gradle.jvm.tasks.Jar
import dev.turboism.gradle.internal.VerificationStamps

val sdkApiBaselineTool = layout.projectDirectory.file("scripts/test/sdk_api_baseline_cli.py")
val sdkJarArtifact = project(":sdk").tasks.named<Jar>("jar").flatMap { it.archiveFile }
val sdkApiHelperFiles = fileTree("scripts/test") {
    include("sdk_api_baseline*.py")
}

// gradle/verification.gradle.kts iterates this list from the release gate. Historical
// anchors were removed; the property stays (empty) so the release gate keeps evaluating.
extensions.extraProperties["sdkExactCompatibilityCheckTasks"] = emptyList<Any>()

val checkSdkApiBaselineTool by tasks.registering(Exec::class) {
    group = "verification"
    description = "Runs deterministic SDK API baseline mutation and compatibility selftests."
    workingDir(rootDir)
    inputs.files(sdkApiHelperFiles, "scripts/test/test_sdk_api_baseline.sh")
    VerificationStamps.apply(this)
    commandLine("bash", "scripts/test/test_sdk_api_baseline.sh")
}

val generateSdkApiReport by tasks.registering(Exec::class) {
    group = "verification"
    description = "Generates the current pre-release public SDK surface for review without changing a baseline."
    dependsOn(":sdk:jar")
    val output = layout.buildDirectory.file("reports/sdk-api/current.txt")
    inputs.file(sdkJarArtifact).withPathSensitivity(org.gradle.api.tasks.PathSensitivity.NONE)
    inputs.files(sdkApiHelperFiles)
    outputs.file(output)
    outputs.cacheIf { true }
    doFirst {
        commandLine(
            "python3", sdkApiBaselineTool.asFile.absolutePath, "dump",
            "--input", sdkJarArtifact.get().asFile.absolutePath,
            "--package-prefix", "dev.turboism.sdk",
            "--output", output.get().asFile.absolutePath
        )
    }
}

val sdkApiCurrent = layout.projectDirectory.file("sdk/api-contracts/current.txt")
val sdkApiCurrentReport = layout.buildDirectory.file("reports/sdk-api/current.txt")

val checkSdkApiCurrent by tasks.registering(Exec::class) {
    group = "verification"
    description = "Verifies the public SDK surface matches the reviewed current snapshot."
    dependsOn(generateSdkApiReport)
    inputs.files(sdkApiCurrent, sdkApiCurrentReport, sdkApiHelperFiles)
    workingDir(rootDir)
    commandLine(
        "python3", sdkApiBaselineTool.asFile.absolutePath, "verify-current",
        "--input", sdkApiCurrentReport.get().asFile.absolutePath,
        "--current", sdkApiCurrent.asFile.absolutePath
    )
    VerificationStamps.apply(this)
}

tasks.register("updateSdkApiCurrent") {
    group = "build setup"
    description = "Refreshes the public SDK snapshot for explicit diff review."
    dependsOn(generateSdkApiReport)
    doLast {
        sdkApiCurrentReport.get().asFile.copyTo(sdkApiCurrent.asFile, overwrite = true)
    }
}
