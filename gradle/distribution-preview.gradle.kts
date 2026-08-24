import org.gradle.api.Project
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.Sync
import org.gradle.jvm.tasks.Jar
import java.io.File
import java.util.jar.JarFile

private val resolvedWorktreeId = rootProject.extra["turboismResolvedWorktreeId"] as String
private val previewBundleDir = layout.buildDirectory.dir("preview/$resolvedWorktreeId")
private val performanceProbeValidationDir =
    layout.buildDirectory.dir("validation/$resolvedWorktreeId/performance-probe")


private fun Project.productionProjects() = subprojects.filter {
    it.path == ":sdk" || it.path.startsWith(":plugins:")
}

private fun configurePreviewSource(task: Sync, previewDirectory: Provider<org.gradle.api.file.Directory>) {
    task.into(previewDirectory)
    configurePreviewAgentJar(task)
    configurePreviewInspectorJar(task)
    configurePreviewPerfStatsJar(task)
    configurePreviewThemeJar(task)
    configureScenePaletteEnhancerJar(task)
    configurePreviewMeshJar(task)
    configureGraalHost(task)
    task.from("scripts/preview/launch-cubism-turboism.bat")
    task.from("scripts/preview/launch-cubism-turboism.ps1")
    task.from("scripts/preview/run-preview.bat")
    task.from("packaging/README-preview.md") { rename { "README.md" } }
}

private fun configurePreviewAgentJar(task: Sync) {
    task.from(project(":bootstrap").tasks.named<Jar>("jar").flatMap { it.archiveFile })
}

private fun configurePreviewInspectorJar(task: Sync) {
    task.from(project(":plugins:project-inspector").tasks.named<Jar>("jar").flatMap { it.archiveFile }) {
        into("plugins")
        rename { "project-inspector.jar" }
    }
}

private fun configurePreviewPerfStatsJar(task: Sync) {
    task.from(project(":plugins:perf-stats").tasks.named<Jar>("jar").flatMap { it.archiveFile }) {
        into("plugins")
        rename { "perf-stats.jar" }
    }
}

private fun configurePreviewThemeJar(task: Sync) {
    task.from(project(":plugins:ui-theme").tasks.named<Jar>("jar").flatMap { it.archiveFile }) {
        into("plugins")
        rename { "ui-theme.jar" }
    }
}

private fun configureScenePaletteEnhancerJar(task: Sync) {
    task.from(project(":plugins:scene-palette-enhancer").tasks.named<Jar>("jar").flatMap { it.archiveFile }) {
        into("plugins")
        rename { "scene-palette-enhancer.jar" }
    }
}

private fun configurePreviewMeshJar(task: Sync) {
    task.from(project(":plugins:mesh-edit-mirror-axis-enhance").tasks.named<Jar>("jar").flatMap { it.archiveFile }) {
        into("plugins")
        rename { "mesh-edit-mirror-axis-enhance.jar" }
    }
}

private fun configureGraalHost(task: Sync) {
    // The preview runs inside Windows Cubism (also under Proton on Linux), so
    // its Graal closure must remain windows-amd64 even when installDist follows
    // the developer host target.
    task.from(project(":graal-host").tasks.named("windowsPreviewDist")) {
        include("lib/**")
        into("graal")
    }
}

tasks.register<Exec>("checkDistributionProtocolContract") {
    group = "verification"
    description = "Verifies protocol fixtures, package privacy, source boundaries, and compiled module boundaries."
    val productionProjects = productionProjects()
    dependsOn(":runtime:protocolRecordValidationTest")
    dependsOn(productionProjects.map { it.tasks.named("classes") })
    environment("TURBOISM_SKIP_GRADLE_MODEL", "1")
    environment("TURBOISM_SDK_CLASSES_DIR", project(":sdk").layout.buildDirectory.dir("classes/java/main").get().asFile)
    environment("TURBOISM_PLUGIN_CLASSES_DIRS", pluginClassesDirectories(productionProjects))
    commandLine("bash", "scripts/test/test_distribution_protocol_contract.sh")
}

private fun pluginClassesDirectories(projects: List<Project>): String = projects
    .filter { it.path.startsWith(":plugins:") }
    .joinToString(File.pathSeparator) { it.layout.buildDirectory.dir("classes/java/main").get().asFile.absolutePath }

