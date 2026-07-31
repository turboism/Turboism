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
private val previewSmokeDir = layout.buildDirectory.dir("preview-smoke/$resolvedWorktreeId")

private fun Project.mainRuntimeClasspath() = extensions.getByType<SourceSetContainer>()
    .named("main").get().runtimeClasspath

private fun Project.productionProjects() = subprojects.filter {
    it.path == ":sdk" || it.path.startsWith(":plugins:")
}

private fun configurePreviewSource(task: Sync, previewDirectory: Provider<org.gradle.api.file.Directory>) {
    task.into(previewDirectory)
    configurePreviewAgentJar(task)
    configurePreviewInspectorJar(task)
    task.from("scripts/preview/launch-cubism-turboism.bat")
    task.from("scripts/preview/launch-cubism-turboism.ps1")
    task.from("scripts/preview/run-preview.bat")
    task.from("docs/release/README-preview.md") { rename { "README.md" } }
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
    dependsOn(":bootstrap:jar", ":plugins:project-inspector:jar")
    configurePreviewSource(this, previewBundleDir)
    doLast {
        listOf("plugin-data", "state", "logs").forEach { previewBundleDir.get().asFile.resolve(it).mkdirs() }
    }
}

val previewAgentSmokeTest by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Starts a child JVM with the built preview agent and proves premain loads the bundled plugin."
    dependsOn(previewBundle, ":bootstrap:testClasses")
    val bootstrapTests = project(":bootstrap").extensions.getByType<SourceSetContainer>().named("test")
    classpath(bootstrapTests.map { it.output })
    mainClass.set("dev.turboism.bootstrap.PreviewAgentSmokeMain")
    systemProperty("java.awt.headless", "true")
    doFirst {
        val source = previewBundleDir.get().asFile
        val root = previewSmokeDir.get().asFile
        root.deleteRecursively()
        copyPreviewBundle(source, root)
        systemProperty("turboism.home", root.absolutePath)
        setJvmArgs(listOf("-javaagent:${root.resolve("turboism-agent.jar").absolutePath}=hostClass=com.live2d.cubism.CEAppCtrl;timeoutSeconds=10"))
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

val checkPreviewRuntimeReports by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Strictly validates the four correlated machine-readable preview reports."
    dependsOn(previewAgentSmokeTest, ":runtime:classes")
    classpath(project(":runtime").mainRuntimeClasspath())
    mainClass.set("dev.turboism.preview.report.PreviewReportValidationCli")
    doFirst { args(previewSmokeDir.get().asFile.resolve("state").absolutePath) }
}

tasks.register("checkPreviewBundleLayout") {
    group = "verification"
    description = "Build and verify the minimum Turboism 0.1 preview bundle layout."
    dependsOn(previewBundle)
    doLast { verifyPreviewBundle(previewBundleDir.get().asFile) }
}

private fun copyPreviewBundle(source: File, target: File) {
    copyPreviewBundleInto(source, target)
}

private fun copyPreviewBundleInto(source: File, target: File) {
    copy { from(source); into(target) }
}

private fun verifyPreviewBundle(root: File) {
    val required = listOf(
        "turboism-agent.jar", "launch-cubism-turboism.bat", "launch-cubism-turboism.ps1",
        "run-preview.bat", "README.md", "plugins/project-inspector.jar"
    )
    val missing = required.filterNot { root.resolve(it).isFile }
    if (missing.isNotEmpty()) throw GradleException("Preview bundle is missing: $missing")
    verifyPreviewLaunchers(root)
    verifyPreviewAgentJar(root.resolve("turboism-agent.jar"))
}

private fun verifyPreviewLaunchers(root: File) {
    val launcher = root.resolve("launch-cubism-turboism.ps1").readText()
    if (launcher.contains("cubism-hook-agent", true) || launcher.contains("JAVA_TOOL_OPTIONS", true)) {
        throw GradleException("Preview launcher must not reuse the legacy agent or JAVA_TOOL_OPTIONS")
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
    }
}
