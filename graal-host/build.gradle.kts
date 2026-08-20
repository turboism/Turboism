plugins {
    application
}

application {
    mainClass.set("dev.turboism.graalhost.GraalHostMain")
}

val hostOs = System.getProperty("os.name", "").lowercase()
val hostArch = System.getProperty("os.arch", "").lowercase()
val defaultGraalTarget = when {
    hostOs.contains("win") && (hostArch == "amd64" || hostArch == "x86_64") -> "windows-amd64"
    hostOs.contains("linux") && (hostArch == "amd64" || hostArch == "x86_64") -> "linux-amd64"
    hostOs.contains("linux") && (hostArch == "aarch64" || hostArch == "arm64") -> "linux-aarch64"
    hostOs.contains("mac") && (hostArch == "aarch64" || hostArch == "arm64") -> "darwin-aarch64"
    else -> throw GradleException("Unsupported Graal host platform: $hostOs/$hostArch")
}
val graalTarget = providers.gradleProperty("graalHostTarget").orElse(defaultGraalTarget).get()
val supportedGraalTargets = setOf(
    "windows-amd64", "linux-amd64", "linux-aarch64", "darwin-aarch64"
)
if (graalTarget !in supportedGraalTargets) {
    throw GradleException("Unsupported -PgraalHostTarget=$graalTarget")
}

dependencies {
    implementation("com.fasterxml.jackson.core:jackson-databind:2.18.9")
    implementation("com.fasterxml.jackson.core:jackson-core:2.18.9")
    implementation("com.fasterxml.jackson.core:jackson-annotations:2.18.9")

    // Keep GraalVM/Truffle out of the Cubism process. The host loads Polyglot
    // reflectively so Turboism remains Java 17 ABI-compatible while this
    // dedicated process is launched with GraalVM 25.2.x or newer.
    runtimeOnly("org.graalvm.polyglot:polyglot:25.2.4")
    runtimeOnly("org.graalvm.polyglot:js-isolate-$graalTarget-community:25.2.4")
}
