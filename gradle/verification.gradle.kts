import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.bundling.Jar

/*
 * Verification is deliberately layered by cost:
 *
 *   devCheck          daily production compilation and permanent boundaries
 *   focused tests     selected by the current SDD acceptance conditions
 *   checkIntegration  packaged/runtime and affected cross-module behavior
 *   checkRelease      supply-chain, API tooling, packaging, and release checks
 *   host validation   explicit exact-version Cubism runs; never a default gate
 *
 * Historical M1-M16/M13/M14 governance tasks are intentionally absent.
 */

tasks.register<Exec>("checkMappingReviewWrapperArgs") {
    group = "verification"
    description = "Verifies mapping-review wrapper argv transport and args-file hardening offline."
    workingDir(rootDir)
    commandLine("bash", "scripts/test/test_mapping_review_wrapper_args.sh")
}

tasks.register("checkOfficialPluginI18nCompleteness") {
    group = "verification"
    description = "Verifies baseline localization-key completeness for participating official plugins."
    dependsOn(":tests:officialPluginI18nCompletenessTest")
}

val checkAsyncHostReadStructuralBoundaries by tasks.registering(Exec::class) {
    group = "verification"
    description = "Rejects plugin-owned Thread, Executor, and Timer resources and synchronous host reads in the async-host consumer (ProjectInspectorPlugin)."
    workingDir(rootDir)
    inputs.files(
        "plugins/project-inspector/src/main/java/dev/turboism/plugin/projectinspector/ProjectInspectorPlugin.java",
        "scripts/test/test_async_host_read_foundation.py"
    )
    commandLine("python3", "scripts/test/test_async_host_read_foundation.py")
}

val checkAsyncHostReadFoundation by tasks.registering {
    group = "integration verification"
    description = "Verifies async host-read contracts, runtime behavior, consumer behavior, and structural boundaries."
    dependsOn(
        ":sdk:asyncHostReadContractTest",
        ":runtime:asyncHostReadFoundationTest",
        ":plugins:project-inspector:asyncHostReadConsumerTest",
        ":tests:asyncHostReadPreviewIntegrationTest",
        checkAsyncHostReadStructuralBoundaries
    )
}

val checkCubismCoreApiInventory by tasks.registering(Exec::class) {
    group = "integration verification"
    description = "Validates exact-artifact Cubism Core public API inventories without generated documentation gates."
    workingDir(rootDir)
    inputs.files(
        "scripts/cubism_core_api.py",
        "scripts/test/test_cubism_core_api_inventory.py",
        "cubism-ref/index.md",
        fileTree("cubism-ref/core-api/observed") { include("*.json") },
        "cubism-ref/mapping-packs/draft/cubism-5.2-core-model-read.json",
        "cubism-ref/mapping-packs/draft/cubism-5.3.02-core-model-read.json",
        "cubism-ref/profiles/draft/cubism-5.2.json",
        "cubism-ref/profiles/draft/cubism-5.3.02.json"
    )
    commandLine("python3", "scripts/test/test_cubism_core_api_inventory.py")
}

val checkCubismCoreMemberPolicy by tasks.registering(Exec::class) {
    group = "integration verification"
    description = "Classifies every observed Cubism Core public member and checks machine policy drift."
    workingDir(rootDir)
    inputs.files(
        "scripts/cubism_core_policy.py",
        "scripts/test/test_cubism_core_member_policy.py",
        "cubism-ref/core-api/policy/cubism-core-member-policy.json",
        fileTree("cubism-ref/core-api/observed") { include("*.json") }
    )
    commandLine("python3", "scripts/test/test_cubism_core_member_policy.py")
}

val checkCubismCoreSelectorPolicy by tasks.registering(Exec::class) {
    group = "integration verification"
    description = "Validates generated Cubism Core selector constants and profile coverage."
    workingDir(rootDir)
    inputs.files(
        "scripts/cubism_core_selector_policy.py",
        "scripts/test/test_cubism_core_selector_policy.py",
        "cubism-ref/core-api/policy/cubism-core-selector-policy.json",
        "cubism-ref/mapping-packs/draft/cubism-5.2-core-model-read.json",
        "cubism-ref/mapping-packs/draft/cubism-5.3.02-core-model-read.json"
    )
    commandLine("python3", "scripts/test/test_cubism_core_selector_policy.py")
}

