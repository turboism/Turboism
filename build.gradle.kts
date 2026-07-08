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
    description = "Verify module dependency direction, internal imports, and forbidden packages."
    doLast {
        val forbiddenImportPatterns = listOf(
            "dev.turboism.*.internal.*" to "SDK/public modules must not import runtime internal packages",
            "com.live2d.*" to "SDK/plugins must not import Cubism internal packages (com.live2d)",
            "dev.turboism.core.parameter.*" to "Phase 1/M2 forbids parameter package",
            "dev.turboism.core.mesh.*" to "Phase 1/M2 forbids mesh package",
            "dev.turboism.core.psd.*" to "Phase 1/M2 forbids psd package",
            "dev.turboism.core.mirror.*" to "Phase 1/M2 forbids mirror package"
        )

        var failed = false

        rootProject.subprojects.forEach { subproject ->
            // Dependency direction checks
            val config = subproject.configurations.findByName("compileClasspath")
            if (config != null) {
                val resolved = config.resolvedConfiguration.lenientConfiguration.files
                if (subproject.path == ":sdk") {
                    resolved.forEach { file ->
                        if (file.name.contains("runtime")) {
                            logger.error("SDK must not depend on runtime artifacts: ${file.name} in :sdk")
                            failed = true
                        }
                    }
                }
                if (subproject.path.startsWith(":plugins:")) {
                    val apiDeps = subproject.configurations.findByName("api")?.dependencies?.toList() ?: emptyList<Dependency>()
                    val compileOnlyDeps = subproject.configurations.findByName("compileOnly")?.dependencies?.toList() ?: emptyList<Dependency>()
                    val implDeps = subproject.configurations.findByName("implementation")?.dependencies?.toList() ?: emptyList<Dependency>()
                    val declared: List<Dependency> = apiDeps + compileOnlyDeps + implDeps
                    declared.filterIsInstance<ProjectDependency>().forEach { dep ->
                        if (dep.dependencyProject.path != ":sdk") {
                            logger.error("${subproject.path} must only depend on :sdk, found ${dep.dependencyProject.path}")
                            failed = true
                        }
                    }
                }
            }

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
                        val isRestricted = subproject.path == ":sdk" || subproject.path.startsWith(":plugins:")
                        if (isRestricted) {
                            lines.forEachIndexed { index, line ->
                                forbiddenImportPatterns.forEach { (pattern, message) ->
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

tasks.register<JavaExec>("validatePluginMeta") {
    group = "verification"
    description = "Validate source plugin.json files against v1 schema using the runtime validator."
    dependsOn(":runtime:classes")

    val pluginMetaFiles = files(
        fileTree("plugins") {
            include("**/src/main/resources/META-INF/turboism/plugin.json")
        },
        fileTree("testframework/src/main/resources/fixtures/schema/plugin-meta-v1/valid") {
            include("*.json")
        }
    )

    inputs.files(pluginMetaFiles)
    val runtimeMainClasspath = project(":runtime")
        .extensions
        .getByType<org.gradle.api.tasks.SourceSetContainer>()
        .named("main")
        .get()
        .runtimeClasspath
    classpath = runtimeMainClasspath
    mainClass.set("dev.turboism.core.schema.plugin.PluginMetaValidationCli")

    doFirst {
        val filesToValidate = pluginMetaFiles.files.sortedBy { it.invariantSeparatorsPath }
        if (filesToValidate.isEmpty()) {
            throw GradleException("No source plugin.json files found.")
        }
        setArgs(filesToValidate.map { it.absolutePath })
    }
}

// Wire gate tasks into the check lifecycle for the root project
tasks.named("check") {
    dependsOn("checkModuleBoundaries", "validatePluginMeta")
}
