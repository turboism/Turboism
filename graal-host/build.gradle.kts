import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.tasks.Sync
import org.gradle.jvm.tasks.Jar

plugins {
    application
}

application {
    mainClass.set("dev.turboism.graalhost.GraalHostMain")
}

val hostOs = System.getProperty("os.name", "").lowercase()
val hostArch = System.getProperty("os.arch", "").lowercase()
val supportedGraalTargets = setOf(
    "windows-amd64", "linux-amd64", "linux-aarch64", "darwin-aarch64"
)
val detectedGraalTarget = when {
    hostOs.contains("win") && (hostArch == "amd64" || hostArch == "x86_64") -> "windows-amd64"
    hostOs.contains("linux") && (hostArch == "amd64" || hostArch == "x86_64") -> "linux-amd64"
    hostOs.contains("linux") && (hostArch == "aarch64" || hostArch == "arm64") -> "linux-aarch64"
    hostOs.contains("mac") && (hostArch == "aarch64" || hostArch == "arm64") -> "darwin-aarch64"
    else -> null
}
val detectedTarget = detectedGraalTarget
    ?: throw GradleException("Unsupported Graal host platform $hostOs/$hostArch; pass -PgraalHostTarget=<${supportedGraalTargets.joinToString("|")}>.")
val graalTarget = providers.gradleProperty("graalHostTarget").orElse(detectedTarget).get()
if (graalTarget !in supportedGraalTargets) {
    throw GradleException("Unsupported -PgraalHostTarget=$graalTarget")
}
logger.info("Graal host target is $graalTarget" + if (graalTarget == detectedTarget) " (detected)" else " (override)")

val graalVersion = "25.2.4"
val jacksonDependencies = listOf(
    "com.fasterxml.jackson.core:jackson-databind:2.18.9",
    "com.fasterxml.jackson.core:jackson-core:2.18.9",
    "com.fasterxml.jackson.core:jackson-annotations:2.18.9"
)
dependencies {
    implementation(project(":sdk"))
    jacksonDependencies.forEach(::implementation)

    // Keep GraalVM/Truffle out of the Cubism process. The host loads Polyglot
    // reflectively so Turboism remains Java 17 ABI-compatible while this
    // dedicated process is launched with the matching GraalVM 25.2.4 runtime.
    runtimeOnly("org.graalvm.polyglot:polyglot:$graalVersion")
    runtimeOnly("org.graalvm.polyglot:js-isolate-$graalTarget-community:$graalVersion")

}

val windowsPreviewDist by tasks.registering(Sync::class) {
    group = "distribution"
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    description = "Assembles the Windows-amd64 Graal host library closure for the Cubism Preview."
    into(layout.buildDirectory.dir("windows-preview/graal-host"))
    from(tasks.named<Jar>("jar").flatMap { it.archiveFile }) { into("lib") }
    from(configurations.runtimeClasspath) {
        into("lib")
        exclude("js-isolate-*-community-$graalVersion.jar")
    }
    from(
        configurations.detachedConfiguration(
            dependencies.create(
                "org.graalvm.polyglot:js-isolate-windows-amd64-community:$graalVersion"
            )
        )
    ) { into("lib") }
}

tasks.register("checkGraalHostTargetSelection") {
    group = "verification"
    description = "Verifies installDist follows the detected/overridden host target and preview stays Windows-amd64."
    dependsOn(tasks.named("installDist"), windowsPreviewDist)
    doLast {
        val installLibraries = layout.buildDirectory.dir("install/graal-host/lib").get().asFile.list().orEmpty()
        val expectedInstallIsolate = "js-isolate-$graalTarget-community-$graalVersion.jar"
        check(expectedInstallIsolate in installLibraries) {
            "installDist is missing $expectedInstallIsolate"
        }
        val previewLibraries = layout.buildDirectory.dir("windows-preview/graal-host/lib").get().asFile.list().orEmpty()
        val sdkLibrary = project(":sdk").tasks.named<Jar>("jar").get().archiveFile.get().asFile.name
        check(sdkLibrary in previewLibraries) {
            "Windows preview is missing $sdkLibrary"
        }
        val windowsIsolate = "js-isolate-windows-amd64-community-$graalVersion.jar"
        check(windowsIsolate in previewLibraries) {
            "Windows preview is missing $windowsIsolate"
        }
        val wrongPreviewIsolates = previewLibraries.filter {
            it.startsWith("js-isolate-") && it.endsWith("-community-$graalVersion.jar") && it != windowsIsolate
        }
        check(wrongPreviewIsolates.isEmpty()) {
            "Windows preview contains wrong-platform Graal isolates: $wrongPreviewIsolates"
        }
    }
}

tasks.named("check") {
    dependsOn("checkGraalHostTargetSelection")
}
