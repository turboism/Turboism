import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.testing.Test
import org.gradle.jvm.tasks.Jar
import org.gradle.language.jvm.tasks.ProcessResources

val resolvedWorktreeId = rootProject.extra["turboismResolvedWorktreeId"] as String

// Single source of truth for the externally published Turboism framework version.
rootProject.extra["turboismFrameworkVersion"] = "0.43.11"
val declaredFrameworkVersion = rootProject.extra["turboismFrameworkVersion"] as String
val turboismReleaseBuild = providers.gradleProperty("turboismRelease").map { value ->
    if (value != "true" && value != "false") throw GradleException("-PturboismRelease must be true or false")
    value == "true"
}.orElse(false).get()
rootProject.extra["turboismReleaseBuild"] = turboismReleaseBuild

// Build settings select a target, never allocate a number or authorize publication.
val ciVersion = providers.environmentVariable("TURBOISM_BUILD_VERSION").orElse("").get()
val ciChannel = providers.environmentVariable("TURBOISM_BUILD_CHANNEL").orElse("").get()
val localVersion = providers.gradleProperty("turboismVersion").orElse("").get()
val localChannel = providers.gradleProperty("turboismChannel").orElse("").get()
if (ciVersion.isNotEmpty() && localVersion.isNotEmpty() && ciVersion != localVersion ||
    ciChannel.isNotEmpty() && localChannel.isNotEmpty() && ciChannel != localChannel) {
    throw GradleException("Local overrides disagree with the allocated build identity")
}
if (providers.environmentVariable("TURBOISM_NIGHTLY_VERSION").orElse("").get().isNotEmpty()) {
    throw GradleException("Use TURBOISM_BUILD_VERSION and TURBOISM_BUILD_CHANNEL for every channel")
}
val requestedVersion = ciVersion.ifEmpty { localVersion }
val inferredChannel = when {
    requestedVersion.contains("-0.nightly.") -> "nightly"
    requestedVersion.contains("-") -> "beta"
    else -> "stable"
}
val turboismBuildChannel = ciChannel.ifEmpty { localChannel }.ifEmpty { inferredChannel }
if (turboismBuildChannel !in setOf("stable", "beta", "nightly")) throw GradleException("Unknown Turboism build channel")
val turboismBuildNumber = providers.environmentVariable("TURBOISM_BUILD_NUMBER").orElse("").get()
val corePattern = "(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)"
val turboismFrameworkVersion = requestedVersion.ifEmpty {
    when (turboismBuildChannel) {
        "stable" -> declaredFrameworkVersion
        "beta" -> "$declaredFrameworkVersion-beta.local"
        else -> "$declaredFrameworkVersion-0.nightly.local"
    }
}
val selectedPattern = when (turboismBuildChannel) {
    "stable" -> corePattern
    "beta" -> "$corePattern-(alpha|beta|rc)\\.(0|[1-9][0-9]*|local)"
    else -> "$corePattern-0\\.nightly\\.([1-9][0-9]*|local)"
}
if (turboismFrameworkVersion.length > 96 || !Regex(selectedPattern).matches(turboismFrameworkVersion)) {
    throw GradleException("Version does not match the selected channel")
}
// Local source identity comes from the checkout, never from the update API.
val actualSource = providers.exec {
    commandLine("git", "rev-parse", "HEAD")
    isIgnoreExitValue = true
}.standardOutput.asText.get().trim().ifEmpty { "unknown" }
val dirtySource = providers.exec {
    commandLine("git", "status", "--porcelain", "--untracked-files=no")
    isIgnoreExitValue = true
}.standardOutput.asText.get().isNotBlank()
val suppliedSource = providers.environmentVariable("TURBOISM_SOURCE_REVISION").orElse("").get()
val turboismBuildSource = suppliedSource.ifEmpty { actualSource }
if (suppliedSource.isNotEmpty() && suppliedSource != actualSource) throw GradleException("Build source differs from checked-out HEAD")
if (turboismBuildNumber.isNotEmpty()) {
    if (!turboismReleaseBuild || ciVersion.isEmpty() || ciChannel.isEmpty() || dirtySource ||
        !turboismBuildNumber.matches(Regex("[1-9][0-9]*")) ||
        turboismBuildNumber.toLongOrNull()?.let { it < 9007199254740991L } != true ||
        !turboismBuildSource.matches(Regex("[a-f0-9]{40}")) || turboismFrameworkVersion.endsWith(".local") ||
        turboismBuildChannel == "nightly" && !turboismFrameworkVersion.endsWith(".nightly.$turboismBuildNumber")) {
        throw GradleException("A numbered build requires a clean checkout and consistent version/channel/source identity")
    }
}
val effectiveVersion = if (turboismReleaseBuild) turboismFrameworkVersion else "$turboismFrameworkVersion-SNAPSHOT"
val buildKind = if (turboismBuildNumber.isEmpty()) "local" else "ci"
val displayVersion = "$effectiveVersion ($turboismBuildChannel, " +
    (if (turboismBuildNumber.isEmpty()) "local" else "Build $turboismBuildNumber") +
    (if (dirtySource) ", dirty" else "") + ")"
