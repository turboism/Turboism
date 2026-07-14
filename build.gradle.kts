import dev.turboism.gradle.internal.MappingReviewArgsFile
import java.util.jar.JarFile

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

val resolvedWorktreeId = providers.gradleProperty("turboismWorktreeId")
    .orElse(providers.environmentVariable("TURBOISM_WORKTREE_ID"))
    .orElse(defaultWorktreeId)
    .get()

subprojects {
    apply(plugin = "java")
    apply(plugin = "java-library")

    val worktreeId = resolvedWorktreeId

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

            val asmDependencies = subproject.configurations
                .flatMap { configuration -> configuration.dependencies.toList() }
                .filter { dependency -> dependency.group == "org.ow2.asm" }
            asmDependencies.forEach { dependency ->
                val admitted = subproject.path == ":runtime" &&
                    dependency.name == "asm" &&
                    dependency.version == "9.7.1" &&
                    subproject.configurations.getByName("implementation").dependencies.contains(dependency)
                if (!admitted) {
                    logger.error(
                        "Only :runtime implementation(org.ow2.asm:asm:9.7.1) is admitted; " +
                            "found ${dependency.group}:${dependency.name}:${dependency.version} in ${subproject.path}"
                    )
                    failed = true
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
                                if (line.matches(Regex("^import dev\\.turboism\\.distribution(?:\\..*)?;"))) {
                                    logger.error(
                                        "Forbidden distribution import in " +
                                            "${file.relativeTo(rootProject.projectDir)}:${index + 1}"
                                    )
                                    failed = true
                                }
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

tasks.register("checkResolvedBytecodeDependencyGraph") {
    group = "verification"
    description = "Resolve production graphs and reject unadmitted ASM or any Byte Buddy component."
    doLast {
        val violations = mutableListOf<String>()
        allprojects.forEach { candidate ->
            candidate.configurations
                .filter { it.isCanBeResolved && (it.name == "runtimeClasspath" || it.name == "compileClasspath") }
                .forEach { configuration ->
                    val resolution = configuration.incoming.resolutionResult
                    resolution.allDependencies.forEach { dependency ->
                        val requested = dependency.requested
                        if (requested is org.gradle.api.artifacts.component.ModuleComponentSelector) {
                            if (requested.group == "net.bytebuddy" || requested.group.startsWith("net.bytebuddy.")) {
                                violations += "${candidate.path}:${configuration.name} requests forbidden ${requested.group}:${requested.module}:${requested.version}"
                            }
                            if (requested.group == "org.ow2.asm" &&
                                (requested.module != "asm" || requested.version != "9.7.1")) {
                                violations += "${candidate.path}:${configuration.name} requests unadmitted ${requested.group}:${requested.module}:${requested.version}"
                            }
                        }
                    }
                    val external = resolution.allComponents.mapNotNull { component ->
                        val id = component.moduleVersion ?: return@mapNotNull null
                        Triple(id.group, id.name, id.version)
                    }
                    external.forEach { (group, module, version) ->
                        if (group == "net.bytebuddy" || group.startsWith("net.bytebuddy.")) {
                            violations += "${candidate.path}:${configuration.name} contains forbidden $group:$module:$version"
                        }
                        if (group == "org.ow2.asm" && (module != "asm" || version != "9.7.1")) {
                            violations += "${candidate.path}:${configuration.name} contains unadmitted $group:$module:$version"
                        }
                    }
                    if (candidate.path == ":runtime" && configuration.name == "runtimeClasspath") {
                        val asm = external.filter { it.first == "org.ow2.asm" }
                        if (asm != listOf(Triple("org.ow2.asm", "asm", "9.7.1"))) {
                            violations += ":runtime:runtimeClasspath must contain exactly org.ow2.asm:asm:9.7.1; found $asm"
                        }
                    }
                }
        }
        if (violations.isNotEmpty()) {
            throw GradleException("Resolved bytecode dependency graph rejected:\n" + violations.joinToString("\n"))
        }
    }
}

tasks.register("checkAsmDependencyModel") {
    group = "verification"
    description = "Verify Gradle's configured model admits only runtime implementation ASM 9.7.1 and Maven Central."
    doLast {
        val admitted = mutableListOf<String>()
        allprojects.forEach { candidate ->
            candidate.configurations.forEach { configuration ->
                configuration.dependencies.forEach { dependency ->
                    val isAsm = dependency.group == "org.ow2.asm"
                    val isByteBuddy = dependency.group == "net.bytebuddy"
                    if (isByteBuddy) {
                        throw GradleException("Byte Buddy is not admitted: ${candidate.path}:${configuration.name}")
                    }
                    if (isAsm) {
                        val valid = candidate.path == ":runtime" &&
                            configuration.name == "implementation" &&
                            dependency.name == "asm" && dependency.version == "9.7.1"
                        if (!valid) {
                            throw GradleException(
                                "Only :runtime implementation(org.ow2.asm:asm:9.7.1) is admitted; " +
                                    "found ${dependency.group}:${dependency.name}:${dependency.version} " +
                                    "in ${candidate.path}:${configuration.name}"
                            )
                        }
                        admitted += "${candidate.path}:${configuration.name}:${dependency.group}:${dependency.name}:${dependency.version}"
                    }
                }
            }
        }
        if (admitted != listOf(":runtime:implementation:org.ow2.asm:asm:9.7.1")) {
            throw GradleException("Expected exactly one admitted ASM dependency; found $admitted")
        }

        allprojects.forEach { candidate ->
            candidate.repositories.forEach { repository ->
                val maven = repository as? org.gradle.api.artifacts.repositories.MavenArtifactRepository
                    ?: throw GradleException("Only Maven Central is admitted; found ${repository.name} in ${candidate.path}")
                val url = maven.url.toString().trimEnd('/')
                if (url !in setOf("https://repo.maven.apache.org/maven2", "https://repo1.maven.org/maven2")) {
                    throw GradleException("Only Maven Central is admitted; found $url in ${candidate.path}")
                }
            }
        }
    }
}

val productionMainSourceSets = subprojects.mapNotNull { candidate ->
    candidate.extensions.findByType<org.gradle.api.tasks.SourceSetContainer>()
        ?.findByName(org.gradle.api.tasks.SourceSet.MAIN_SOURCE_SET_NAME)
        ?.takeIf { sourceSet -> sourceSet.allSource.files.any { it.isFile } }
        ?.let { candidate to it }
}

tasks.register<Exec>("checkAsmSupplyChainAdmission") {
    group = "verification"
    description = "Verify the exact ASM 9.7.1 dependency, API boundary, and supply-chain evidence."
    environment("TURBOISM_SKIP_GRADLE_MODEL", "1")
    dependsOn("checkAsmDependencyModel", "checkResolvedBytecodeDependencyGraph")
    dependsOn(productionMainSourceSets.map { (candidate, sourceSet) ->
        candidate.tasks.named(sourceSet.classesTaskName)
    })
    environment(
        "TURBOISM_PRODUCTION_CLASSES_DIRS",
        productionMainSourceSets
            .flatMap { (_, sourceSet) -> sourceSet.output.classesDirs.files }
            .joinToString(File.pathSeparator) { it.absolutePath }
    )
    commandLine("bash", "scripts/test/test_asm_supply_chain_admission.sh")
}

tasks.register<JavaExec>("mappingReview") {
    group = "verification"
    description = "Run the local draft mapping review CLI; apply is dry-run unless --write is passed."
    dependsOn(":runtime:classes")

    val runtimeMainClasspath = project(":runtime")
        .extensions
        .getByType<org.gradle.api.tasks.SourceSetContainer>()
        .named("main")
        .get()
        .runtimeClasspath
    classpath = runtimeMainClasspath
    mainClass.set("dev.turboism.mapping.draft.MappingReviewCli")

    val cliArgsFile = providers.gradleProperty("turboismMappingReviewArgsFile")
    val legacyCliArgs = providers.gradleProperty("turboismMappingReviewArgs")
    systemProperty("turboism.worktree.id", resolvedWorktreeId)
    doFirst {
        if (legacyCliArgs.isPresent) {
            throw GradleException(
                "-PturboismMappingReviewArgs is unsupported; pass -PturboismMappingReviewArgsFile=<path> instead."
            )
        }
        if (!cliArgsFile.isPresent || cliArgsFile.get().isBlank()) {
            throw GradleException(
                "Pass -PturboismMappingReviewArgsFile=<path> containing one Base64-encoded UTF-8 argument per line."
            )
        }
        setArgs(MappingReviewArgsFile.readAndDelete(file(cliArgsFile.get()).toPath()))
    }
}

tasks.register<JavaExec>("verifyStaticHostSelectors") {
    group = "verification"
    description = "Verify an exact-version host JAR against a tracked static verification record."
    dependsOn(":runtime:classes")

    val recordPath = providers.gradleProperty("turboismStaticVerificationRecord")
    val artifactPath = providers.gradleProperty("turboismHostArtifact")
    val runtimeMainClasspath = project(":runtime")
        .extensions
        .getByType<org.gradle.api.tasks.SourceSetContainer>()
        .named("main")
        .get()
        .runtimeClasspath
    classpath = runtimeMainClasspath
    mainClass.set("dev.turboism.mapping.verification.StaticVerificationCli")

    doFirst {
        if (!recordPath.isPresent || !artifactPath.isPresent) {
            throw GradleException(
                "Pass -PturboismStaticVerificationRecord=<record.json> " +
                    "-PturboismHostArtifact=<Live2D_Cubism.jar>"
            )
        }
        args(recordPath.get(), artifactPath.get())
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
        val missingPluginMeta = rootProject.subprojects
            .filter { it.path.startsWith(":plugins:") }
            .map { it.file("src/main/resources/META-INF/turboism/plugin.json") }
            .filterNot { it.isFile }
        if (missingPluginMeta.isNotEmpty()) {
            throw GradleException(
                "Every :plugins:* subproject must provide META-INF/turboism/plugin.json; missing: " +
                    missingPluginMeta.joinToString { it.relativeTo(rootProject.projectDir).invariantSeparatorsPath }
            )
        }
        val filesToValidate = pluginMetaFiles.files.sortedBy { it.invariantSeparatorsPath }
        if (filesToValidate.isEmpty()) {
            throw GradleException("No source plugin.json files found.")
        }
        setArgs(filesToValidate.map { it.absolutePath })
    }
}

// Wire gate tasks into the check lifecycle for the root project
tasks.register<Exec>("checkMappingPipelineClosure") {
    group = "verification"
    description = "Validates the BT5 mapping-pipeline closure evidence and boundaries."
    workingDir(rootDir)
    commandLine("bash", "scripts/test/test_bt5_mapping_pipeline_closure.sh")
}

tasks.register<Exec>("checkMappingReviewWrapperArgs") {
    group = "verification"
    description = "Verifies mapping-review wrapper argv transport and args-file hardening offline."
    workingDir(rootDir)
    commandLine("bash", "scripts/test/test_mapping_review_wrapper_args.sh")
}

tasks.register<Exec>("checkPluginInspectionContract") {
    group = "verification"
    description = "Verifies the strict plugin inspection schema, fixtures, streaming, and evidence boundaries."
    workingDir(rootDir)
    commandLine("bash", "scripts/test/test_plugin_inspection_contract.sh")
}

tasks.register("checkPluginInspectionRuntime") {
    group = "verification"
    description = "Runs static plugin inspection gates and the production-backed strict ZIP mutation matrix."
    dependsOn("checkPluginInspectionContract", ":tests:pluginInspectionMutationTest")
}

tasks.register<Exec>("checkDistributionProtocolContract") {
    group = "verification"
    description = "Verifies protocol fixtures, package privacy, source boundaries, and compiled module boundaries."
    val productionProjects = subprojects.filter { candidate ->
        candidate.path == ":sdk" || candidate.path.startsWith(":plugins:")
    }
    dependsOn(":runtime:protocolRecordValidationTest")
    dependsOn(productionProjects.map { candidate -> candidate.tasks.named("classes") })
    environment("TURBOISM_SKIP_GRADLE_MODEL", "1")
    environment("TURBOISM_SDK_CLASSES_DIR", project(":sdk").layout.buildDirectory.dir("classes/java/main").get().asFile)
    environment(
        "TURBOISM_PLUGIN_CLASSES_DIRS",
        productionProjects.filter { it.path.startsWith(":plugins:") }
            .joinToString(File.pathSeparator) {
                it.layout.buildDirectory.dir("classes/java/main").get().asFile.absolutePath
            }
    )
    commandLine("bash", "scripts/test/test_distribution_protocol_contract.sh")
}

val previewBundleDir = layout.buildDirectory.dir("preview/$resolvedWorktreeId")

val previewBundle by tasks.registering(Sync::class) {
    group = "distribution"
    description = "Build the relocatable Turboism 0.1 Developer Preview directory."

    val agentJar = project(":bootstrap").tasks
        .named<org.gradle.jvm.tasks.Jar>("jar")
        .flatMap { it.archiveFile }
    val inspectorJar = project(":plugins:project-inspector").tasks
        .named<org.gradle.jvm.tasks.Jar>("jar")
        .flatMap { it.archiveFile }

    dependsOn(":bootstrap:jar", ":plugins:project-inspector:jar")
    into(previewBundleDir)
    from(agentJar) {
        rename { "turboism-agent.jar" }
    }
    from(inspectorJar) {
        into("plugins")
        rename { "project-inspector.jar" }
    }
    from("scripts/preview/launch-cubism-turboism.bat")
    from("scripts/preview/launch-cubism-turboism.ps1")
    from("scripts/preview/run-preview.bat")
    from("docs/release/README-preview.md") {
        rename { "README.md" }
    }
    doLast {
        val root = previewBundleDir.get().asFile
        listOf("plugin-data", "state", "logs").forEach { relative ->
            root.resolve(relative).mkdirs()
        }
    }
}

val previewSmokeDir = layout.buildDirectory.dir("preview-smoke/$resolvedWorktreeId")

val previewAgentSmokeTest by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Starts a child JVM with the built preview agent and proves premain loads the bundled plugin."
    dependsOn(previewBundle, ":bootstrap:testClasses")
    val bootstrapTests = project(":bootstrap")
        .extensions
        .getByType<org.gradle.api.tasks.SourceSetContainer>()
        .named("test")
    classpath(bootstrapTests.map { it.output })
    mainClass.set("dev.turboism.bootstrap.PreviewAgentSmokeMain")
    systemProperty("java.awt.headless", "true")
    doFirst {
        val source = previewBundleDir.get().asFile
        val root = previewSmokeDir.get().asFile
        root.deleteRecursively()
        copy {
            from(source)
            into(root)
        }
        systemProperty("turboism.home", root.absolutePath)
        setJvmArgs(listOf(
            "-javaagent:${root.resolve("turboism-agent.jar").absolutePath}" +
                "=hostClass=com.live2d.cubism.CEAppCtrl;timeoutSeconds=10"
        ))
    }
}

tasks.register("checkPreviewBundleLayout") {
    group = "verification"
    description = "Build and verify the minimum Turboism 0.1 preview bundle layout."
    dependsOn(previewBundle)
    doLast {
        val root = previewBundleDir.get().asFile
        val required = listOf(
            "turboism-agent.jar",
            "launch-cubism-turboism.bat",
            "launch-cubism-turboism.ps1",
            "run-preview.bat",
            "README.md",
            "plugins/project-inspector.jar"
        )
        val missing = required.filterNot { root.resolve(it).isFile }
        if (missing.isNotEmpty()) {
            throw GradleException("Preview bundle is missing: $missing")
        }
        val launcherText = root.resolve("launch-cubism-turboism.ps1").readText()
        if (launcherText.contains("cubism-hook-agent", ignoreCase = true)
            || launcherText.contains("JAVA_TOOL_OPTIONS", ignoreCase = true)) {
            throw GradleException("Preview launcher must not reuse the legacy agent or JAVA_TOOL_OPTIONS")
        }
        val quickLaunchText = root.resolve("run-preview.bat").readText()
        if (!quickLaunchText.contains("call \"%~dp0launch-cubism-turboism.bat\"")) {
            throw GradleException("run-preview.bat must preserve the quoted preview path")
        }
        JarFile(root.resolve("turboism-agent.jar")).use { jar ->
            val attributes = jar.manifest.mainAttributes
            if (attributes.getValue("Premain-Class") != "dev.turboism.bootstrap.TurboismAgent"
                || attributes.getValue("Agent-Class") != "dev.turboism.bootstrap.TurboismAgent") {
                throw GradleException("Preview agent manifest is missing the Turboism agent entrypoints")
            }
            val verification = "META-INF/turboism/verification/cubism-5.3.02-project-workspace.json"
            if (jar.getJarEntry(verification) == null) {
                throw GradleException("Preview agent is missing embedded verification record $verification")
            }
        }
    }
}

tasks.named("check") {
    dependsOn(
        "checkModuleBoundaries",
        "checkAsmSupplyChainAdmission",
        "checkMappingPipelineClosure",
        "checkMappingReviewWrapperArgs",
        "checkPluginInspectionRuntime",
        "checkDistributionProtocolContract",
        "checkPreviewBundleLayout",
        previewAgentSmokeTest,
        ":tests:previewPluginRuntimeTest",
        "validatePluginMeta"
    )
}
