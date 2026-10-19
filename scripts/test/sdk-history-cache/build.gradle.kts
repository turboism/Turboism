// Union of external plugins requested by the reviewed v2–v21 SDK anchors.
// Resolve marker metadata and implementation dependencies from the same Plugin
// Portal as the archives. Keep each plugin's graph separate so conflict resolution
// cannot omit an older transitive artifact needed by an earlier anchor.
// These versions belong to history: do not replace them with the live catalog.
buildscript {
    repositories {
        gradlePluginPortal()
    }
    listOf(
        "org.izpack.gradle:org.izpack.gradle.gradle.plugin:3.2.3",
        "com.gradleup.shadow:com.gradleup.shadow.gradle.plugin:8.3.11",
        "com.diffplug.spotless:com.diffplug.spotless.gradle.plugin:8.9.0"
    ).forEachIndexed { index, marker ->
        val pluginCache = configurations.create("historicalPlugin$index") {
            isCanBeConsumed = false
            isCanBeResolved = true
        }
        dependencies.add(pluginCache.name, marker)
    }
}

// Install the JVM variant-disambiguation rules used by the historical Java
// projects (notably Guava's JVM/Android and Caffeine's shaded variants).
plugins {
    `kotlin-dsl`
    // Also resolve the real plugin classpath: Kotlin DSL adds embedded-Kotlin
    // constraints which ordinary dependency configurations do not reproduce.
    id("org.izpack.gradle") version "3.2.3" apply false
    id("com.gradleup.shadow") version "8.3.11" apply false
    id("com.diffplug.spotless") version "8.9.0" apply false
}

repositories {
    mavenCentral()
}

val sdkCompiler = configurations.create("sdkCompiler") {
    isCanBeConsumed = false
    isCanBeResolved = true
}

dependencies {
    // v21 buildSrc compiles convention plugins against this implementation.
    implementation("com.diffplug.spotless:spotless-plugin-gradle:8.9.0")
    add(sdkCompiler.name, "com.google.errorprone:error_prone_core:2.42.0")
}

tasks.register("warmSdkHistoryCache") {
    doLast {
        buildscript.configurations.matching { it.name.startsWith("historicalPlugin") }.forEach {
            it.resolve()
        }
        sdkCompiler.resolve()
        // Mirror v21 buildSrc Kotlin DSL compilation, including compiler plugins.
        configurations.matching { it.isCanBeResolved }.forEach { it.resolve() }
    }
}
