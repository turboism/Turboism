import org.gradle.api.Project
import org.gradle.api.artifacts.Dependency
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
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
    "dev.turboism.core.parameter.*" to "SDK/plugins must not import runtime parameter internals",
    "dev.turboism.core.mesh.*" to "SDK/plugins must not import runtime mesh internals",
    "dev.turboism.core.psd.*" to "SDK/plugins must not import runtime PSD internals",
    "dev.turboism.core.mirror.*" to "SDK/plugins must not import runtime mirror internals"
)

private val productionDependencyConfigurations = setOf(
    "api", "compileOnly", "compileOnlyApi", "implementation", "runtimeOnly", "annotationProcessor"
)

private val forbiddenQualifiedReferencePatterns = listOf(
    Regex("""(?<![\w$])dev\.turboism\.[A-Za-z_$][\w$]*\.internal(?:\.[A-Za-z_$][\w$]*)+(?![\w$])""") to
        "SDK/public modules must not reference runtime internal packages",
    Regex("""(?<![\w$])com\.live2d(?:\.[A-Za-z_$][\w$]*)+(?![\w$])""") to
        "SDK/plugins must not reference Cubism internal packages (com.live2d)",
    Regex("""(?<![\w$])dev\.turboism\.core\.(?:parameter|mesh|psd|mirror)(?:\.[A-Za-z_$][\w$]*)+(?![\w$])""") to
        "SDK/plugins must not reference runtime core internals",
    Regex("""(?<![\w$])dev\.turboism\.distribution(?:\.[A-Za-z_$][\w$]*)+(?![\w$])""") to
        "SDK/plugins must not reference distribution internals"
)

private val forbiddenHostUiTraversal = listOf(
    "SwingUtilities.getWindowAncestor(",
    "SwingUtilities.getRoot(",
    ".getTopLevelAncestor()"
)

tasks.register("checkModuleBoundaries") {
    group = "verification"
    description = "Verifies SDK/runtime/plugin dependency direction and host-internal import boundaries."
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
    when {
        subproject.path == ":sdk" -> {
            checkDeclaredBoundaryDependencies(subproject, setOf(":sdk"), "SDK", state)
            checkResolvedBoundaryComponents(config, subproject, setOf(":sdk"), state)
        }
        subproject.path.startsWith(":plugins:") -> {
            checkDeclaredBoundaryDependencies(subproject, setOf(":sdk"), "Plugin", state)
            checkResolvedBoundaryComponents(config, subproject, setOf(subproject.path, ":sdk"), state)
        }
    }
}

private fun checkDeclaredBoundaryDependencies(
    project: Project,
    allowedProjectPaths: Set<String>,
    ownerLabel: String,
    state: BoundaryState
) {
    project.configurations
        .filter { it.name in productionDependencyConfigurations }
        .flatMap { it.dependencies.toList() }
        .distinct()
        .forEach { dependency ->
            if (dependency is ProjectDependency) {
                val path = dependency.dependencyProject.path
                if (path !in allowedProjectPaths) {
                    state.reject("$ownerLabel ${project.path} may not depend on project component $path")
                }
            } else {
                state.reject(
                    "$ownerLabel ${project.path} may only declare approved project dependencies; " +
                        "found ${dependencyIdentity(dependency)}"
                )
            }
        }
}

private fun dependencyIdentity(dependency: Dependency): String =
    "${dependency.javaClass.simpleName}(${dependency.group ?: "<no-group>"}:${dependency.name}:${dependency.version ?: "<no-version>"})"

private fun checkResolvedBoundaryComponents(
    configuration: org.gradle.api.artifacts.Configuration,
    project: Project,
    allowedProjectPaths: Set<String>,
    state: BoundaryState
) {
    if (state.failed) return
    try {
        configuration.incoming.resolutionResult.allComponents.forEach { component ->
            val id = component.id
            if (id is ProjectComponentIdentifier) {
                if (id.projectPath !in allowedProjectPaths) {
                    state.reject("${project.path} resolved forbidden project component ${id.projectPath}")
                }
            } else {
                state.reject("${project.path} resolved forbidden non-project component ${id.displayName}")
            }
        }
    } catch (exception: Exception) {
        state.reject("${project.path} dependency identity resolution failed closed: ${exception.message}")
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
    val restricted = project.path == ":sdk" || project.path.startsWith(":plugins:")
    if (restricted) {
        checkRestrictedImports(root, file, lines, state)
        checkForbiddenQualifiedReferences(root, file, lines, state)
    }
    if (project.path.startsWith(":plugins:")) {
        checkForbiddenHostUiTraversal(root, file, lines, state)
    }
}

private fun checkRestrictedImports(root: Project, file: java.io.File, lines: List<String>, state: BoundaryState) {
    lines.forEachIndexed { index, line ->
        val trimmed = line.trim()
        if (trimmed.matches(Regex("import dev\\.turboism\\.distribution(?:\\..*)?;"))) {
            state.reject("Forbidden distribution import in ${file.relativeTo(root.projectDir)}:${index + 1}")
        }
        forbiddenImportPatterns.forEach { (pattern, message) ->
            if (trimmed.matches(Regex("import $pattern;"))) {
                state.reject("Forbidden import in ${file.relativeTo(root.projectDir)}:${index + 1}: $message")
            }
        }
    }
}

private fun checkForbiddenQualifiedReferences(
    root: Project,
    file: java.io.File,
    lines: List<String>,
    state: BoundaryState
) {
    val source = stripJavaCommentsAndStrings(lines.filterNot { it.trimStart().startsWith("import ") }.joinToString("\n"))
    forbiddenQualifiedReferencePatterns.forEach { (pattern, message) ->
        pattern.find(source)?.let { match ->
            state.reject(
                "Forbidden fully-qualified reference '${match.value}' in " +
                    "${file.relativeTo(root.projectDir)}: $message"
            )
        }
    }
}

private fun stripJavaCommentsAndStrings(source: String): String {
    val output = StringBuilder(source.length)
    var state = 0
    var escaped = false
    var index = 0
    while (index < source.length) {
        val character = source[index]
        when (state) {
            0 -> when {
                source.startsWith("//", index) -> {
                    output.append(' ')
                    state = 1
                    index += 2
                }
                source.startsWith("/*", index) -> {
                    output.append(' ')
                    state = 2
                    index += 2
                }
                character == '"' -> {
                    output.append(' ')
                    state = 3
                    index++
                }
                character == '\'' -> {
                    output.append(' ')
                    state = 4
                    index++
                }
                else -> {
                    output.append(character)
                    index++
                }
            }
            1 -> {
                if (character == '\n') {
                    output.append('\n')
                    state = 0
                }
                index++
            }
            2 -> {
                if (source.startsWith("*/", index)) {
                    output.append(' ')
                    state = 0
                    index += 2
                } else {
                    if (character == '\n') output.append('\n')
                    index++
                }
            }
            3, 4 -> {
                val closingQuote = if (state == 3) '"' else '\''
                when {
                    character.code == 92 && !escaped -> {
                        escaped = true
                        index++
                    }
                    escaped -> {
                        escaped = false
                        index++
                    }
                    character == closingQuote -> {
                        output.append(' ')
                        state = 0
                        index++
                    }
                    character == '\n' -> {
                        output.append('\n')
                        state = 0
                        index++
                    }
                    else -> index++
                }
            }
        }
    }
    return output.toString()
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
