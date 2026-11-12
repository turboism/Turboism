import org.gradle.api.Project
import org.gradle.api.artifacts.Dependency
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.logging.Logger
import dev.turboism.gradle.internal.VerificationStamps
import dev.turboism.gradle.internal.boundary.JavaSourceScan
import dev.turboism.gradle.internal.boundary.PermissionVocabulary
import dev.turboism.gradle.internal.boundary.PluginClassReferences
import dev.turboism.gradle.internal.boundary.PluginPermissionAudit

private class BoundaryState(private val logger: Logger) {
    var failed = false

    fun reject(message: String) {
        logger.error(message)
        failed = true
    }
}

private val productionDependencyConfigurations = setOf(
    "api", "compileOnly", "compileOnlyApi", "implementation", "runtimeOnly", "annotationProcessor"
)

tasks.register("checkModuleBoundaries") {
    group = "verification"
    description = "Verifies SDK/runtime/plugin dependency direction and host-internal import boundaries."
    // The rules and waivers live in this script; module additions land in settings.gradle.kts.
    inputs.file("gradle/module-boundaries.gradle.kts")
    inputs.file("settings.gradle.kts")
    // The kernel is proved from its compiled classes as well as its sources, so the
    // bytecode half of its contract only passes after it actually compiles.
    dependsOn(":persistence-core:classes", ":compatibility-policy:classes")
    inputs.files(
        fileTree(project(":persistence-core").layout.buildDirectory.dir("classes/java/main")) {
            include("**/*.class")
        },
        fileTree(project(":compatibility-policy").layout.buildDirectory.dir("classes/java/main")) {
            include("**/*.class")
        },
        fileTree(rootDir) {
            include("**/src/main/java/**/*.java", "**/src/main/resources/**/*.java")
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
    VerificationStamps.apply(this)
}

/**
 * Layering inside the single :runtime module. The framework core, adapters, mapping, leaf
 * service implementations and shared infrastructure must not import upward into composition
 * layers or sideways into surfaces they are documented to sit below. Composition seams —
 * {@code bootstrap}, {@code shell}, {@code ui}, {@code kernel}, {@code distribution} and
 * {@code pluginmanagement} — may reach anywhere; the plugin service wiring lives in
 * {@code kernel.context} so no core package needs an exemption. The bottom-most {@code runtime}
 * and {@code internal} packages may reach nothing above them, which is what keeps infrastructure
 * such as {@code runtime.log} from depending on the framework core.
 *
 * Violations are fail-closed: a known debt is an explicit per-file waiver in
 * [runtimePackageWaivers], every other hit fails the gate so the baseline cannot regress.
 */
private class RuntimePackageRule(
    val sourceTopPackages: Set<String>,
    val forbiddenTopPackages: Set<String>,
    val message: String
)

private val runtimeCompositionLayers = setOf("shell", "kernel", "distribution", "pluginmanagement")
private val runtimeFeatureImpls = setOf(
    "recentfile", "recentpreview", "screenshot", "script", "storage", "task", "config",
    "userfile", "hostread", "performance", "exportsettings", "filechooser", "mcp", "update",
    "worktime", "keybinding", "automesh"
)
// Bottom-most packages: the JDK, the SDK and their leaf siblings only — every layer above
// them is forbidden, which is what keeps runtime.log off the framework core.
private val runtimeLeafPackages = setOf("runtime", "internal")
private val runtimeInfra = setOf(
    "permissions", "diagnostics", "failure", "cleanup", "home", "graal", "i18n"
) + runtimeLeafPackages

private val runtimePackageRules = listOf(
    RuntimePackageRule(
        setOf("core"),
        setOf("adapter", "hook", "ui") + runtimeCompositionLayers,
        "framework core must not reach into adapter/host/composition layers"
    ),
    RuntimePackageRule(
        setOf("adapter"),
        runtimeCompositionLayers + setOf("bootstrap"),
        "editor adapters must not reach into composition/bootstrap seams"
    ),
    RuntimePackageRule(
        setOf("mapping"),
        setOf("adapter", "ui", "hook") + runtimeCompositionLayers,
        "host-artifact verification must stay below runtime surfaces"
    ),
    RuntimePackageRule(
        runtimeFeatureImpls,
        runtimeCompositionLayers,
        "service implementations must not reach into composition layers"
    ),
    RuntimePackageRule(
        runtimeInfra,
        setOf("adapter", "ui", "hook", "mapping") + runtimeCompositionLayers,
        "shared infrastructure must not reach into runtime surfaces"
    ),
    RuntimePackageRule(
        runtimeLeafPackages,
        setOf("adapter", "ui", "hook", "mapping", "core") + runtimeCompositionLayers +
            runtimeFeatureImpls + (runtimeInfra - runtimeLeafPackages),
        "leaf runtime/internal packages must not reach into core, feature, or surface layers"
    )
)

/**
 * Every top-level package {@code :runtime} is allowed to declare. The rules above only bind the
 * packages they name, so a new top-level package would silently escape the layering contract;
 * [checkRuntimeTopPackageClassification] makes that fail closed until the package is placed in a
 * layer here.
 */
private val runtimeClassifiedTopPackages =
    runtimeCompositionLayers + setOf("core", "adapter", "mapping", "ui", "bootstrap") +
        runtimeFeatureImpls + runtimeInfra

/**
 * Accepted layering debt, one entry per file. Each waiver names the file relative to
 * {@code runtime/src/main/java/dev/turboism/} plus the direction it is allowed to keep.
 */
private val runtimePackageWaivers: Map<String, Set<String>> = mapOf(
    // Empty on purpose: the last entry (CapabilitySelectorDependencies reaching into
    // adapter.cubism.*) was removed once the mesh-tool session and modeling selection
    // contracts moved into mapping.verification.selector. Keep it empty - a new waiver
    // needs a documented reason and its own removal plan.
)

/**
 * Sub-package direction rules inside one top-level package, keyed by the source sub-package
 * prefix. The top-level rules above cannot see these: {@code core} imports {@code core} freely,
 * so a cycle between two core sub-packages would pass them. Each entry names the sub-package
 * that may not import the listed sibling prefixes, which keeps the manifest reader below the
 * schema vocabulary that reports its findings.
 */
private class RuntimeSubPackageRule(
    val sourcePrefix: String,
    val forbiddenPrefixes: Set<String>,
    val message: String
)

private val runtimeSubPackageRules = listOf(
    RuntimeSubPackageRule(
        "core/schema/",
        setOf("core/descriptor/"),
        "schema vocabulary must not reach into the manifest reader that reports its findings"
    )
)

private fun checkModuleBoundaries(project: Project) {
    val state = BoundaryState(project.logger)
    project.subprojects.forEach { subproject ->
        checkProjectDependencies(subproject, state)
        checkAsmDependencies(subproject, state)
        scanProductionSources(project, subproject, state)
    }
    checkRuntimeTopPackageClassification(project, state)
    checkOpaqueUserFileRuntime(project, state)
    checkOpaqueUserFileSdk(project, state)
    checkPersistenceCoreSources(project, state)
    checkPersistenceCoreBytecode(project, state)
    checkCompatibilityPolicySources(project, state)
    checkCompatibilityPolicyBytecode(project, state)
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
        subproject.path == ":persistence-core" -> {
            // The typed-config kernel is expressed in frozen SDK types only; a dependency
            // back on :runtime (or anything else) would dissolve the boundary it exists for.
            checkDeclaredBoundaryDependencies(
                subproject,
                setOf(":sdk"),
                "Persistence-core",
                state
            )
            checkResolvedBoundaryComponents(
                config,
                subproject,
                setOf(":persistence-core", ":sdk"),
                state
            )
        }
        subproject.path == ":compatibility-policy" -> {
            // The admission policy reads frozen SDK declarations only; a dependency back
            // on :runtime (or anything else) would dissolve the host-independent boundary.
            checkDeclaredBoundaryDependencies(
                subproject,
                setOf(":sdk"),
                "Compatibility-policy",
                state
            )
            checkResolvedBoundaryComponents(
                config,
                subproject,
                setOf(":compatibility-policy", ":sdk"),
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

/*
 * External artifacts a first-party plugin may link against, keyed by module path and written as
 * `group:name:version`. The list is closed on purpose: an unlisted external dependency fails in
 * both the declared and the resolved half of the gate, so embedding a third-party library in a
 * plugin stays a reviewed decision instead of a build-file accident.
 */
private val pluginExternalComponents = mapOf(
    // Turbo-mesh embeds the Tinfour constrained-Delaunay kernel into its own jar, so the plugin
    // ships the triangulation code with no host-provided library on its class path.
    ":plugins:turbo-mesh" to setOf("org.tinfour:TinfourCore:2.1.8")
)

private fun admittedPluginExternalComponents(project: Project): Set<String> =
    pluginExternalComponents[project.path].orEmpty()

/** `group:name:version` of a declared external module dependency, or an empty string. */
private fun externalComponentIdentity(dependency: Dependency): String {
    val group = dependency.group ?: return ""
    return "$group:${dependency.name}:${dependency.version}"
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
                } else if (externalComponentIdentity(dependency) !in admittedPluginExternalComponents(project)) {
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
                val admitted = id is ModuleComponentIdentifier
                    && "${id.group}:${id.module}:${id.version}" in admittedPluginExternalComponents(project)
                if (!admitted) {
                    state.reject("${project.path} resolved forbidden non-project component ${id.displayName}")
                }
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
    // `src/tooling/java` is build-time review tooling compiled in :runtime; it sits in the same
    // layering scheme as the main sources even though it never ships in the runtime JAR.
    listOf(project.file("src/main/java"), project.file("src/main/resources"), project.file("src/tooling/java"))
        .forEach { sourceDir ->
            if (!sourceDir.exists()) {
                return@forEach
            }
            sourceDir.walkTopDown().filter { it.isFile && it.extension == "java" }.forEach { file ->
                checkSourceFile(root, project, file, state)
            }
        }
}

/*
 * The source-level rules (retired package, restricted imports, fully-qualified references,
 * internal management contracts, host UI traversal, direct executor/JSON construction) live in
 * buildSrc as JavaSourceScan, next to the comment/string lexer they depend on and the fixtures
 * that pin it. This script decides which module a file belongs to and which families therefore
 * apply.
 */
private fun checkSourceFile(root: Project, project: Project, file: java.io.File, state: BoundaryState) {
    val lines = file.readLines()
    val source = JavaSourceScan.SourceFile(file.relativeTo(root.projectDir).toString(), lines)
    JavaSourceScan.forbiddenPackageDeclarations(source).forEach(state::reject)
    JavaSourceScan.hostPackageLiterals(source).forEach(state::reject)
    JavaSourceScan.directRuntimeConstructions(source).forEach(state::reject)
    val restricted = project.path == ":sdk" || project.path.startsWith(":plugins:")
    if (restricted) {
        JavaSourceScan.restrictedImports(source).forEach(state::reject)
        JavaSourceScan.qualifiedReferences(source).forEach(state::reject)
        JavaSourceScan.internalContractReferences(source).forEach(state::reject)
    }
    if (project.path.startsWith(":plugins:")) {
        JavaSourceScan.hostUiTraversal(source).forEach(state::reject)
    }
    // Resources carry .java texts that are never compiled, so the runtime layering rules
    // (which are keyed to compiled source packages) only apply under src/main/java and the
    // build-time src/tooling/java source set.
    if (project.path == ":runtime" &&
        (file.startsWith(project.file("src/main/java")) ||
            file.startsWith(project.file("src/tooling/java")))
    ) {
        checkRuntimePackageBoundaries(root, file, lines, state)
    }
}

/**
 * Path of a runtime source relative to {@code dev/turboism}, for both compiled source sets: the
 * shipped {@code src/main/java} tree and the build-time {@code src/tooling/java} tree. An empty
 * string means the file sits directly in {@code dev/turboism} and carries no top-level package.
 */
private fun runtimeSourceRelativePath(root: Project, file: java.io.File): String {
    val devRoots = listOf(
        root.file("runtime/src/main/java/dev/turboism"),
        root.file("runtime/src/tooling/java/dev/turboism")
    )
    val devRoot = devRoots.firstOrNull { file.startsWith(it) } ?: return ""
    return file.relativeTo(devRoot).path.replace('\\', '/')
}

private fun checkRuntimePackageBoundaries(
    root: Project,
    file: java.io.File,
    lines: List<String>,
    state: BoundaryState
) {
    val relative = runtimeSourceRelativePath(root, file)
    val topPackage = relative.substringBefore('/', "")
    if (topPackage.isEmpty()) {
        return
    }
    runtimePackageRules.forEach rules@{ rule ->
        if (topPackage !in rule.sourceTopPackages) {
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
    checkRuntimeSubPackageBoundaries(relative, lines, state)
}

/**
 * Applies [runtimeSubPackageRules]: a source under one core sub-package may not import a sibling
 * sub-package the rule forbids. The import target is matched by its dotted form, so a static
 * import of a nested type is caught by the same rule as a plain type import.
 */
private fun checkRuntimeSubPackageBoundaries(
    relative: String,
    lines: List<String>,
    state: BoundaryState
) {
    runtimeSubPackageRules.forEach rules@{ rule ->
        if (!relative.startsWith(rule.sourcePrefix)) {
            return@rules
        }
        val forbiddenDotted = rule.forbiddenPrefixes.map { it.replace('/', '.') }
        lines.forEach imports@{ line ->
            val match = Regex(
                """^\s*import\s+(?:static\s+)?dev\.turboism\.([\w.]+)\s*;"""
            ).find(line.trimStart())
            val imported = match?.groupValues?.get(1) ?: return@imports
            val forbidden = forbiddenDotted.firstOrNull { imported.startsWith(it) } ?: return@imports
            state.reject(
                "Runtime sub-package violation in runtime/.../$relative: " +
                    "${rule.sourcePrefix} must not import $forbidden (${rule.message})"
            )
        }
    }
}

private fun checkRuntimeTopPackageClassification(root: Project, state: BoundaryState) {
    val devRoot = root.file("runtime/src/main/java/dev/turboism")
    if (!devRoot.isDirectory) {
        // Minimal fixtures build a :runtime without a source tree; there is nothing to classify.
        return
    }
    val topPackages = devRoot.listFiles()
        ?.filter { it.isDirectory }
        ?.map { it.name }
        .orEmpty()
        .sorted()
    topPackages.filterNot { it in runtimeClassifiedTopPackages }.forEach { pkg ->
        state.reject(
            "Unclassified :runtime top-level package dev.turboism.$pkg: " +
                "classify it in gradle/module-boundaries.gradle.kts"
        )
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
 * :persistence-core owns the pure typed-config kernel. Its sources may name only its own
 * package, frozen SDK types, and the JDK value/collection/charset types it needs; the
 * compiled bytecode is checked too, because an import-only rule cannot see a fully
 * qualified reference. The allowlist is intentionally a prefix list rather than a ban:
 * a new JDK package is a reviewed contract change, not an accident.
 */
private val persistenceCoreAllowedImportPrefixes = listOf(
    "dev.turboism.persistence.",
    "dev.turboism.sdk.",
    "java.io.IOException",
    "java.nio.ByteBuffer",
    "java.nio.charset.",
    "java.util."
)

private val persistenceCoreForbiddenBinaryPrefixes = listOf(
    "com/live2d/",
    "java/awt/",
    "java/lang/reflect/",
    "java/net/",
    "java/nio/channels/",
    "java/nio/file/",
    "java/util/concurrent/",
    "javax/imageio/",
    "javax/swing/"
)

private val persistenceCoreAllowedDevTurboismRoots = setOf("persistence", "sdk")

private fun checkPersistenceCoreSources(root: Project, state: BoundaryState) {
    val sourceRoot = root.file("core/persistence/src/main/java")
    if (!sourceRoot.isDirectory) {
        state.reject(
            "Persistence-core production sources are missing at " +
                sourceRoot.relativeTo(root.projectDir)
        )
        return
    }
    sourceRoot.walkTopDown().filter { it.isFile && it.extension == "java" }.forEach { file ->
        val display = file.relativeTo(root.projectDir)
        file.readLines().forEachIndexed { index, line ->
            val trimmed = line.trim()
            if (!trimmed.startsWith("import ")) {
                return@forEachIndexed
            }
            val imported = trimmed
                .removePrefix("import ")
                .removePrefix("static ")
                .removeSuffix(";")
                .trim()
            if (persistenceCoreAllowedImportPrefixes.none { imported.startsWith(it) }) {
                state.reject(
                    "Persistence-core import rejected in $display:${index + 1}: " +
                        "the pure kernel must not name '$imported'"
                )
            }
        }
    }
}

private fun checkPersistenceCoreBytecode(root: Project, state: BoundaryState) {
    val classesDir = root.project(":persistence-core")
        .layout.buildDirectory.dir("classes/java/main").get().asFile
    if (!classesDir.isDirectory) {
        state.reject(
            "Persistence-core has no compiled classes at " + classesDir.relativeTo(root.projectDir)
        )
        return
    }
    classesDir.walkTopDown().filter { it.isFile && it.extension == "class" }.forEach { file ->
        val display = file.relativeTo(root.projectDir)
        val bytes = file.readBytes()
        persistenceCoreForbiddenBinaryPrefixes.forEach { prefix ->
            if (PluginClassReferences.indexOfNeedle(bytes, prefix.toByteArray(Charsets.UTF_8), 0) >= 0) {
                state.reject(
                    "Persistence-core class $display references forbidden $prefix"
                )
            }
        }
        val binaryNeedle = "dev/turboism/".toByteArray(Charsets.UTF_8)
        var offset = PluginClassReferences.indexOfNeedle(bytes, binaryNeedle, 0)
        while (offset >= 0) {
            var end = offset + binaryNeedle.size
            while (end < bytes.size && PluginClassReferences.isBinaryNameByte(bytes[end])) {
                end++
            }
            val segment = bytes.decodeToString(offset + binaryNeedle.size, end)
            if (segment.isNotEmpty() && segment !in persistenceCoreAllowedDevTurboismRoots) {
                state.reject(
                    "Persistence-core class $display references forbidden package dev/turboism/$segment"
                )
            }
            offset = PluginClassReferences.indexOfNeedle(bytes, binaryNeedle, offset + binaryNeedle.size)
        }
        val dottedNeedle = "dev.turboism.".toByteArray(Charsets.UTF_8)
        offset = PluginClassReferences.indexOfNeedle(bytes, dottedNeedle, 0)
        while (offset >= 0) {
            var end = offset + dottedNeedle.size
            while (end < bytes.size && PluginClassReferences.isBinaryNameByte(bytes[end])) {
                end++
            }
            val segment = bytes.decodeToString(offset + dottedNeedle.size, end)
            if (segment.isNotEmpty() && segment !in persistenceCoreAllowedDevTurboismRoots) {
                state.reject(
                    "Persistence-core class $display references forbidden package dev.turboism.$segment"
                )
            }
            offset = PluginClassReferences.indexOfNeedle(bytes, dottedNeedle, offset + dottedNeedle.size)
        }
        // java.io is admitted only as the checked parse-failure type, never as file IO.
        val ioNeedle = "java/io/".toByteArray(Charsets.UTF_8)
        offset = PluginClassReferences.indexOfNeedle(bytes, ioNeedle, 0)
        while (offset >= 0) {
            var end = offset + ioNeedle.size
            while (end < bytes.size && PluginClassReferences.isBinaryNameByte(bytes[end])) {
                end++
            }
            val segment = bytes.decodeToString(offset + ioNeedle.size, end)
            if (segment.isNotEmpty() && segment != "IOException") {
                state.reject(
                    "Persistence-core class $display references forbidden java.io.$segment"
                )
            }
            offset = PluginClassReferences.indexOfNeedle(bytes, ioNeedle, offset + ioNeedle.size)
        }
    }
}

/*
 * :compatibility-policy owns the host-independent Cubism admission policy. Its sources may
 * name only its own package, frozen SDK annotation types, and the JDK value/collection
 * types the exact decision needs; compiled bytecode is checked too, because an import-only
 * rule cannot see a fully qualified reference. The allowlist is a prefix list rather than
 * a ban: a new JDK package is a reviewed contract change, not an accident.
 */
private val compatibilityPolicyAllowedImportPrefixes = listOf(
    "dev.turboism.compatibility.",
    "dev.turboism.sdk.",
    "java.math.BigInteger",
    "java.util."
)

private val compatibilityPolicyForbiddenBinaryPrefixes = listOf(
    "com/live2d/",
    "java/awt/",
    "java/io/",
    "java/lang/ClassLoader",
    "java/lang/reflect/",
    "java/net/",
    "java/nio/",
    "java/security/",
    "java/util/concurrent/",
    "javax/",
    "org/objectweb/asm/"
)

private val compatibilityPolicyAllowedDevTurboismRoots = setOf("compatibility", "sdk")

private fun checkCompatibilityPolicySources(root: Project, state: BoundaryState) {
    val sourceRoot = root.file("core/compatibility-policy/src/main/java")
    if (!sourceRoot.isDirectory) {
        state.reject(
            "Compatibility-policy production sources are missing at " +
                sourceRoot.relativeTo(root.projectDir)
        )
        return
    }
    sourceRoot.walkTopDown().filter { it.isFile && it.extension == "java" }.forEach { file ->
        val display = file.relativeTo(root.projectDir)
        file.readLines().forEachIndexed { index, line ->
            val trimmed = line.trim()
            if (!trimmed.startsWith("import ")) {
                return@forEachIndexed
            }
            val imported = trimmed
                .removePrefix("import ")
                .removePrefix("static ")
                .removeSuffix(";")
                .trim()
            if (compatibilityPolicyAllowedImportPrefixes.none { imported.startsWith(it) }) {
                state.reject(
                    "Compatibility-policy import rejected in $display:${index + 1}: " +
                        "the pure admission policy must not name '$imported'"
                )
            }
        }
    }
}

private fun checkCompatibilityPolicyBytecode(root: Project, state: BoundaryState) {
    val classesDir = root.project(":compatibility-policy")
        .layout.buildDirectory.dir("classes/java/main").get().asFile
    if (!classesDir.isDirectory) {
        state.reject(
            "Compatibility-policy has no compiled classes at " +
                classesDir.relativeTo(root.projectDir)
        )
        return
    }
    classesDir.walkTopDown().filter { it.isFile && it.extension == "class" }.forEach { file ->
        val display = file.relativeTo(root.projectDir)
        val bytes = file.readBytes()
        compatibilityPolicyForbiddenBinaryPrefixes.forEach { prefix ->
            if (PluginClassReferences.indexOfNeedle(bytes, prefix.toByteArray(Charsets.UTF_8), 0) >= 0) {
                state.reject(
                    "Compatibility-policy class $display references forbidden $prefix"
                )
            }
        }
        val binaryNeedle = "dev/turboism/".toByteArray(Charsets.UTF_8)
        var offset = PluginClassReferences.indexOfNeedle(bytes, binaryNeedle, 0)
        while (offset >= 0) {
            var end = offset + binaryNeedle.size
            while (end < bytes.size && PluginClassReferences.isBinaryNameByte(bytes[end])) {
                end++
            }
            val segment = bytes.decodeToString(offset + binaryNeedle.size, end)
            if (segment.isNotEmpty() && segment !in compatibilityPolicyAllowedDevTurboismRoots) {
                state.reject(
                    "Compatibility-policy class $display references forbidden package dev/turboism/$segment"
                )
            }
            offset = PluginClassReferences.indexOfNeedle(bytes, binaryNeedle, offset + binaryNeedle.size)
        }
        val dottedNeedle = "dev.turboism.".toByteArray(Charsets.UTF_8)
        offset = PluginClassReferences.indexOfNeedle(bytes, dottedNeedle, 0)
        while (offset >= 0) {
            var end = offset + dottedNeedle.size
            while (end < bytes.size && PluginClassReferences.isBinaryNameByte(bytes[end])) {
                end++
            }
            val segment = bytes.decodeToString(offset + dottedNeedle.size, end)
            if (segment.isNotEmpty() && segment !in compatibilityPolicyAllowedDevTurboismRoots) {
                state.reject(
                    "Compatibility-policy class $display references forbidden package dev.turboism.$segment"
                )
            }
            offset = PluginClassReferences.indexOfNeedle(bytes, dottedNeedle, offset + dottedNeedle.size)
        }
    }
}

/*
 * checkPluginBoundaries is the compiled-artifact half of the plugin boundary.
 * checkModuleBoundaries scans sources and declared dependency paths; this gate
 * scans the bytecode every plugin actually ships, so copied or generated
 * classes cannot carry runtime/internal/Cubism references past the source
 * rules. Plugins are external consumers: only the JDK, dev.turboism.sdk.*,
 * the plugin's own dev.turboism.plugin.* classes, and com.sun.* JDK surfaces
 * are admissible.
 */
private val pluginBoundaryAllowedRoots = setOf(
    "sdk",    // the public API module
    "plugin"  // the plugin's own dev.turboism.plugin.* classes
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
    val contractTaskNames: List<String> = emptyList(),
    val allowedBinaryRoots: Set<String> = pluginBoundaryAllowedRoots
)

private val pluginBoundaryExtras = mapOf(
    ":plugins:selection-brush" to PluginBoundaryExtras(
        forbiddenSourceTokens = listOf(
            "java.awt", "javax.swing", "java.lang.reflect", "dev.turboism.runtime",
            "dev.turboism.mapping", "VerifiedMemberResolver", "ClassLoader", "getDeclared"
        ),
        contractTaskNames = listOf("verifySelectionBrushJar")
    ),
    ":plugins:geometric-mesh-layout" to PluginBoundaryExtras(
        // The plugin owns its configuration dialog as a plain JDK Swing window (the same
        // plugin-owned-window exception TurboismWindowFactory documents), so AWT/Swing are
        // admitted here. Everything below that could reach runtime internals or the host
        // canvas stays forbidden: the canvas overlay itself is runtime-painted data.
        forbiddenSourceTokens = listOf(
            "java.lang.reflect", "dev.turboism.runtime", "dev.turboism.mapping",
            "VerifiedMemberResolver", "ClassLoader", "getDeclared"
        ),
        contractTaskNames = listOf("verifyGeometricMeshLayoutJar")
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
    VerificationStamps.apply(this)
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
 * :event-processor is annotationProcessor, and no other project or external dependency may
 * appear in any production configuration except [pluginExternalComponents]. An
 * implementation-scoped :sdk would embed the shared SDK identity into the plugin archive and
 * break the single-typeidentity contract the bootstrap layer depends on.
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
                } else if (externalComponentIdentity(dependency) !in admittedPluginExternalComponents(plugin)) {
                    state.reject(
                        "Plugin ${plugin.path} may not declare non-project dependency " +
                            "${dependencyIdentity(dependency)} in ${configuration.name}"
                    )
                }
            }
        }
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
    "persistence",    // :persistence-core typed-config kernel
    "compatibility",  // :compatibility-policy Cubism admission policy
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

private fun checkPluginBytecodeReferences(root: Project, plugin: Project, state: BoundaryState) {
    val classesDir = plugin.layout.buildDirectory.dir("classes/java/main").get().asFile
    if (!classesDir.isDirectory) {
        state.reject("Plugin ${plugin.path} has no compiled classes at ${classesDir.relativeTo(root.projectDir)}")
        return
    }
    val forbiddenDottedRoots = pluginBoundaryForbiddenDottedRoots(root)
    val allowedRoots = pluginBoundaryExtras[plugin.path]?.allowedBinaryRoots
        ?: pluginBoundaryAllowedRoots
    classesDir.walkTopDown()
        .filter { it.isFile && it.extension == "class" }
        .forEach { classFile ->
            PluginClassReferences.scan(
                classFile.relativeTo(root.projectDir).toString(),
                classFile.readBytes(),
                forbiddenDottedRoots,
                allowedRoots
            ).forEach(state::reject)
        }
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
 * the plugin manifest. The rules, the constant-pool reader and the message
 * vocabulary live in buildSrc (PluginPermissionAudit, tested over the same
 * fixtures); this script owns the walk, the manifest read, the logging and the
 * single failure. A rule hit accumulates; an unparseable class file aborts the
 * audit rather than contributing zero references to it.
 */

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
    VerificationStamps.apply(this)
}

private fun checkPluginPermissionAudit(root: Project, plugins: List<Project>) {
    val state = BoundaryState(root.logger)
    val vocabulary = PermissionVocabulary.read(root.projectDir.toPath())
    val unexported = PluginPermissionAudit.boundaryPermissions() - vocabulary.toSet()
    if (unexported.isNotEmpty()) {
        state.reject(
            "Plugin permission audit rules name ids the SDK vocabulary does not export: $unexported"
        )
    }
    plugins.forEach { plugin ->
        val descriptor = plugin.file("src/main/resources/META-INF/turboism/plugin.json")
        val declared = PluginPermissionAudit.declaredPermissionIds(descriptor.readText())
        val classesDir = plugin.layout.buildDirectory.dir("classes/java/main").get().asFile
        if (!classesDir.isDirectory) {
            state.reject("Plugin ${plugin.path} has no compiled classes at ${classesDir.relativeTo(root.projectDir)}")
            return@forEach
        }
        val waivers = pluginPermissionAuditWaivers[plugin.path].orEmpty()
        val classes = classesDir.walkTopDown()
            .filter { it.isFile && it.extension == "class" }
            .map { classFile ->
                PluginPermissionAudit.ClassFile(
                    classFile.relativeTo(root.projectDir).toString(),
                    classFile.readBytes()
                )
            }
            .toList()
        PluginPermissionAudit.audit(plugin.path, classes, declared, waivers).forEach(state::reject)
    }
    if (state.failed) {
        throw GradleException("Plugin permission audit failed.")
    }
    root.logger.lifecycle("Plugin permission audit passed for ${plugins.size} plugins.")
}