val checkCodeQualitySelfTest by tasks.registering(Exec::class) {
    group = "verification"
    description = "Runs negative fixtures proving each code-quality rule fails closed."
    workingDir(rootDir)
    inputs.files(
        "scripts/test/check_code_quality.py",
        "scripts/test/test_check_code_quality.py"
    )
    commandLine("python3", "scripts/test/test_check_code_quality.py")
}

/*
 * Wired into devCheck as a ratchet: the digest, naming and asset rules are enforced absolutely,
 * and Javadoc is enforced as a non-increasing maximum so new undocumented public API is blocked
 * immediately while the existing backlog burns down.
 * -PturboismCodeQualityRules selects a subset; -PturboismCodeQualityStrict=true demands zero.
 */
tasks.register<Exec>("checkCodeQuality") {
    group = "verification"
    description =
        "Rejects new undocumented public API, duplicated reviewed host digests, version-encoding " +
            "type names, and retired governance tokens in machine assets."
    dependsOn(checkCodeQualitySelfTest)
    workingDir(rootDir)
    inputs.files("scripts/test/check_code_quality.py")
    inputs.files(
        fileTree("sdk/src/main/java") { include("**/*.java") },
        fileTree("runtime/src/main/java") { include("**/*.java") },
        fileTree("bootstrap/src/main/java") { include("**/*.java") },
        fileTree("plugins") { include("**/src/main/java/**/*.java") },
        fileTree("cubism-ref") { include("**/*.json") }
    )
    val selectedRules = providers.gradleProperty("turboismCodeQualityRules")
    val strict = providers.gradleProperty("turboismCodeQualityStrict")
    doFirst {
        val rules = selectedRules.getOrElse("javadoc,digests,naming,assets")
        val command = mutableListOf(
            "python3", "scripts/test/check_code_quality.py", rootDir.absolutePath,
            "--rules", rules
        )
        if (strict.getOrElse("false") != "true") {
            command += "--ratchet"
        }
        commandLine(command)
    }
}

val checkPackageLayout by tasks.registering(Exec::class) {
    group = "verification"
    description = "Rejects deprecated SDK/runtime packages and package-only production Java shells."
    workingDir(rootDir)
    inputs.file("scripts/test/check_package_layout.py")
    inputs.files(
        fileTree("sdk/src/main/java") { include("**/*.java") },
        fileTree("runtime/src/main/java") { include("**/*.java") }
    )
    commandLine("python3", "scripts/test/check_package_layout.py", rootDir.absolutePath)
}

val checkModuleBoundariesSelfTest by tasks.registering(Exec::class) {
    group = "verification"
    description = "Runs negative fixtures for fail-closed module-boundary enforcement."
    workingDir(rootDir)
    inputs.files("gradle/module-boundaries.gradle.kts", "scripts/test/test_module_boundaries.py")
    commandLine("python3", "scripts/test/test_module_boundaries.py")
}

val checkDuplicateJavaImports by tasks.registering {
    group = "verification"
    description = "Rejects duplicate Java import declarations within one source file."
    doLast {
        val importPattern = Regex("""^\s*import\s+(static\s+)?([\w.${'$'}*]+)\s*;\s*${'$'}""")
        val duplicates = mutableListOf<String>()
        fileTree(rootDir) {
            include("**/src/**/*.java")
            exclude(".worktrees/**")
        }.files
            .sortedBy { it.relativeTo(rootDir).invariantSeparatorsPath }
            .forEach { source ->
                val relativePath = source.relativeTo(rootDir).invariantSeparatorsPath
                val firstImportLines = mutableMapOf<String, Int>()
                var importsOpen = true
                var blockComment = false
                source.useLines { lines ->
                    lines.forEachIndexed { index, line ->
                        if (!importsOpen) {
                            return@forEachIndexed
                        }
                        val trimmed = line.trim()
                        if (blockComment) {
                            if (trimmed.contains("*/")) {
                                blockComment = false
                            }
                            return@forEachIndexed
                        }
                        if (trimmed.isEmpty() || trimmed.startsWith("//")) {
                            return@forEachIndexed
                        }
                        if (trimmed.startsWith("/*")) {
                            blockComment = !trimmed.contains("*/")
                            return@forEachIndexed
                        }
                        val match = importPattern.matchEntire(line)
                        if (match == null) {
                            if (!trimmed.startsWith("package ") && !trimmed.startsWith("@")) {
                                importsOpen = false
                            }
                            return@forEachIndexed
                        }
                        val declaration = (match.groupValues[1] + match.groupValues[2]).trim()
                        val lineNumber = index + 1
                        val firstLine = firstImportLines.putIfAbsent(declaration, lineNumber)
                        if (firstLine != null) {
                            duplicates += "$relativePath:$lineNumber repeats import $declaration; (first at line $firstLine)"
                        }
                    }
                }
            }
        if (duplicates.isNotEmpty()) {
            throw GradleException(duplicates.sorted().joinToString("\n", prefix = "Duplicate Java import declarations:\n"))
        }
    }
}

