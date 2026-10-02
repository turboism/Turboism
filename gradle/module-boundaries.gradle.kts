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

private val runtimeInternalImportPattern = "dev.turboism.*.internal.*"
private val forbiddenImportPatterns = listOf(
    runtimeInternalImportPattern to "SDK/public modules must not import runtime internal packages",
    "com.live2d.*" to "SDK/plugins must not import Cubism internal packages (com.live2d)",
    "dev.turboism.core.parameter.*" to "SDK/plugins must not import runtime parameter internals",
    "dev.turboism.core.mesh.*" to "SDK/plugins must not import runtime mesh internals",
    "dev.turboism.core.psd.*" to "SDK/plugins must not import runtime PSD internals",
    "dev.turboism.core.mirror.*" to "SDK/plugins must not import runtime mirror internals",
    "dev.turboism.sdk.event.cubism.*" to
        "SDK/plugins must not import the retired dev.turboism.sdk.event.cubism package " +
        "(Cubism events live in dev.turboism.sdk.cubism.event)"
)

private val forbiddenPackageDeclarations = listOf(
    Regex("""^\s*package\s+dev\.turboism\.sdk\.event\.cubism\s*;""") to
        "Retired package dev.turboism.sdk.event.cubism must not be reintroduced " +
        "(Cubism events live in dev.turboism.sdk.cubism.event)"
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

/*
 * The boundary check produces no artifact; the stamp file gives Gradle a persistent
 * output for up-to-date tracking. It is written only after the check action succeeds.
 */
private fun Task.verificationStamp() {
    val stamp = project.layout.buildDirectory.file("verification-stamps/$name.stamp")
    outputs.file(stamp)
    doLast {
        stamp.get().asFile.apply {
            parentFile.mkdirs()
            writeText("ok\n")
        }
    }
}

tasks.register("checkModuleBoundaries") {
    group = "verification"
    description = "Verifies SDK/runtime/plugin dependency direction and host-internal import boundaries."
    // The rules and waivers live in this script; module additions land in settings.gradle.kts.
    inputs.file("gradle/module-boundaries.gradle.kts")
    inputs.file("settings.gradle.kts")
    inputs.files(
        fileTree(rootDir) {
            include("**/src/main/java/**/*.java")
            exclude(".worktrees/**", ".claude/**", "**/build/**", ".git/**")
        },
        fileTree(rootDir) {
            include("**/build.gradle.kts")
            exclude(".worktrees/**", ".claude/**", "**/build/**", ".git/**")
        }
    )
    doLast {
        checkModuleBoundaries(rootProject)
    }
    verificationStamp()
}

/**
 * Layering inside the single :runtime module. The framework core, adapters, mapping, leaf
 * service implementations and shared infrastructure must not import upward into composition
 * layers or sideways into surfaces they are documented to sit below. Composition seams —
 * {@code bootstrap}, {@code shell}, {@code ui}, {@code preview}, {@code distribution} — and the
 * service-wiring package {@code core.plugin.context} may reach anywhere.
 *
 * Violations are fail-closed: a known debt is an explicit per-file waiver in
 * [runtimePackageWaivers], every other hit fails the gate so the baseline cannot regress.
 */
private class RuntimePackageRule(
    val sourceTopPackages: Set<String>,
    val sourceExclusions: Set<String>,
    val forbiddenTopPackages: Set<String>,
    val message: String
)

private val runtimeCompositionLayers = setOf("shell", "preview", "distribution")
private val runtimeFeatureImpls = setOf(
    "recentfile", "recentpreview", "screenshot", "script", "storage", "task", "config",
    "userfile", "hostread", "performance", "exportsettings", "filechooser", "mcp", "update"
)
private val runtimeInfra = setOf(
    "permissions", "diagnostics", "failure", "cleanup", "home", "graal", "i18n"
)

private val runtimePackageRules = listOf(
    RuntimePackageRule(
        setOf("core"),
        setOf("core/plugin/context/"),
        setOf("adapter", "hook", "shell", "ui", "preview", "distribution"),
        "framework core must not reach into adapter/host/composition layers"
    ),
    RuntimePackageRule(
        setOf("adapter"),
        emptySet(),
        setOf("shell", "preview", "distribution", "bootstrap"),
        "editor adapters must not reach into composition/bootstrap seams"
    ),
    RuntimePackageRule(
        setOf("mapping"),
        emptySet(),
        setOf("adapter", "shell", "ui", "preview", "distribution", "hook"),
        "host-artifact verification must stay below runtime surfaces"
    ),
    RuntimePackageRule(
        runtimeFeatureImpls,
        emptySet(),
        runtimeCompositionLayers,
        "service implementations must not reach into composition layers"
    ),
    RuntimePackageRule(
        runtimeInfra,
        emptySet(),
        setOf("adapter", "shell", "ui", "preview", "distribution", "hook", "mapping"),
        "shared infrastructure must not reach into runtime surfaces"
    )
)

/**
 * Accepted layering debt, one entry per file. Each waiver names the file relative to
 * {@code runtime/src/main/java/dev/turboism/} plus the direction it is allowed to keep.
 */
private val runtimePackageWaivers = mapOf<String, Set<String>>()

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
        subproject.path == ":core-contract" -> {
            // Internal management contracts depend only on the SDK surface they reference;
            // the module must never reach into runtime or plugin implementations.
            checkDeclaredBoundaryDependencies(
                subproject,
                setOf(":sdk"),
                "Core-contract",
                state
            )
            checkResolvedBoundaryComponents(
                config,
                subproject,
                setOf(":core-contract", ":sdk"),
                state
            )
        }
        subproject.path == ":runtime" -> {
            checkRuntimePluginDependencies(subproject, config, state)
        }
        subproject.path.startsWith(":plugins:") -> {
            checkDeclaredBoundaryDependencies(
                subproject,
                setOf(":sdk", ":event-processor"),
                "Plugin",
                state
            )
            checkResolvedBoundaryComponents(config, subproject, setOf(subproject.path, ":sdk"), state)
        }
    }
}