rootProject.extra["turboismFrameworkVersion"] = turboismFrameworkVersion
rootProject.extra["turboismBuildMetadata"] = linkedMapOf(
    "version" to effectiveVersion, "channel" to turboismBuildChannel,
    "buildNumber" to turboismBuildNumber, "sourceRevision" to turboismBuildSource,
    "buildKind" to buildKind, "dirty" to dirtySource.toString(), "displayVersion" to displayVersion
)
tasks.register("printBuildInfo") {
    group = "help"
    description = "Print the exact local/CI version identity without building, allocating or publishing"
    val metadata = rootProject.extra["turboismBuildMetadata"] as Map<*, *>
    doLast { metadata.forEach { (key, value) -> println("$key=$value") } }
}
allprojects {
    group = "dev.turboism"
    version = effectiveVersion
    tasks.withType<Jar>().configureEach {
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
        // The SDK remains a byte-exact reviewed library, not a numbered product.
        if (project.path != ":sdk" && turboismBuildNumber.isNotEmpty()
            && (!project.path.startsWith(":plugins:") || project.path == ":plugins:core")) {
            manifest.attributes(
                "Turboism-Build-Number" to turboismBuildNumber,
                "Turboism-Source-Revision" to turboismBuildSource,
                "Turboism-Channel" to turboismBuildChannel,
                "Turboism-Version" to turboismFrameworkVersion
            )
        }
    }
}

subprojects {
    apply(plugin = "java")
    apply(plugin = "java-library")
    layout.buildDirectory.set(
        file("${rootProject.layout.buildDirectory.get()}/worktree/$resolvedWorktreeId/${project.name}")
    )
    tasks.named<Jar>("jar") {
        archiveClassifier.set(resolvedWorktreeId)
    }
    if (path.startsWith(":plugins:")) {
        tasks.named<ProcessResources>("processResources") {
            from(layout.projectDirectory.dir(".")) {
                include("README.md", "README_zh.md", "README_ja.md")
                into("META-INF/turboism/readme")
            }
        }
    }
    extensions.configure<JavaPluginExtension> {
        toolchain.languageVersion.set(JavaLanguageVersion.of(17))
    }
    tasks.withType<JavaCompile>().configureEach {
        options.release.set(17)
    }
    tasks.named<Test>("test") {
        useJUnitPlatform()
    }
    dependencies {
        add("testImplementation", platform("org.junit:junit-bom:5.10.3"))
        add("testImplementation", "org.junit.jupiter:junit-jupiter")
        add("testRuntimeOnly", "org.junit.platform:junit-platform-launcher")
    }
}
