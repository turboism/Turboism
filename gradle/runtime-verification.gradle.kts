import org.gradle.api.Project
import org.gradle.api.file.FileCollection
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.SourceSetContainer

private fun Project.runtimeMainClasspath() = project(":runtime").extensions
    .getByType<SourceSetContainer>().named("main").get().runtimeClasspath

private fun JavaExec.configureRuntimeJavaExec(project: Project, mainClassName: String) {
    dependsOn(":runtime:classes")
    classpath = project.runtimeMainClasspath()
    mainClass.set(mainClassName)
}

private fun Project.filesToValidate(pluginMetaFiles: FileCollection): List<java.io.File> {
    val missing = rootProject.subprojects.filter { it.path.startsWith(":plugins:") }
        .map { it.file("src/main/resources/META-INF/turboism/plugin.json") }.filterNot { it.isFile }
    if (missing.isNotEmpty()) {
        throw GradleException(
            "Every :plugins:* subproject must provide META-INF/turboism/plugin.json; missing: " +
                missing.joinToString { it.relativeTo(rootProject.projectDir).invariantSeparatorsPath }
        )
    }
    val files = pluginMetaFiles.files.sortedBy { it.invariantSeparatorsPath }
    if (files.isEmpty()) {
        throw GradleException("No source plugin.json files found.")
    }
    return files
}

tasks.register<JavaExec>("verifyStaticHostSelectors") {
    group = "verification"
    description = "Verify an exact-version host JAR against a tracked static verification record."
    val recordPath = providers.gradleProperty("turboismStaticVerificationRecord")
    val artifactPath = providers.gradleProperty("turboismHostArtifact")
    configureRuntimeJavaExec(project, "dev.turboism.mapping.verification.StaticVerificationCli")
    doFirst {
        if (!recordPath.isPresent || !artifactPath.isPresent) {
            throw GradleException("Pass -PturboismStaticVerificationRecord=<record.json> -PturboismHostArtifact=<Live2D_Cubism.jar>")
        }
        args(recordPath.get(), artifactPath.get())
    }
}

val pluginMetaFiles = files(
    fileTree("plugins") { include("**/src/main/resources/META-INF/turboism/plugin.json") },
    fileTree("testframework/src/main/resources/fixtures/schema/plugin-meta-v2/valid") { include("*.json") }
)

tasks.register<JavaExec>("validatePluginMeta") {
    group = "verification"
    description = "Validate source plugin.json files against their declared plugin-meta schema version using the runtime validator."
    inputs.files(pluginMetaFiles)
    configureRuntimeJavaExec(project, "dev.turboism.core.schema.plugin.PluginMetaValidationCli")
    doFirst {
        setArgs(filesToValidate(pluginMetaFiles).map { it.absolutePath })
    }
}

val firstPartyPluginProjects = rootProject.subprojects.filter { it.path.startsWith(":plugins:") }
val firstPartyDescriptorFiles = files(
    firstPartyPluginProjects.map { it.file("src/main/resources/META-INF/turboism/plugin.json") }
)
val firstPartyJarFiles = provider {
    firstPartyPluginProjects.sortedBy { it.name }.map { plugin ->
        plugin.tasks.named<Jar>("jar").get().archiveFile.get().asFile
    }
}

tasks.register<JavaExec>("verifyFirstPartyPluginMetadata") {
    group = "verification"
    description = "Inspect every built first-party plugin JAR through the production PluginJarContract and bind its embedded v3 classification to the tracked descriptor."
    dependsOn(firstPartyPluginProjects.map { "${it.path}:jar" })
    inputs.files(firstPartyDescriptorFiles)
    inputs.files(firstPartyJarFiles)
    configureRuntimeJavaExec(project, "dev.turboism.pluginmanagement.FirstPartyMetadataVerificationCli")
    doFirst {
        val pairs = firstPartyPluginProjects.sortedBy { it.name }.flatMap { plugin ->
            val descriptor = plugin.file("src/main/resources/META-INF/turboism/plugin.json")
            if (!descriptor.isFile) {
                throw GradleException(
                    "Tracked first-party descriptor missing: " +
                        descriptor.relativeTo(rootProject.projectDir).invariantSeparatorsPath
                )
            }
            val jar = plugin.tasks.named<Jar>("jar").get().archiveFile.get().asFile
            if (!jar.isFile) {
                throw GradleException(
                    "Built first-party JAR missing: " +
                        jar.relativeTo(rootProject.projectDir).invariantSeparatorsPath
                )
            }
            listOf(descriptor.absolutePath, jar.absolutePath)
        }
        setArgs(pairs)
    }
}
