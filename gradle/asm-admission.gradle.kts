import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.component.ModuleComponentSelector
import org.gradle.api.artifacts.repositories.MavenArtifactRepository
import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.SourceSetContainer
import java.io.File

private val forbiddenAgentGroup = "net.byte" + "buddy"
private val forbiddenAgentName = "Byte" + " Buddy"
private val admittedAsmGroup = "org.ow2." + "asm"
private val admittedAsmCoordinate = "$admittedAsmGroup:asm:9.7.1"

private fun verifyResolvedBytecodeDependencyGraph(project: Project) {
    val violations = mutableListOf<String>()
    project.allprojects.forEach { candidate ->
        checkProjectResolution(candidate, violations)
    }
    if (violations.isNotEmpty()) {
        throw GradleException("Resolved bytecode dependency graph rejected:\n" + violations.joinToString("\n"))
    }
}

private fun checkProjectResolution(candidate: Project, violations: MutableList<String>) {
    candidate.configurations.filter { it.isCanBeResolved && isProductionClasspath(it) }.forEach { configuration ->
        checkResolvedConfiguration(candidate, configuration, violations)
    }
}

private fun isProductionClasspath(configuration: Configuration): Boolean {
    return configuration.name == "runtimeClasspath" || configuration.name == "compileClasspath"
}

private fun checkResolvedConfiguration(candidate: Project, configuration: Configuration, violations: MutableList<String>) {
    val resolution = configuration.incoming.resolutionResult
    checkRequestedComponents(candidate, configuration, resolution.allDependencies, violations)
    val external = resolution.allComponents.mapNotNull { component ->
        component.moduleVersion?.let { Triple(it.group, it.name, it.version) }
    }
    checkResolvedComponents(candidate, configuration, external, violations)
    checkRuntimeAsm(candidate, configuration, external, violations)
}

private fun checkRequestedComponents(
    candidate: Project,
    configuration: Configuration,
    dependencies: Iterable<org.gradle.api.artifacts.result.DependencyResult>,
    violations: MutableList<String>
) {
    dependencies.forEach { dependency ->
        val requested = dependency.requested as? ModuleComponentSelector ?: return@forEach
        if (requested.group == forbiddenAgentGroup || requested.group.startsWith("$forbiddenAgentGroup.")) {
            violations += "${candidate.path}:${configuration.name} requests forbidden ${requested.group}:${requested.module}:${requested.version}"
        }
        if (requested.group == admittedAsmGroup && (requested.module != "asm" || requested.version != "9.7.1")) {
            violations += "${candidate.path}:${configuration.name} requests unadmitted ${requested.group}:${requested.module}:${requested.version}"
        }
    }
}

private fun checkResolvedComponents(
    candidate: Project,
    configuration: Configuration,
    external: List<Triple<String, String, String>>,
    violations: MutableList<String>
) {
    external.forEach { (group, module, version) ->
        if (group == forbiddenAgentGroup || group.startsWith("$forbiddenAgentGroup.")) {
            violations += "${candidate.path}:${configuration.name} contains forbidden $group:$module:$version"
        }
        if (group == admittedAsmGroup && (module != "asm" || version != "9.7.1")) {
            violations += "${candidate.path}:${configuration.name} contains unadmitted $group:$module:$version"
        }
    }
}

private fun checkRuntimeAsm(
    candidate: Project,
    configuration: Configuration,
    external: List<Triple<String, String, String>>,
    violations: MutableList<String>
) {
    if (candidate.path != ":runtime" || configuration.name != "runtimeClasspath") {
        return
    }
    val asm = external.filter { it.first == admittedAsmGroup }
    if (asm != listOf(Triple(admittedAsmGroup, "asm", "9.7.1"))) {
        violations += ":runtime:runtimeClasspath must contain exactly $admittedAsmCoordinate; found $asm"
    }
}

