plugins {
    id("java")
    id("java-library")
}

allprojects {
    group = "dev.turboism"
    version = "0.1.0-SNAPSHOT"

    repositories {
        mavenCentral()
    }
}

val defaultWorktreeId = providers.exec {
    commandLine("bash", "scripts/dev/worktree-id.sh")
    workingDir(rootProject.layout.projectDirectory)
}.standardOutput.asText.get().trim().ifBlank { rootProject.layout.projectDirectory.asFile.name }

subprojects {
    apply(plugin = "java")
    apply(plugin = "java-library")

    val worktreeId = providers.gradleProperty("turboismWorktreeId")
        .orElse(providers.environmentVariable("TURBOISM_WORKTREE_ID"))
        .orElse(defaultWorktreeId)
        .get()

    layout.buildDirectory.set(file("${rootProject.layout.buildDirectory.get()}/worktree/${worktreeId}/${project.name}"))

    tasks.jar {
        archiveClassifier.set(worktreeId)
    }

    java {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(17))
        }
    }

    tasks.withType<JavaCompile> {
        options.release.set(17)
    }

    tasks.test {
        useJUnitPlatform()
    }

    dependencies {
        "testImplementation"(platform("org.junit:junit-bom:5.10.3"))
        "testImplementation"("org.junit.jupiter:junit-jupiter")
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
    }
}

// Repository-wide quality gates
tasks.register("checkModuleBoundaries") {
    group = "verification"
    description = "Verify module dependency direction and internal imports."
    doLast {
        val forbiddenPatterns = listOf(
            "dev.turboism.*.internal.*" to "SDK/public modules must not import runtime internal packages"
        )
        var failed = false
        rootProject.subprojects.forEach { subproject ->
            val sourceDir = subproject.file("src/main/java")
            if (sourceDir.exists()) {
                sourceDir.walkTopDown()
                    .filter { it.isFile && it.extension == "java" }
                    .forEach { file ->
                        val lines = file.readLines()
                        if (lines.size > 800) {
                            logger.error("Class exceeds 800 lines: ${file.relativeTo(rootProject.projectDir)}")
                            failed = true
                        }
                        val isSdk = subproject.path == ":sdk" || subproject.path.startsWith(":plugins:")
                        if (isSdk) {
                            lines.forEachIndexed { index, line ->
                                forbiddenPatterns.forEach { (pattern, message) ->
                                    if (line.matches(Regex("^import $pattern;"))) {
                                        logger.error("Forbidden import in ${file.relativeTo(rootProject.projectDir)}:${index + 1}: $message")
                                        failed = true
                                    }
                                }
                            }
                        }
                    }
            }
        }
        if (failed) {
            throw GradleException("Module boundary checks failed.")
        }
        logger.lifecycle("Module boundary checks passed.")
    }
}

tasks.register("validatePluginMeta") {
    group = "verification"
    description = "Validate plugin.json files against v1 schema."
    doLast {
        val pluginJsonFiles = rootProject.projectDir.walkTopDown()
            .filter { it.name == "plugin.json" && it.path.contains("META-INF/turboism/") }
            .toList()
        if (pluginJsonFiles.isEmpty()) {
            throw GradleException("No plugin.json files found.")
        }
        pluginJsonFiles.forEach { file ->
            val text = file.readText()
            if (!text.contains("\"format\"")) {
                throw GradleException("plugin.json missing format field: ${file.relativeTo(rootProject.projectDir)}")
            }
            if (!text.contains("\"schemaVersion\"")) {
                throw GradleException("plugin.json missing schemaVersion field: ${file.relativeTo(rootProject.projectDir)}")
            }
            if (!text.contains("turboism.plugin.meta")) {
                throw GradleException("plugin.json has wrong format value: ${file.relativeTo(rootProject.projectDir)}")
            }
        }
        logger.lifecycle("Plugin meta validation passed for ${pluginJsonFiles.size} file(s).")
    }
}

// Wire gate tasks into the check lifecycle for the root project
tasks.named("check") {
    dependsOn("checkModuleBoundaries", "validatePluginMeta")
}
