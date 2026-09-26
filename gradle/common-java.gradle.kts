import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.testing.Test
import org.gradle.jvm.tasks.Jar
import org.gradle.language.jvm.tasks.ProcessResources

val resolvedWorktreeId = rootProject.extra["turboismResolvedWorktreeId"] as String

// Single source of truth for the externally published Turboism framework version.
rootProject.extra["turboismFrameworkVersion"] = "0.44.0"
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
            && !project.path.startsWith(":plugins:")) {
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
    // Error Prone 2.42.0 is the last release that runs on the JDK 17 toolchain (2.43.0
    // requires JDK 21). The plugin jar rides the annotation processor path of a forked
    // javac so the JDK compiler internals can be opened to it. Only the deterministic
    // bug patterns below are enabled, each at ERROR: the baseline is zero findings.
    // ReferenceEquality supersedes the removed StringEquality check and still covers
    // String == misuse; IdentityBinaryExpression is the checker behind the requested
    // "IdentityBinaryName" name.
    val errorprone = configurations.create("errorprone") {
        isVisible = false
        isCanBeConsumed = false
        isCanBeResolved = true
        description = "Error Prone javac plugin artifacts"
    }
    dependencies {
        add("errorprone", "com.google.errorprone:error_prone_core:2.42.0")
    }
    tasks.withType<JavaCompile>().configureEach {
        options.release.set(17)
        if (name == "compileJava") {
            options.isFork = true
            options.forkOptions.jvmArgs = options.forkOptions.jvmArgs.orEmpty() + listOf(
                "--add-exports", "jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED",
                "--add-exports", "jdk.compiler/com.sun.tools.javac.file=ALL-UNNAMED",
                "--add-exports", "jdk.compiler/com.sun.tools.javac.main=ALL-UNNAMED",
                "--add-exports", "jdk.compiler/com.sun.tools.javac.model=ALL-UNNAMED",
                "--add-exports", "jdk.compiler/com.sun.tools.javac.parser=ALL-UNNAMED",
                "--add-exports", "jdk.compiler/com.sun.tools.javac.processing=ALL-UNNAMED",
                "--add-exports", "jdk.compiler/com.sun.tools.javac.tree=ALL-UNNAMED",
                "--add-exports", "jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED",
                "--add-opens", "jdk.compiler/com.sun.tools.javac.code=ALL-UNNAMED",
                "--add-opens", "jdk.compiler/com.sun.tools.javac.comp=ALL-UNNAMED"
            )
            options.annotationProcessorPath =
                (options.annotationProcessorPath ?: files()) + errorprone
            options.compilerArgs = options.compilerArgs.orEmpty() + listOf(
                "-XDcompilePolicy=simple",
                "--should-stop=ifError=FLOW",
                "-Xplugin:ErrorProne " +
                    "-XepDisableAllChecks " +
                    "-Xep:EqualsHashCode:ERROR " +
                    "-Xep:ArrayEquals:ERROR " +
                    "-Xep:BoxedPrimitiveEquality:ERROR " +
                    "-Xep:ReferenceEquality:ERROR " +
                    "-Xep:CollectionIncompatibleType:ERROR " +
                    "-Xep:NarrowingCompoundAssignment:ERROR " +
                    "-Xep:IdentityBinaryExpression:ERROR"
            )
        }
    }
    tasks.named<Test>("test") {
        useJUnitPlatform()
        testLogging {
            // CI logs must carry the full assertion message; SHORT hides multi-line details.
            events("failed")
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        }
    }
    dependencies {
        add("testImplementation", platform("org.junit:junit-bom:5.10.3"))
        add("testImplementation", "org.junit.jupiter:junit-jupiter")
        add("testRuntimeOnly", "org.junit.platform:junit-platform-launcher")
    }
}