val productionClasses = subprojects
    .filterNot { it.path == ":tests" }
    .map { "${it.path}:classes" }

val devCheck by tasks.registering {
    group = "verification"
    description = "Fast daily production compilation and permanent-boundary verification; no broad test suites."
    dependsOn(
        productionClasses,
        checkDuplicateJavaImports,
        checkPackageLayout,
        "checkModuleBoundaries",
        "checkCodeQuality",
        "checkSdkV4ExactApiCompatibility",
        "checkSdkV4TierCompatibility",
        "validatePluginMeta",
        "checkOfficialPluginI18nCompleteness"
    )
}

val resolvedHostValidationWorktreeId = rootProject.extra["turboismResolvedWorktreeId"] as String

val packageParameterHostValidation by tasks.registering(Exec::class) {
    group = "host verification"
    description = "Packages the test-only SDK probe and parameter host-validation bundle."
    dependsOn("previewBundle", ":plugins:parameter:jar", ":tests:testClasses")
    workingDir(rootDir)
    environment("TURBOISM_WORKTREE_ID", resolvedHostValidationWorktreeId)
    commandLine("bash", "scripts/preview/package-windows-parameter-validation.sh")
}

val packageWorkspaceHostValidation by tasks.registering(Exec::class) {
    group = "host verification"
    description = "Packages the test-only SDK probe and workspace host-validation bundle."
    dependsOn("previewBundle", ":tests:testClasses")
    workingDir(rootDir)
    environment("TURBOISM_WORKTREE_ID", resolvedHostValidationWorktreeId)
    commandLine("bash", "scripts/preview/package-windows-workspace-validation.sh")
}

val packageThemeHostValidation by tasks.registering(Exec::class) {
    group = "host verification"
    description = "Packages the UI theme plugin for exact-host validation."
    dependsOn("previewBundle", ":plugins:ui-theme:jar")
    workingDir(rootDir)
    environment("TURBOISM_WORKTREE_ID", resolvedHostValidationWorktreeId)
    commandLine("bash", "scripts/preview/package-windows-theme-validation.sh")
}

val buildThemeHostProbe by tasks.registering(Exec::class) {
    group = "host verification"
    description = "Builds the test-only SDK theme host exerciser."
    workingDir(rootDir)
    commandLine("bash", "validation/theme-host-probe/build.sh")
}

val buildStatusBarHostProbe by tasks.registering(Exec::class) {
    group = "host verification"
    description = "Builds the test-only SDK status-bar host exerciser."
    dependsOn(":sdk:jar")
    workingDir(rootDir)
    commandLine("bash", "validation/status-bar-host-probe/build.sh")
}

tasks.register<Exec>("validateStatusBarHost5302") {
    group = "host verification"
    description = "Runs the automated exact-host Cubism 5.3.02 native status-bar matrix."
    dependsOn("previewBundle", ":sdk:jar", buildStatusBarHostProbe)
    workingDir(rootDir)
    environment("TURBOISM_WORKTREE_ID", resolvedHostValidationWorktreeId)
    commandLine("bash", "scripts/preview/run-status-bar-host-validation.sh", "5302")
}

val buildFpsHostProbe by tasks.registering(Exec::class) {
    group = "host verification"
    description = "Builds the test-only SDK FPS counting host exerciser."
    dependsOn(":sdk:jar")
    workingDir(rootDir)
    commandLine("bash", "validation/fps-host-probe/build.sh")
}

fun registerFpsHostValidation(name: String, version: String) {
    tasks.register<Exec>(name) {
        group = "host verification"
        description = "Runs the automated exact-host Cubism $version FPS counting session."
        dependsOn("previewBundle", ":sdk:jar", buildFpsHostProbe)
        workingDir(rootDir)
        environment("TURBOISM_WORKTREE_ID", resolvedHostValidationWorktreeId)
        commandLine("bash", "scripts/preview/run-fps-host-validation.sh", version)
    }
}

registerFpsHostValidation("validateFpsHost5203", "5203")
registerFpsHostValidation("validateFpsHost5302", "5302")