val previewBundle by tasks.registering(Sync::class) {
    group = "distribution"
    description = "Build the relocatable Turboism 0.1 Developer Preview directory."
    dependsOn(
        ":bootstrap:jar",
        ":plugins:project-inspector:jar",
        ":plugins:perf-stats:jar",
        ":plugins:ui-theme:jar",
        ":plugins:scene-palette-enhancer:jar",
        ":plugins:mesh-edit-mirror-axis-enhance:jar",
        ":graal-host:windowsPreviewDist"
    )
    configurePreviewSource(this, previewBundleDir)
    doLast {
        listOf("plugin-data", "state", "logs").forEach { previewBundleDir.get().asFile.resolve(it).mkdirs() }
    }
}

val performanceProbeValidationBundle by tasks.registering(Sync::class) {
    group = "distribution"
    description = "Build the validation-only Cubism performance probe bundle."
    dependsOn(":bootstrap:performanceProbeAgentJar", ":bootstrap:performanceProbeCarrierJar")
    into(performanceProbeValidationDir)
    from(project(":bootstrap").tasks.named<Jar>("performanceProbeAgentJar").flatMap { it.archiveFile }) {
        rename { "turboism-agent.jar" }
    }
    from(project(":bootstrap").tasks.named<Jar>("performanceProbeCarrierJar").flatMap { it.archiveFile }) {
        into("lib")
    }
    doLast {
        listOf("plugins", "plugin-data", "state", "logs").forEach {
            performanceProbeValidationDir.get().asFile.resolve(it).mkdirs()
        }
    }
}

val previewBootstrapBridgeTest by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Proves the distributed agent exposes the mesh-mirror ingress to bootstrap lookup."
    dependsOn(previewBundle, ":bootstrap:testClasses")
    val bootstrapTests = project(":bootstrap").extensions.getByType<SourceSetContainer>().named("test")
    classpath(bootstrapTests.map { it.output })
    mainClass.set("dev.turboism.bootstrap.BootstrapBridgeVisibilityMain")
    doFirst {
        val agent = previewBundleDir.get().asFile.resolve("turboism-agent.jar")
        setJvmArgs(listOf("-javaagent:${agent.absolutePath}=hostClass=missing.Host;timeoutSeconds=1"))
    }
}

tasks.register("checkPreviewBundleLayout") {
    group = "verification"
    description = "Build and verify the minimum Turboism 0.1 preview bundle layout and probe package isolation."
    dependsOn(previewBundle, performanceProbeValidationBundle)
    doLast {
        verifyPreviewBundle(previewBundleDir.get().asFile)
        verifyPerformanceProbeValidationBundle(performanceProbeValidationDir.get().asFile)
    }
}

private fun verifyPreviewBundle(root: File) {
    val required = listOf(
        "turboism-agent.jar", "launch-cubism-turboism.bat", "launch-cubism-turboism.ps1",
        "run-preview.bat", "README.md", "plugins/project-inspector.jar", "plugins/perf-stats.jar",
        "plugins/ui-theme.jar", "plugins/scene-palette-enhancer.jar",
        "plugins/mesh-edit-mirror-axis-enhance.jar", "graal/lib/polyglot-25.2.4.jar"
    )
    val missing = required.filterNot { root.resolve(it).isFile }
    if (missing.isNotEmpty()) throw GradleException("Preview bundle is missing: $missing")
    val graalLibraries = root.resolve("graal/lib").listFiles()?.map { it.name }.orEmpty()
    if (graalLibraries.none { it.startsWith("sdk-") && it.endsWith(".jar") }) {
        throw GradleException("Windows preview bundle is missing the Turboism SDK dependency")
    }
    val windowsIsolate = "js-isolate-windows-amd64-community-25.2.4.jar"
    if (windowsIsolate !in graalLibraries) {
        throw GradleException("Windows preview bundle is missing $windowsIsolate")
    }
    val wrongPlatformIsolates = graalLibraries.filter {
        it.startsWith("js-isolate-") && it.endsWith("-community-25.2.4.jar") && it != windowsIsolate
    }
    if (wrongPlatformIsolates.isNotEmpty()) {
        throw GradleException("Windows preview bundle contains wrong-platform GraalJS isolates: $wrongPlatformIsolates")
    }
    verifyPreviewLaunchers(root)
    verifyPreviewAgentJar(root.resolve("turboism-agent.jar"))
}

