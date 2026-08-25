import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.testing.Test
import org.gradle.jvm.tasks.Jar

val resolvedWorktreeId = rootProject.extra["turboismResolvedWorktreeId"] as String

// Single source of truth for the externally published Turboism framework version.
rootProject.extra["turboismFrameworkVersion"] = "0.42.0"
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

allprojects {
    group = "dev.turboism"
    version = if (turboismReleaseBuild) turboismFrameworkVersion else "$turboismFrameworkVersion-SNAPSHOT"
    tasks.withType<Jar>().configureEach {
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
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