val buildSeparateSavePathHostProbe by tasks.registering(Exec::class) {
    group = "host verification"
    description = "Builds the test-only SDK separate-save-path host exerciser."
    dependsOn(":sdk:jar")
    workingDir(rootDir)
    commandLine("bash", "validation/separate-save-path-host-probe/build.sh")
}

fun registerSeparateSavePathHostValidation(name: String, version: String, displayVersion: String) {
    tasks.register<Exec>(name) {
        group = "host verification"
        description = "Runs the automated exact-host Cubism $displayVersion separate-save-path matrix."
        dependsOn("previewBundle", ":sdk:jar", buildSeparateSavePathHostProbe)
        workingDir(rootDir)
        environment("TURBOISM_WORKTREE_ID", resolvedHostValidationWorktreeId)
        commandLine("bash", "scripts/preview/run-separate-save-path-host-validation.sh", version)
    }
}

registerSeparateSavePathHostValidation("validateSeparateSavePathHost5302", "5302", "5.3.02")
registerSeparateSavePathHostValidation("validateSeparateSavePathHost5203", "5203", "5.2.03")

val packageClipMaskViewerHostValidation by tasks.registering(Exec::class) {
    group = "host verification"
    description = "Packages the clipmask-viewer plugin and probe exerciser for exact-host validation."
    dependsOn("previewBundle", ":plugins:clipmask-viewer:jar")
    workingDir(rootDir)
    environment("TURBOISM_WORKTREE_ID", resolvedHostValidationWorktreeId)
    commandLine("bash", "scripts/preview/package-windows-clipmask-viewer-validation.sh")
}

val buildClipMaskViewerHostProbe by tasks.registering(Exec::class) {
    group = "host verification"
    description = "Builds the test-only SDK clipmask-viewer host exerciser."
    dependsOn(":sdk:jar")
    workingDir(rootDir)
    commandLine("bash", "validation/clipmask-viewer-host-probe/build.sh")
}

fun registerClipMaskViewerHostValidation(name: String, version: String, displayVersion: String) {
    tasks.register<Exec>(name) {
        group = "host verification"
        description = "Runs the automated exact-host Cubism $displayVersion clip-mask viewer matrix."
        dependsOn(packageClipMaskViewerHostValidation, buildClipMaskViewerHostProbe)
        workingDir(rootDir)
        environment("TURBOISM_WORKTREE_ID", resolvedHostValidationWorktreeId)
        commandLine("bash", "scripts/preview/run-clipmask-viewer-host-validation.sh", version)
    }
}

registerClipMaskViewerHostValidation("validateClipMaskViewerHost5302", "5302", "5.3.02")
registerClipMaskViewerHostValidation("validateClipMaskViewerHost5203", "5203", "5.2.03")
fun registerThemeHostValidation(name: String, version: String, displayVersion: String) {
    tasks.register<Exec>(name) {
        group = "host verification"
        description = "Runs the automated exact-host Cubism $displayVersion theme matrix."
        dependsOn(packageThemeHostValidation, buildThemeHostProbe)
        workingDir(rootDir)
        environment("TURBOISM_WORKTREE_ID", resolvedHostValidationWorktreeId)
        commandLine("bash", "scripts/preview/run-theme-host-validation.sh", version)
    }
}

val packagePsdClipMaskHostValidation by tasks.registering(Exec::class) {
    group = "host verification"
    description = "Packages the PSD clip-mask import plugin and its test-only SDK probe for exact-host validation."
    dependsOn("previewBundle", ":plugins:psd-clip-mask-import:jar", ":tests:testClasses")
    workingDir(rootDir)
    environment("TURBOISM_WORKTREE_ID", resolvedHostValidationWorktreeId)
    // Bind the bundle to the exact artifact of the current Gradle jar task;
    // no directory scanning or version heuristics may substitute for it.
    doFirst {
        environment(
            "PSD_CLIP_MASK_PLUGIN_JAR",
            project(":plugins:psd-clip-mask-import").tasks.named<Jar>("jar")
                .flatMap { it.archiveFile }
                .map { it.asFile.absolutePath }
                .get()
        )
    }
    commandLine("bash", "scripts/preview/package-windows-psd-clip-mask-validation.sh")
}

val checkPsdClipMaskHostValidationBundle by tasks.registering(Exec::class) {
    group = "verification"
    description = "Asserts the PSD clip-mask host-validation bundle jars carry descriptor-declared i18n catalogs."
    dependsOn(packagePsdClipMaskHostValidation)
    workingDir(rootDir)
    environment("TURBOISM_WORKTREE_ID", resolvedHostValidationWorktreeId)
    commandLine("bash", "scripts/preview/check-psd-clip-mask-validation-bundle.sh")
}