tasks.register("checkResolvedBytecodeDependencyGraph") {
    group = "verification"
    description = "Resolve production graphs and reject unadmitted ASM or any $forbiddenAgentName component."
    doLast {
        verifyResolvedBytecodeDependencyGraph(rootProject)
    }
}

private fun verifyAsmDependencyModel(project: Project) {
    val admitted = mutableListOf<String>()
    project.allprojects.forEach { candidate -> collectAsmDependencies(candidate, admitted) }
    if (admitted != listOf(":runtime:implementation:$admittedAsmCoordinate")) {
        throw GradleException("Expected exactly one admitted ASM dependency; found $admitted")
    }
    project.allprojects.forEach(::verifyMavenCentralOnly)
}

private fun collectAsmDependencies(candidate: Project, admitted: MutableList<String>) {
    candidate.configurations.forEach { configuration ->
        configuration.dependencies.forEach { dependency ->
            verifyConfiguredDependency(candidate, configuration, dependency, admitted)
        }
    }
}

private fun verifyConfiguredDependency(
    candidate: Project,
    configuration: Configuration,
    dependency: org.gradle.api.artifacts.Dependency,
    admitted: MutableList<String>
) {
    val isAsm = dependency.group == admittedAsmGroup
    if (dependency.group == forbiddenAgentGroup) {
        throw GradleException("$forbiddenAgentName is not admitted: ${candidate.path}:${configuration.name}")
    }
    if (!isAsm) {
        return
    }
    val valid = candidate.path == ":runtime" && configuration.name == "implementation" &&
        dependency.name == "asm" && dependency.version == "9.7.1"
    if (!valid) {
        throw GradleException(
            "Only :runtime implementation($admittedAsmCoordinate) is admitted; " +
                "found ${dependency.group}:${dependency.name}:${dependency.version} in ${candidate.path}:${configuration.name}"
        )
    }
    admitted += "${candidate.path}:${configuration.name}:${dependency.group}:${dependency.name}:${dependency.version}"
}

private fun verifyMavenCentralOnly(candidate: Project) {
    candidate.repositories.forEach { repository ->
        val maven = repository as? MavenArtifactRepository
            ?: throw GradleException("Only Maven Central is admitted; found ${repository.name} in ${candidate.path}")
        val url = maven.url.toString().trimEnd('/')
        if (url !in setOf("https://repo.maven.apache.org/maven2", "https://repo1.maven.org/maven2")) {
            throw GradleException("Only Maven Central is admitted; found $url in ${candidate.path}")
        }
    }
}

tasks.register("checkAsmDependencyModel") {
    group = "verification"
    description = "Verify Gradle's configured model admits only runtime implementation ASM 9.7.1 and Maven Central."
    doLast {
        verifyAsmDependencyModel(rootProject)
    }
}

val productionMainSourceSets = subprojects.mapNotNull { candidate ->
    candidate.extensions.findByType<SourceSetContainer>()?.findByName(SourceSet.MAIN_SOURCE_SET_NAME)
        ?.takeIf { sourceSet -> sourceSet.allSource.files.any { it.isFile } }
        ?.let { candidate to it }
}

tasks.register<Exec>("checkAsmSupplyChainAdmission") {
    group = "verification"
    description = "Verify the exact ASM 9.7.1 dependency, API boundary, and supply-chain evidence."
    environment("TURBOISM_SKIP_GRADLE_MODEL", "1")
    dependsOn("checkAsmDependencyModel", "checkResolvedBytecodeDependencyGraph")
    dependsOn(productionMainSourceSets.map { (candidate, sourceSet) -> candidate.tasks.named(sourceSet.classesTaskName) })
    environment(
        "TURBOISM_PRODUCTION_CLASSES_DIRS",
        productionMainSourceSets.flatMap { (_, sourceSet) -> sourceSet.output.classesDirs.files }
            .joinToString(File.pathSeparator) { it.absolutePath }
    )
    commandLine("bash", "scripts/test/test_asm_supply_chain_admission.sh")
}
