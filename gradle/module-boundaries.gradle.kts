import org.gradle.api.Project
import org.gradle.api.artifacts.Dependency
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.logging.Logger

private class BoundaryState(private val logger: Logger) {
    var failed = false

    fun reject(message: String) {
        logger.error(message)
        failed = true
    }
}

private val forbiddenImportPatterns = listOf(
    "dev.turboism.*.internal.*" to "SDK/public modules must not import runtime internal packages",
    "com.live2d.*" to "SDK/plugins must not import Cubism internal packages (com.live2d)",
    "dev.turboism.core.parameter.*" to "Phase 1/M2 forbids parameter package",
    "dev.turboism.core.mesh.*" to "Phase 1/M2 forbids mesh package",
    "dev.turboism.core.psd.*" to "Phase 1/M2 forbids psd package",
    "dev.turboism.core.mirror.*" to "Phase 1/M2 forbids mirror package"
)

private val forbiddenHostUiTraversal = listOf(
    "SwingUtilities.getWindowAncestor(",
    "SwingUtilities.getRoot(",
    ".getTopLevelAncestor()"
)

tasks.register("checkModuleBoundaries") {
    group = "verification"
    description = "Verify module dependency direction, internal imports, and forbidden packages."
    dependsOn("checkSdkV2ExactApiCompatibility")
    doLast {
        checkModuleBoundaries(rootProject)
    }
}

private fun checkModuleBoundaries(project: Project) {
    val state = BoundaryState(project.logger)
    project.subprojects.forEach { subproject ->
        checkProjectDependencies(subproject, state)
        checkAsmDependencies(subproject, state)
        scanProductionSources(project, subproject, state)
    }
    checkOpaqueUserFileRuntime(project, state)
    checkOpaqueUserFileSdk(project, state)
    if (state.failed) {
        throw GradleException("Module boundary checks failed.")
    }
    project.logger.lifecycle("Module boundary checks passed.")
}

private fun checkProjectDependencies(subproject: Project, state: BoundaryState) {
    val config = subproject.configurations.findByName("compileClasspath") ?: return
    val resolved = config.resolvedConfiguration.lenientConfiguration.files
    if (subproject.path == ":sdk") {
        checkSdkResolvedDependencies(resolved, subproject, state)
    }
    if (subproject.path.startsWith(":plugins:")) {
        checkPluginProjectDependencies(subproject, state)
    }
}

private fun checkSdkResolvedDependencies(files: Set<java.io.File>, project: Project, state: BoundaryState) {
    files.forEach { file ->
        if (file.name.contains("runtime")) {
            state.reject("SDK must not depend on runtime artifacts: ${file.name} in ${project.path}")
        }
    }
}

private fun checkPluginProjectDependencies(project: Project, state: BoundaryState) {
    val apiDeps = project.configurations.findByName("api")?.dependencies?.toList() ?: emptyList<Dependency>()
    val compileOnlyDeps = project.configurations.findByName("compileOnly")?.dependencies?.toList() ?: emptyList<Dependency>()
    val implementationDeps = project.configurations.findByName("implementation")?.dependencies?.toList() ?: emptyList<Dependency>()
    val declared: List<Dependency> = apiDeps + compileOnlyDeps + implementationDeps
    declared.filterIsInstance<ProjectDependency>().forEach { dependency ->
        if (dependency.dependencyProject.path != ":sdk") {
            state.reject("${project.path} must only depend on :sdk, found ${dependency.dependencyProject.path}")
        }
    }
}

private fun checkAsmDependencies(project: Project, state: BoundaryState) {
    val admittedAsmGroup = "org.ow2." + "asm"
    val dependencies = project.configurations.flatMap { it.dependencies.toList() }
        .filter { it.group == admittedAsmGroup }
    dependencies.forEach { dependency ->
        val admitted = project.path == ":runtime" && dependency.name == "asm" &&
            dependency.version == "9.7.1" &&
            project.configurations.getByName("implementation").dependencies.contains(dependency)
        if (!admitted) {
            state.reject(
                "Only :runtime implementation(${admittedAsmGroup}:asm:9.7.1) is admitted; " +
                    "found ${dependency.group}:${dependency.name}:${dependency.version} in ${project.path}"
            )
        }
    }
}