/**
 * The runtime is composition-neutral: it may carry external libraries but must never depend on
 * a plugin implementation module. The framework shell belongs to :runtime; its composition
 * contracts live in :core-contract and are never exposed to plugin consumers.
 */
private fun checkRuntimePluginDependencies(
    subproject: Project,
    config: org.gradle.api.artifacts.Configuration,
    state: BoundaryState
) {
    subproject.configurations
        .filter { it.name in productionDependencyConfigurations }
        .forEach { configuration ->
            configuration.dependencies.filterIsInstance<ProjectDependency>().forEach { dependency ->
                val path = dependency.dependencyProject.path
                if (path.startsWith(":plugins:")) {
                    state.reject(
                        "Runtime may not depend on plugin component $path from ${configuration.name}"
                    )
                }
            }
        }
    if (state.failed) return
    try {
        config.incoming.resolutionResult.allComponents.forEach { component ->
            val id = component.id
            if (id is ProjectComponentIdentifier && id.projectPath.startsWith(":plugins:")) {
                state.reject(":runtime resolved forbidden plugin component ${id.projectPath}")
            }
        }
    } catch (exception: Exception) {
        state.reject(":runtime dependency identity resolution failed closed: ${exception.message}")
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
        .forEach { configuration ->
            configuration.dependencies.forEach { dependency ->
                if (dependency is ProjectDependency) {
                    val path = dependency.dependencyProject.path
                    val admittedProcessor = ownerLabel == "Plugin"
                        && configuration.name == "annotationProcessor"
                        && path == ":event-processor"
                    val admitted = path in allowedProjectPaths
                        && (path != ":event-processor" || admittedProcessor)
                    if (!admitted) {
                        state.reject(
                            "$ownerLabel ${project.path} may not depend on project component $path " +
                                "from ${configuration.name}"
                        )
                    }
                } else {
                    state.reject(
                        "$ownerLabel ${project.path} may only declare approved project dependencies; " +
                            "found ${dependencyIdentity(dependency)} in ${configuration.name}"
                    )
                }
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
    checkForbiddenPackageDeclaration(root, file, lines, state)
    val restricted = project.path == ":sdk" || project.path.startsWith(":plugins:")
    if (restricted) {
        checkRestrictedImports(root, file, lines, state)
        checkForbiddenQualifiedReferences(root, file, lines, state)
        checkInternalContractReferences(root, file, lines, state)
    }
    if (project.path.startsWith(":plugins:")) {
        checkForbiddenHostUiTraversal(root, file, lines, state)
    }
    if (project.path == ":runtime") {
        checkRuntimePackageBoundaries(root, file, lines, state)
    }
}

private fun checkRuntimePackageBoundaries(
    root: Project,
    file: java.io.File,
    lines: List<String>,
    state: BoundaryState
) {
    val relative = file.relativeTo(root.file("runtime/src/main/java/dev/turboism")).path
        .replace('\\', '/')
    val topPackage = relative.substringBefore('/', "")
    if (topPackage.isEmpty()) {
        return
    }
    runtimePackageRules.forEach rules@{ rule ->
        if (topPackage !in rule.sourceTopPackages) {
            return@rules
        }
        if (rule.sourceExclusions.any { relative.startsWith(it) }) {
            return@rules
        }
        lines.forEach imports@{ line ->
            val match = Regex(
                """^\s*import\s+(?:static\s+)?dev\.turboism\.(\w+)(?:\.[\w.]*\w+)?\s*;"""
            ).find(line.trimStart())
            val target = match?.groupValues?.get(1) ?: return@imports
            if (target !in rule.forbiddenTopPackages) {
                return@imports
            }
            if (target in (runtimePackageWaivers[relative] ?: emptySet())) {
                return@imports
            }
            state.reject(
                "Runtime layering violation in runtime/.../$relative: " +
                    "dev.turboism.$topPackage must not import dev.turboism.$target " +
                    "(${rule.message}); add a runtimePackageWaivers entry only for a documented debt"
            )
        }
    }
}

private fun checkForbiddenPackageDeclaration(
    root: Project,
    file: java.io.File,
    lines: List<String>,
    state: BoundaryState
) {
    lines.forEach { line ->
        forbiddenPackageDeclarations.forEach { (pattern, message) ->
            if (pattern.containsMatchIn(line)) {
                state.reject("${file.relativeTo(root.projectDir)}: $message")
            }
        }
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
                state.reject(
                    "Forbidden import in ${file.relativeTo(root.projectDir)}:${index + 1}: $message"
                )
            }
        }
    }
}

private val internalContractReferencePattern =
    Regex("""(?<![\w$])dev\.turboism\.internal(?:\.[A-Za-z_$][\w$]*)+(?![\w$])""")

/**
 * Internal management contracts ({@code dev.turboism.internal.*}) are composition-internal: the
 * SDK and ordinary plugins must never import or reference them, so built-in-only services cannot
 * leak through the plugin surface. The framework shell lives in :runtime and needs no plugin exception.
 */
private fun checkInternalContractReferences(
    root: Project,
    file: java.io.File,
    lines: List<String>,
    state: BoundaryState
) {
    lines.forEachIndexed { index, line ->
        val trimmed = line.trim()
        if (trimmed.startsWith("import ") || trimmed.startsWith("import static ")) {
            val imported = trimmed
                .removePrefix("import ")
                .removePrefix("static ")
                .removeSuffix(";")
                .trim()
            if (imported.startsWith("dev.turboism.internal.")) {
                state.reject(
                    "Forbidden internal-contract import in " +
                        "${file.relativeTo(root.projectDir)}:${index + 1}: " +
                        "internal management contracts are not a plugin API"
                )
            }
        }
    }
    val source = stripJavaCommentsAndStrings(
        lines.filterNot { it.trimStart().startsWith("import ") }.joinToString("\n")
    )
    internalContractReferencePattern.findAll(source).forEach { match ->
        state.reject(
            "Forbidden internal-contract reference '${match.value}' in " +
                "${file.relativeTo(root.projectDir)}: " +
                "internal management contracts are not a plugin API"
        )
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

/*
 * checkPluginBoundaries is the compiled-artifact half of the plugin boundary.
 * checkModuleBoundaries scans sources and declared dependency paths; this gate
 * scans the bytecode every plugin actually ships, so copied or generated
 * classes cannot carry runtime/internal/Cubism references past the source
 * rules. Plugins are external consumers: only the JDK, dev.turboism.sdk.*,
 * dev.turboism.protocol.* (the SDK module's second root), the plugin's own
 * dev.turboism.plugin.* classes, and com.sun.* JDK surfaces are admissible.
 */
private val pluginBoundaryAllowedRoots = setOf(
    "sdk",     // the public API module
    "plugin",  // the plugin's own dev.turboism.plugin.* classes
    "protocol" // StrictJson and sibling wire helpers shipped inside :sdk
)

private val pluginBoundaryProjects = rootProject.subprojects
    .filter { it.path.startsWith(":plugins:") }

/**
 * Per-plugin boundary extras, kept as data inside this single mechanism the same way
 * [runtimePackageWaivers] records accepted layering debt. The selection-brush entry is
 * the former verifySdkOnlyProduction/verifySelectionBrushJar gate: a Preview-SDK canvas
 * plugin that must stay free of AWT/Swing, reflection, and runtime internals, plus its
 * packaged-resource JAR contract.
 */
private class PluginBoundaryExtras(
    val forbiddenSourceTokens: List<String> = emptyList(),
    val contractTaskNames: List<String> = emptyList()
)

private val pluginBoundaryExtras = mapOf(
    ":plugins:selection-brush" to PluginBoundaryExtras(
        forbiddenSourceTokens = listOf(
            "java.awt", "javax.swing", "java.lang.reflect", "dev.turboism.runtime",
            "dev.turboism.mapping", "VerifiedMemberResolver", "ClassLoader", "getDeclared"
        ),
        contractTaskNames = listOf("verifySelectionBrushJar")
    )
)

tasks.register("checkPluginBoundaries") {
    group = "verification"
    description = "Verifies every first-party plugin ships SDK-only production bytecode: " +
        "declared dependencies, forbidden class-reference prefixes, and per-plugin source rules."
    dependsOn(pluginBoundaryProjects.map { "${it.path}:classes" })
    dependsOn(
        pluginBoundaryExtras.flatMap { (path, extras) ->
            extras.contractTaskNames.map { "$path:$it" }
        }
    )
    inputs.file("gradle/module-boundaries.gradle.kts")
    inputs.file("settings.gradle.kts")
    inputs.file("build.gradle.kts")
    // The forbidden dotted roots are derived from the :runtime top-level package listing,
    // so the directory entries themselves are an input of this gate.
    inputs.files(
        fileTree("runtime/src/main/java/dev/turboism") { include("*/") }
    )
    pluginBoundaryProjects.forEach { plugin ->
        inputs.file(plugin.file("build.gradle.kts"))
        inputs.files(
            fileTree(plugin.layout.buildDirectory.dir("classes/java/main")) { include("**/*.class") }
        )
        inputs.files(
            fileTree(plugin.layout.projectDirectory.dir("src/main/java")) { include("**/*.java") }
        )
    }
    doLast {
        checkPluginBoundaries(rootProject, pluginBoundaryProjects)
    }
    verificationStamp()
}

private fun checkPluginBoundaries(root: Project, plugins: List<Project>) {
    val state = BoundaryState(root.logger)
    plugins.forEach { plugin ->
        checkPluginDependencyScopes(plugin, state)
        checkPluginBytecodeReferences(root, plugin, state)
        checkPluginForbiddenSourceTokens(root, plugin, state)
    }
    if (state.failed) {
        throw GradleException("Plugin boundary checks failed.")
    }
    root.logger.lifecycle("Plugin boundary checks passed for ${plugins.size} plugins.")
}

/*
 * Declared-scope precision on top of checkModuleBoundaries: :sdk is compileOnly,
 * :event-processor is annotationProcessor, and no other or external dependency may
 * appear in any production configuration. An implementation-scoped :sdk would embed
 * the shared SDK identity into the plugin archive and break the single-typeidentity
 * contract the bootstrap layer depends on.
 */
private val pluginDependencyScopes = mapOf(
    "compileOnly" to setOf(":sdk"),
    "annotationProcessor" to setOf(":event-processor")
)

private fun checkPluginDependencyScopes(plugin: Project, state: BoundaryState) {
    plugin.configurations
        .filter { it.name in productionDependencyConfigurations }
        .forEach { configuration ->
            configuration.dependencies.forEach { dependency ->
                if (dependency is ProjectDependency) {
                    val path = dependency.dependencyProject.path
                    if (path !in pluginDependencyScopes[configuration.name].orEmpty()) {
                        state.reject(
                            "Plugin ${plugin.path} may not depend on $path from " +
                                "${configuration.name}; plugins take :sdk as compileOnly " +
                                "and :event-processor as annotationProcessor"
                        )
                    }
                } else {
                    state.reject(
                        "Plugin ${plugin.path} may not declare non-project dependency " +
                            "${dependencyIdentity(dependency)} in ${configuration.name}"
                    )
                }
            }
        }
}

private fun isBinaryNameByte(value: Byte): Boolean =
    value in 'a'.code..'z'.code || value in 'A'.code..'Z'.code ||
        value in '0'.code..'9'.code || value == '_'.code.toByte() ||
        value == '$'.code.toByte()

private fun ByteArray.indexOfNeedle(needle: ByteArray, from: Int): Int {
    if (size < needle.size) return -1
    outer@ for (i in from..size - needle.size) {
        for (j in needle.indices) {
            if (this[i + j] != needle[j]) continue@outer
        }
        return i
    }
    return -1
}

/*
 * Dotted `dev.turboism.` occurrences are string payloads — Class.forName targets,
 * config-key namespaces like `dev.turboism.texture-atlas.*`, event ids. Unlike
 * binary names they may be kebab-case keys, so the dotted scan denies the actual
 * runtime/internal package roots instead of whitelisting. New runtime packages
 * are picked up automatically from the :runtime source tree.
 */
private val pluginBoundaryForbiddenDottedExtras = setOf(
    "internal",       // :core-contract management contracts
    "bootstrap",      // java-agent entrypoint surface
    "eventprocessor", // annotation-processor implementation
    "graalhost"       // native-image host surface
)

private fun pluginBoundaryForbiddenDottedRoots(root: Project): Set<String> {
    val runtimeRoot = root.file("runtime/src/main/java/dev/turboism")
    val runtimePackages = runtimeRoot.listFiles()
        ?.filter { it.isDirectory }
        ?.map { it.name }
        .orEmpty()
    return runtimePackages.toSet() + pluginBoundaryForbiddenDottedExtras
}

/**
 * Scans one compiled class file for `dev.turboism` and `com.live2d` references.
 * Binary `dev/turboism/<segment>` class references must stay inside
 * [pluginBoundaryAllowedRoots]; dotted `dev.turboism.<segment>` payloads fail on
 * [pluginBoundaryForbiddenDottedRoots]. A bare `dev/turboism/<ClassName>` root-package
 * reference fails the binary scan because it matches no allowed root.
 */
private fun checkPluginClassFile(
    root: Project,
    file: java.io.File,
    forbiddenDottedRoots: Set<String>,
    state: BoundaryState
) {
    val bytes = file.readBytes()
    val binaryNeedle = "dev/turboism/".toByteArray(Charsets.UTF_8)
    var offset = bytes.indexOfNeedle(binaryNeedle, 0)
    while (offset >= 0) {
        var end = offset + binaryNeedle.size
        while (end < bytes.size && isBinaryNameByte(bytes[end])) {
            end++
        }
        val segment = bytes.decodeToString(offset + binaryNeedle.size, end)
        if (segment.isNotEmpty() && segment !in pluginBoundaryAllowedRoots) {
            state.reject(
                "Plugin class ${file.relativeTo(root.projectDir)} references forbidden " +
                    "package dev/turboism/$segment"
            )
        }
        offset = bytes.indexOfNeedle(binaryNeedle, offset + binaryNeedle.size)
    }
    val dottedNeedle = "dev.turboism.".toByteArray(Charsets.UTF_8)
    offset = bytes.indexOfNeedle(dottedNeedle, 0)
    while (offset >= 0) {
        var end = offset + dottedNeedle.size
        while (end < bytes.size && isBinaryNameByte(bytes[end])) {
            end++
        }
        val segment = bytes.decodeToString(offset + dottedNeedle.size, end)
        if (segment in forbiddenDottedRoots) {
            state.reject(
                "Plugin class ${file.relativeTo(root.projectDir)} references forbidden " +
                    "package dev.turboism.$segment"
            )
        }
        offset = bytes.indexOfNeedle(dottedNeedle, offset + dottedNeedle.size)
    }
    if (bytes.indexOfNeedle("com/live2d/".toByteArray(Charsets.UTF_8), 0) >= 0 ||
        bytes.indexOfNeedle("com.live2d.".toByteArray(Charsets.UTF_8), 0) >= 0
    ) {
        state.reject(
            "Plugin class ${file.relativeTo(root.projectDir)} references forbidden " +
                "Cubism internals (com.live2d)"
        )
    }
}

private fun checkPluginBytecodeReferences(root: Project, plugin: Project, state: BoundaryState) {
    val classesDir = plugin.layout.buildDirectory.dir("classes/java/main").get().asFile
    if (!classesDir.isDirectory) {
        state.reject("Plugin ${plugin.path} has no compiled classes at ${classesDir.relativeTo(root.projectDir)}")
        return
    }
    val forbiddenDottedRoots = pluginBoundaryForbiddenDottedRoots(root)
    classesDir.walkTopDown()
        .filter { it.isFile && it.extension == "class" }
        .forEach { checkPluginClassFile(root, it, forbiddenDottedRoots, state) }
}

private fun checkPluginForbiddenSourceTokens(root: Project, plugin: Project, state: BoundaryState) {
    val extras = pluginBoundaryExtras[plugin.path] ?: return
    val sourceRoot = plugin.file("src/main/java")
    if (!sourceRoot.isDirectory) return
    sourceRoot.walkTopDown()
        .filter { it.isFile && it.extension == "java" }
        .forEach { source ->
            val text = source.readText()
            extras.forbiddenSourceTokens.forEach { token ->
                if (token in text) {
                    state.reject(
                        "${source.relativeTo(root.projectDir)}: forbidden production token $token"
                    )
                }
            }
        }
}

/*
 * checkPluginPermissionAudit closes the manifest-as-audit trail (ARCHITECTURE §8:
 * plugin isolation is not a sandbox). Bytecode constant-pool member references to
 * JDK risk-boundary APIs — network transport, filesystem writes/reads, process
 * spawning — must be backed by the matching turboism.* permission id declared in
 * the plugin manifest. Pure path/address objects (java.nio.file.Path, java.net.URI,
 * java.net.InetAddress) and plugin-private jar resources carry no boundary and are
 * not tracked.
 */
private val pluginNetworkOwnerPrefixes = listOf(
    "java/net/http/", "com/sun/net/httpserver/", "javax/net/ssl/SSLSocket",
    "javax/net/ssl/SSLServerSocket", "javax/net/ssl/HttpsURLConnection",
    "java/net/Socket", "java/net/ServerSocket", "java/net/DatagramSocket",
    "java/net/MulticastSocket", "java/net/URLConnection", "java/net/HttpURLConnection",
    "java/net/JarURLConnection", "java/nio/channels/SocketChannel",
    "java/nio/channels/ServerSocketChannel", "java/nio/channels/DatagramChannel",
    "java/nio/channels/AsynchronousSocketChannel",
    "java/nio/channels/AsynchronousServerSocketChannel"
)

private val pluginNetworkMembers = mapOf(
    "java/net/URL" to setOf("openConnection", "openStream"),
    "java/net/InetAddress" to setOf("getByName", "getAllByName")
)

private val pluginProcessMembers = mapOf(
    "java/lang/ProcessBuilder" to null, // any member: construction already commits a spawn plan
    "java/lang/Runtime" to setOf("exec"),
    "java/awt/Desktop" to setOf("browse", "browseFileDirectory", "open", "edit", "mail", "print")
)

private val pluginFileWriteMembers = mapOf(
    "java/nio/file/Files" to setOf(
        "write", "writeString", "delete", "deleteIfExists", "move", "copy",
        "createDirectory", "createDirectories", "createFile", "createTempFile",
        "createTempDirectory", "newOutputStream", "newBufferedWriter", "setAttribute",
        "setPosixFilePermissions", "setLastModifiedTime", "setOwner", "createLink",
        "createSymbolicLink"
    ),
    "java/io/FileOutputStream" to null,
    "java/io/FileWriter" to null,
    "java/io/File" to setOf(
        "delete", "mkdir", "mkdirs", "createNewFile", "renameTo", "setWritable",
        "setReadable", "setExecutable", "setLastModified", "deleteOnExit"
    ),
    "java/io/PrintWriter" to setOf("<init>"),
    "java/io/PrintStream" to setOf("<init>"),
    // Only the File overloads of ImageIO.write cross the file boundary.
    "javax/imageio/ImageIO" to setOf("write")
)

private val pluginFileReadMembers = mapOf(
    "java/nio/file/Files" to setOf(
        "exists", "notExists", "isDirectory", "isRegularFile", "isSymbolicLink",
        "isHidden", "isReadable", "isWritable", "isExecutable", "size", "list",
        "walk", "walkFileTree", "find", "lines", "readAllBytes", "readAllLines",
        "readString", "readSymbolicLink", "newInputStream", "newBufferedReader",
        "newDirectoryStream", "getLastModifiedTime", "readAttributes", "getAttribute",
        "getOwner", "getPosixFilePermissions", "getFileAttributeView",
        "probeContentType", "getFileStore", "isSameFile", "mismatch"
    ),
    "java/io/FileInputStream" to null,
    "java/io/FileReader" to null,
    "java/io/File" to setOf(
        "exists", "isFile", "isDirectory", "isHidden", "isAbsolute", "canRead",
        "canWrite", "canExecute", "length", "lastModified", "list", "listFiles",
        "listRoots", "getFreeSpace", "getTotalSpace", "getUsableSpace"
    ),
    // Constructing a ZipFile opens the archive for reading.
    "java/util/zip/ZipFile" to setOf("<init>"),
    // Only the File overloads of ImageIO.read cross the file boundary.
    "javax/imageio/ImageIO" to setOf("read")
)

/* Channel open modes and RandomAccessFile "r"/"rw" flags are runtime arguments:
 * a member reference here crosses the file boundary on both ends. */
private val pluginFileReadWriteMembers = mapOf(
    "java/io/RandomAccessFile" to null,
    "java/nio/channels/FileChannel" to setOf("open"),
    "java/nio/file/Files" to setOf("newByteChannel")
)

/**
 * Audited plugins whose bytecode trips a rule for a documented reason; the entry
 * replaces a manifest declaration only when the boundary is provably delegated
 * (e.g. a handle issued by an SDK service). Empty by design.
 */
private val pluginPermissionAuditWaivers = mapOf<String, Map<String, String>>()

tasks.register("checkPluginPermissionAudit") {
    group = "verification"
    description = "Cross-checks plugin bytecode JDK risk-boundary calls against manifest permission ids."
    dependsOn(pluginBoundaryProjects.map { "${it.path}:classes" })
    inputs.file("gradle/module-boundaries.gradle.kts")
    inputs.file("settings.gradle.kts")
    pluginBoundaryProjects.forEach { plugin ->
        inputs.files(
            fileTree(plugin.layout.buildDirectory.dir("classes/java/main")) { include("**/*.class") }
        )
        inputs.file(
            plugin.file("src/main/resources/META-INF/turboism/plugin.json")
        )
    }
    doLast {
        checkPluginPermissionAudit(rootProject, pluginBoundaryProjects)
    }
    verificationStamp()
}

private fun checkPluginPermissionAudit(root: Project, plugins: List<Project>) {
    val state = BoundaryState(root.logger)
    plugins.forEach { plugin ->
        val descriptor = plugin.file("src/main/resources/META-INF/turboism/plugin.json")
        val declared = declaredPermissionIds(descriptor)
        val classesDir = plugin.layout.buildDirectory.dir("classes/java/main").get().asFile
        if (!classesDir.isDirectory) {
            state.reject("Plugin ${plugin.path} has no compiled classes at ${classesDir.relativeTo(root.projectDir)}")
            return@forEach
        }
        val waivers = pluginPermissionAuditWaivers[plugin.path].orEmpty()
        classesDir.walkTopDown()
            .filter { it.isFile && it.extension == "class" }
            .forEach { classFile ->
                auditPluginClassFile(root, plugin, classFile, declared, waivers, state)
            }
    }
    if (state.failed) {
        throw GradleException("Plugin permission audit failed.")
    }
    root.logger.lifecycle("Plugin permission audit passed for ${plugins.size} plugins.")
}

private fun declaredPermissionIds(descriptor: java.io.File): Set<String> {
    val text = descriptor.readText()
    val key = text.indexOf("\"permissions\"")
    if (key < 0) return emptySet()
    val arrayStart = text.indexOf('[', key)
    if (arrayStart < 0) return emptySet()
    var depth = 0
    var end = -1
    for (i in arrayStart until text.length) {
        when (text[i]) {
            '[' -> depth++
            ']' -> {
                depth--
                if (depth == 0) {
                    end = i
                    break
                }
            }
        }
    }
    if (end < 0) return emptySet()
    return Regex("\"id\"\\s*:\\s*\"([^\"]+)\"")
        .findAll(text.substring(arrayStart, end))
        .map { it.groupValues[1] }
        .toSet()
}

/** Constant-pool member reference: owner binary name, member name, type descriptor. */
private class MemberRef(val owner: String, val name: String, val descriptor: String)

private fun parseConstantPoolMembers(classFile: java.io.File, bytes: ByteArray): List<MemberRef> {
    // Fail closed: a .class file that cannot be parsed must break the audit rather than
    // silently contribute zero member references to it.
    if (bytes.size < 10 || bytes.copyOfRange(0, 4).contentEquals(byteArrayOf(-54, -2, -70, -66)).not()) {
        throw GradleException(
            "Plugin permission audit cannot parse $classFile: not a valid class file header"
        )
    }
    fun u2(offset: Int) = (bytes[offset].toInt() and 0xFF shl 8) or (bytes[offset + 1].toInt() and 0xFF)
    val count = u2(8)
    val utf8 = arrayOfNulls<String>(count)
    val classNameIndex = IntArray(count)
    val nameAndTypeName = IntArray(count)
    val nameAndTypeDescriptor = IntArray(count)
    val memberRefOwner = IntArray(count)
    val memberRefNameAndType = IntArray(count)
    var position = 10
    var index = 1
    try {
        while (index < count) {
            when (bytes[position].toInt() and 0xFF) {
                1 -> {
                    val length = u2(position + 1)
                    utf8[index] = String(bytes, position + 3, length, Charsets.ISO_8859_1)
                    position += 3 + length
                }
                3, 4 -> position += 5
                5, 6 -> {
                    position += 9
                    index++
                }
                7 -> {
                    classNameIndex[index] = u2(position + 1)
                    position += 3
                }
                8 -> position += 3
                9, 10, 11 -> {
                    memberRefOwner[index] = u2(position + 1)
                    memberRefNameAndType[index] = u2(position + 3)
                    position += 5
                }
                12 -> {
                    nameAndTypeName[index] = u2(position + 1)
                    nameAndTypeDescriptor[index] = u2(position + 3)
                    position += 5
                }
                15 -> position += 4
                16 -> position += 3
                17, 18 -> position += 5
                19, 20 -> position += 3
                else -> throw GradleException(
                    "Plugin permission audit cannot parse $classFile: " +
                        "unknown constant-pool tag ${bytes[position].toInt() and 0xFF} at entry $index"
                )
            }
            index++
        }
    } catch (truncated: IndexOutOfBoundsException) {
        throw GradleException(
            "Plugin permission audit cannot parse $classFile: truncated constant pool", truncated
        )
    }
    val members = mutableListOf<MemberRef>()
    for (entry in 1 until count) {
        val ownerIndex = memberRefOwner[entry]
        if (ownerIndex == 0) continue
        val owner = utf8[classNameIndex[ownerIndex]]
        val nat = memberRefNameAndType[entry]
        val name = utf8[nameAndTypeName[nat]]
        val descriptor = utf8[nameAndTypeDescriptor[nat]]
        if (owner != null && name != null) {
            members.add(MemberRef(owner, name, descriptor.orEmpty()))
        }
    }
    return members
}

private fun requiredPermissionsFor(owner: String, name: String, descriptor: String): Set<String> {
    val required = mutableSetOf<String>()
    if (pluginNetworkOwnerPrefixes.any { owner.startsWith(it) } ||
        pluginNetworkMembers[owner]?.contains(name) == true
    ) {
        required += "turboism.network.fetch"
    }
    val processNames = pluginProcessMembers[owner]
    if (pluginProcessMembers.containsKey(owner) && (processNames == null || name in processNames)) {
        required += "turboism.process.run"
    }
    val writeNames = pluginFileWriteMembers[owner]
    if (pluginFileWriteMembers.containsKey(owner) && (writeNames == null || name in writeNames) &&
        descriptorTargetsFile(owner, descriptor)
    ) {
        required += "turboism.file.write"
    }
    val readNames = pluginFileReadMembers[owner]
    if (pluginFileReadMembers.containsKey(owner) && (readNames == null || name in readNames) &&
        descriptorTargetsFile(owner, descriptor)
    ) {
        required += "turboism.file.read"
    }
    val channelNames = pluginFileReadWriteMembers[owner]
    if (pluginFileReadWriteMembers.containsKey(owner) && (channelNames == null || name in channelNames)) {
        required += "turboism.file.read"
        required += "turboism.file.write"
    }
    return required
}

/*
 * Some owners only cross the file boundary for a subset of their tracked members'
 * signatures: PrintWriter/PrintStream write a file only when constructed over a
 * File or a String path (Writer/OutputStream delegates stay off the boundary),
 * and ImageIO.read/write only touch files for the File overloads.
 */
private fun descriptorTargetsFile(owner: String, descriptor: String): Boolean = when (owner) {
    "java/io/PrintWriter", "java/io/PrintStream" ->
        descriptor.startsWith("(Ljava/io/File;") || descriptor.startsWith("(Ljava/lang/String;")
    "javax/imageio/ImageIO" -> descriptor.contains("java/io/File")
    else -> true
}

private fun auditPluginClassFile(
    root: Project,
    plugin: Project,
    classFile: java.io.File,
    declared: Set<String>,
    waivers: Map<String, String>,
    state: BoundaryState
) {
    val bytes = classFile.readBytes()
    val members = parseConstantPoolMembers(classFile, bytes)
    members.forEach { member ->
        requiredPermissionsFor(member.owner, member.name, member.descriptor).forEach { permission ->
            val evidence = "${member.owner}.${member.name}"
            if (permission !in declared && evidence !in waivers) {
                state.reject(
                    "Plugin ${plugin.path} class ${classFile.relativeTo(root.projectDir)} calls " +
                        "$evidence which requires declared permission $permission in " +
                        "src/main/resources/META-INF/turboism/plugin.json"
                )
            }
        }
    }
}