private fun verifyPreviewLaunchers(root: File) {
    val launcher = root.resolve("launch-cubism-turboism.ps1").readText()
    if (launcher.contains("cubism-hook-agent", true) || launcher.contains("JAVA_TOOL_OPTIONS", true)) {
        throw GradleException("Preview launcher must not reuse the legacy agent or JAVA_TOOL_OPTIONS")
    }
    val requiredGraalLauncherTokens = listOf(
        "[string]${'$'}CubismJava",
        "[string]${'$'}GraalJava",
        "TURBOISM_GRAAL_JAVA",
        "TURBOISM_GRAALVM_HOME",
        "GRAALVM_HOME",
        "graalvm\\bin\\java.exe",
        "-Dturboism.graal.java=${'$'}graalHostJava",
        "-Dturboism.graal.classpath=${'$'}graalClassPath",
        "-Dturboism.graal.enabled=false"
    )
    val missingGraalTokens = requiredGraalLauncherTokens.filterNot(launcher::contains)
    if (missingGraalTokens.isNotEmpty()) {
        throw GradleException("Preview launcher is missing dual-JVM Graal configuration: ${'$'}missingGraalTokens")
    }
    if (!root.resolve("run-preview.bat").readText().contains("call \"%~dp0launch-cubism-turboism.bat\"")) {
        throw GradleException("run-preview.bat must preserve the quoted preview path")
    }
}

private fun verifyPreviewAgentJar(agentJar: File) {
    JarFile(agentJar).use { jar ->
        val attributes = jar.manifest.mainAttributes
        if (attributes.getValue("Premain-Class") != "dev.turboism.bootstrap.TurboismAgent" ||
            attributes.getValue("Agent-Class") != "dev.turboism.bootstrap.TurboismAgent") {
            throw GradleException("Preview agent manifest is missing the Turboism agent entrypoints")
        }
        val verification = "META-INF/turboism/verification/cubism-5.3.02-project-workspace.json"
        if (jar.getJarEntry(verification) == null) {
            throw GradleException("Preview agent is missing embedded verification record $verification")
        }
        val required = listOf(
            "dev/turboism/adapter/cubism/performance/PerformanceProbeRecorder.class",
            "dev/turboism/adapter/cubism/performance/PerformanceProbeMethodTransformer.class",
            "dev/turboism/adapter/cubism/performance/PerformanceProbeTargets.class",
            "dev/turboism/adapter/cubism/performance/NativePerformanceProbeBridge.class",
            "dev/turboism/adapter/cubism/performance/PerformanceProbeRollbackObserver.class",
            "dev/turboism/adapter/cubism/performance/PerformanceFpsHook.class",
            "dev/turboism/adapter/cubism/performance/PerformanceFpsHookRegistry.class",
            "dev/turboism/bootstrap/PerformanceFpsHookInstaller.class",
            "dev/turboism/bootstrap/carrier/PerformanceProbeCarrier.class",
            "dev/turboism/bootstrap/carrier/PerformanceProbeCallback.class",
            "dev/turboism/bootstrap/VerifiedPerformanceProbeInstaller.class"
        )
        val missing = required.filterNot { jar.getJarEntry(it) != null }
        if (missing.isNotEmpty()) {
            throw GradleException("Preview agent is missing probe/FPS implementation classes: $missing")
        }
    }
}

private fun verifyPerformanceProbeValidationBundle(root: File) {
    val agentJar = root.resolve("turboism-agent.jar")
    if (!agentJar.isFile) {
        throw GradleException("Performance probe validation bundle is missing turboism-agent.jar")
    }
    JarFile(agentJar).use { jar ->
        val required = listOf(
            "dev/turboism/adapter/cubism/performance/PerformanceProbeReportWriter.class",
            "dev/turboism/adapter/cubism/performance/PerformanceProbeMethodTransformer.class",
            "dev/turboism/adapter/cubism/performance/PerformanceProbeRecorder.class",
            "dev/turboism/adapter/cubism/performance/PerformanceProbeTargets.class",
            "dev/turboism/adapter/cubism/performance/PerformanceProbeRollbackObserver.class",
            "dev/turboism/adapter/cubism/performance/PerformanceProbeRollbackWriter.class",
            "dev/turboism/adapter/cubism/performance/NativePerformanceProbeBridge.class",
            "dev/turboism/bootstrap/VerifiedPerformanceProbeInstaller.class",
            "dev/turboism/bootstrap/carrier/PerformanceProbeCarrier.class"
        )
        val missing = required.filterNot { jar.getJarEntry(it) != null }
        if (missing.isNotEmpty()) {
            throw GradleException("Validation agent is missing probe implementation classes: $missing")
        }
    }
    if (!root.resolve("lib/performance-probe-carrier.jar").isFile) {
        throw GradleException("Performance probe validation bundle is missing lib/performance-probe-carrier.jar")
    }
}