tasks.register<Exec>("validatePsdClipMaskHost5302") {
    group = "host verification"
    description = "Runs the automated exact-host Cubism 5.3.02 PSD clip-mask read/write/Undo/Redo matrix."
    dependsOn(packagePsdClipMaskHostValidation)
    workingDir(rootDir)
    environment("TURBOISM_WORKTREE_ID", resolvedHostValidationWorktreeId)
    val mode = providers.gradleProperty("turboismPsdClipMaskValidationMode").orElse("matrix")
    doFirst {
        commandLine(
            "bash",
            "scripts/preview/run-psd-clip-mask-host-validation.sh",
            "5302",
            mode.get()
        )
    }
}

tasks.register<Exec>("validatePsdClipMaskHost5203") {
    group = "host verification"
    description = "Runs the automated exact-host Cubism 5.2.03 PSD clip-mask read/write/Undo/Redo matrix."
    dependsOn(packagePsdClipMaskHostValidation)
    workingDir(rootDir)
    environment("TURBOISM_WORKTREE_ID", resolvedHostValidationWorktreeId)
    val mode = providers.gradleProperty("turboismPsdClipMaskValidationMode").orElse("matrix")
    doFirst {
        commandLine(
            "bash",
            "scripts/preview/run-psd-clip-mask-host-validation.sh",
            "5203",
            mode.get()
        )
    }
}

fun registerParameterHostValidation(name: String, version: String, displayVersion: String) {
    tasks.register<Exec>(name) {
        group = "host verification"
        description = "Runs the automated exact-host Cubism $displayVersion parameter/editor matrix."
        dependsOn(packageParameterHostValidation)
        workingDir(rootDir)
        environment("TURBOISM_WORKTREE_ID", resolvedHostValidationWorktreeId)
        val mode = providers.gradleProperty("turboismHostValidationMode").orElse("matrix")
        doFirst {
            commandLine(
                "bash",
                "scripts/preview/run-parameter-host-validation.sh",
                version,
                mode.get()
            )
        }
    }
}

fun registerWorkspaceHostValidation(name: String, version: String, displayVersion: String) {
    tasks.register<Exec>(name) {
        group = "host verification"
        description = "Runs the automated exact-host Cubism $displayVersion workspace matrix."
        dependsOn(packageWorkspaceHostValidation)
        workingDir(rootDir)
        environment("TURBOISM_WORKTREE_ID", resolvedHostValidationWorktreeId)
        commandLine("bash", "scripts/preview/run-workspace-host-validation.sh", version)
    }
}

registerThemeHostValidation("validateThemeHost5302", "5302", "5.3.02")
registerThemeHostValidation("validateThemeHost5203", "5203", "5.2.03")
registerParameterHostValidation("validateParameterHost5302", "5302", "5.3.02")
registerParameterHostValidation("validateParameterHost5203", "5203", "5.2.03")
registerWorkspaceHostValidation("validateWorkspaceHost5302", "5302", "5.3.02")
registerWorkspaceHostValidation("validateWorkspaceHost5203", "5203", "5.2.03")

tasks.register("checkIntegration") {
    group = "verification"
    description = "Runs packaged runtime, plugin, preview-agent, and affected cross-module integration verification."
    dependsOn(
        devCheck,
        checkAsyncHostReadFoundation,
        checkCubismCoreApiInventory,
        checkCubismCoreMemberPolicy,
        checkCubismCoreSelectorPolicy,
        ":runtime:corePublicApiProviderTest",
        "checkPluginInspectionRuntime",
        "verifyFirstPartyPluginMetadata",
        "checkDistributionProtocolContract",
        "checkPreviewBundleLayout",
        "checkPsdClipMaskHostValidationBundle",
        "previewBootstrapBridgeTest",
        ":tests:previewPluginRuntimeTest"
    )
}

tasks.register("checkRelease") {
    group = "verification"
    description = "Runs integration, supply-chain, API-tooling, and release-oriented verification."
    dependsOn(
        "checkIntegration",
        "checkSdkApiBaselineTool",
        "checkSdkApiReferenceBuilder",
        "checkModuleBoundariesSelfTest",
        "checkSdkV2ExactApiCompatibility",
        "checkSdkV3ExactApiCompatibility",
        "checkSdkV3TierCompatibility",
        "checkAsmSupplyChainAdmission",
        "checkMappingReviewWrapperArgs"
    )
}

tasks.named("check") {
    dependsOn(devCheck)
}