private fun scanProductionSources(root: Project, project: Project, state: BoundaryState) {
    val sourceDir = project.file("src/main/java")
    if (!sourceDir.exists()) {
        return
    }
    sourceDir.walkTopDown().filter { it.isFile && it.extension == "java" }.forEach { file ->
        checkSourceFile(root, project, file, state)
    }
}

private fun checkSourceFile(root: Project, project: Project, file: java.io.File, state: BoundaryState) {
    val lines = file.readLines()
    if (lines.size > 800) {
        state.reject("Class exceeds 800 lines: ${file.relativeTo(root.projectDir)}")
    }
    val restricted = project.path == ":sdk" || project.path.startsWith(":plugins:")
    if (restricted) {
        checkRestrictedImports(root, file, lines, state)
    }
    if (project.path.startsWith(":plugins:")) {
        checkForbiddenHostUiTraversal(root, file, lines, state)
    }
}

private fun checkRestrictedImports(root: Project, file: java.io.File, lines: List<String>, state: BoundaryState) {
    lines.forEachIndexed { index, line ->
        if (line.matches(Regex("^import dev\\.turboism\\.distribution(?:\\..*)?;"))) {
            state.reject("Forbidden distribution import in ${file.relativeTo(root.projectDir)}:${index + 1}")
        }
        forbiddenImportPatterns.forEach { (pattern, message) ->
            if (line.matches(Regex("^import $pattern;"))) {
                state.reject("Forbidden import in ${file.relativeTo(root.projectDir)}:${index + 1}: $message")
            }
        }
    }
}

private fun checkForbiddenHostUiTraversal(root: Project, file: java.io.File, lines: List<String>, state: BoundaryState) {
    val source = lines.joinToString("\n")
    forbiddenHostUiTraversal.forEach { token ->
        if (source.contains(token)) {
            state.reject(
                "Plugin-owned external Swing views must not discover or mutate host UI trees; " +
                    "forbidden token '$token' in ${file.relativeTo(root.projectDir)}"
            )
        }
    }
}

private fun checkOpaqueUserFileRuntime(project: Project, state: BoundaryState) {
    val runtime = project.file("runtime/src/main/java/dev/turboism/userfile")
    if (!runtime.isDirectory) {
        return
    }
    runtime.walkTopDown().filter { it.isFile && it.extension == "java" }.forEach { file ->
        checkOpaqueUserFileRuntimeSource(project, file, state)
    }
}

private fun checkOpaqueUserFileRuntimeSource(project: Project, file: java.io.File, state: BoundaryState) {
    val source = file.readText()
    val callsPredecessor = source.contains("dev.turboism.sdk.ui.FileChooserRequest") ||
        source.contains("dev.turboism.sdk.ui.UiHostCapabilityService") || source.contains("requestFile(")
    if (callsPredecessor) {
        state.reject(
            "Opaque user-file runtime must not call the predecessor string chooser: " +
                file.relativeTo(project.projectDir)
        )
    }
}

private fun checkOpaqueUserFileSdk(project: Project, state: BoundaryState) {
    val sdkUi = project.file("sdk/src/main/java/dev/turboism/sdk/ui")
    if (!sdkUi.isDirectory) {
        return
    }
    sdkUi.walkTopDown().filter { it.isFile && it.name.startsWith("UserFile") && it.extension == "java" }
        .forEach { file -> checkOpaqueUserFileSdkSource(project, file, state) }
}

private fun checkOpaqueUserFileSdkSource(project: Project, file: java.io.File, state: BoundaryState) {
    val source = file.readText()
    val exposesLocation = source.contains("java.nio.file.Path") || source.contains("java.io.File") ||
        source.contains("java.net.URI")
    if (exposesLocation) {
        state.reject(
            "Opaque user-file SDK must not expose path/file/URI types: " + file.relativeTo(project.projectDir)
        )
    }
}
