import org.gradle.api.tasks.Exec

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
    description = "Rejects plugin-owned Thread, Executor, and Timer resources and synchronous host reads."
    workingDir(rootDir)
    inputs.files(
        fileTree("plugins") { include("*/src/main/java/**/*.java") },
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

val productionClasses = subprojects
    .filterNot { it.path == ":tests" }
    .map { "${it.path}:classes" }

val devCheck by tasks.registering {
    group = "verification"
    description = "Fast daily production compilation and permanent-boundary verification; no broad test suites."
    dependsOn(
        productionClasses,
        checkPackageLayout,
        checkModuleBoundariesSelfTest,
        "checkModuleBoundaries",
        "checkSdkV2ExactApiCompatibility",
        "validatePluginMeta"
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
        "checkDistributionProtocolContract",
        "checkPreviewBundleLayout",
        "previewAgentSmokeTest",
        "checkPreviewRuntimeReports",
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
        "checkSdkV2ExactApiCompatibility",
        "checkAsmSupplyChainAdmission",
        "checkMappingReviewWrapperArgs"
    )
}

tasks.named("check") {
    dependsOn(devCheck, "checkSdkV2ExactApiCompatibility")
}
