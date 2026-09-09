import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.testing.Test
import org.gradle.jvm.tasks.Jar
import org.gradle.language.jvm.tasks.ProcessResources

val resolvedWorktreeId = rootProject.extra["turboismResolvedWorktreeId"] as String

// Single source of truth for the externally published Turboism framework version.
rootProject.extra["turboismFrameworkVersion"] = "0.43.10"
val turboismFrameworkVersion = rootProject.extra["turboismFrameworkVersion"] as String
val turboismReleaseBuild = providers.gradleProperty("turboismRelease")
    .map { value ->
        if (value != "true" && value != "false") {
            throw GradleException("-PturboismRelease must be true or false")
        }
        value == "true"
    }
    .orElse(false)
    .get()
rootProject.extra["turboismReleaseBuild"] = turboismReleaseBuild

// CI identity is separate from the public product version. Local builds remain unnumbered.
val turboismBuildNumber = providers.environmentVariable("TURBOISM_BUILD_NUMBER").orElse("").get()
val turboismBuildSource = providers.environmentVariable("TURBOISM_SOURCE_REVISION").orElse("").get()
if (turboismBuildNumber.isNotEmpty()) {
    if (!turboismBuildNumber.matches(Regex("[1-9][0-9]*")) ||
        turboismBuildNumber.toLongOrNull()?.let { it < 9007199254740991L } != true ||
        !turboismBuildSource.matches(Regex("[a-f0-9]{40}"))) {
        throw GradleException("Invalid allocated Turboism build identity")
    }
}

allprojects {
    group = "dev.turboism"
    version = if (turboismReleaseBuild) turboismFrameworkVersion else "$turboismFrameworkVersion-SNAPSHOT"
    tasks.withType<Jar>().configureEach {
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
        if (turboismBuildNumber.isNotEmpty()) {
            manifest.attributes(
                "Turboism-Build-Number" to turboismBuildNumber,
                "Turboism-Source-Revision" to turboismBuildSource
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
