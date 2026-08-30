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

tasks.register<Exec>("checkCubismHostValidationArguments") {
    group = "verification"
    description = "Verifies Cubism host-validation path validation and remote argument transport offline."
    workingDir(rootDir)
    commandLine("bash", "scripts/test/test_cubism_host_validation_arguments.sh")
}

tasks.register<Exec>("checkFxValidationBrokerArguments") {
    group = "verification"
    description = "Verifies bounded validation-only fx broker accept lifetimes offline."
    workingDir(rootDir)
    commandLine("python3", "scripts/test/test_fx_validation_broker_arguments.py")
}

tasks.register<Exec>("checkGraalScriptHostValidationDryRun") {
    group = "verification"
    description = "Verifies the Graal script host-validation wrapper uses configurable Java and packaged wildcard classpath."
    workingDir(rootDir)
    commandLine("bash", "scripts/test/test_graal_script_host_validation_dry_run.sh")
}

tasks.register<Exec>("checkGraalPreviewLauncherContract") {
    group = "verification"
    description = "Verifies ProbeOnly validates a configured Graal library closure without claiming host readiness."
    workingDir(rootDir)
    commandLine("bash", "scripts/test/test_graal_preview_launcher_contract.sh")
}

tasks.register("checkGraalScriptHostValidation") {
    group = "verification"
    description = "Runs offline Graal script host-validation contracts."
    dependsOn(
        "checkCubismHostValidationArguments",
        "checkFxValidationBrokerArguments",
        "checkGraalScriptHostValidationDryRun",
        "checkGraalPreviewLauncherContract"
    )
}

tasks.register("checkOfficialPluginI18nCompleteness") {
    group = "verification"
    description = "Verifies baseline localization-key completeness for participating official plugins."
    dependsOn(":testing:integration-tests:officialPluginI18nCompletenessTest")
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
        ":testing:integration-tests:asyncHostReadPreviewIntegrationTest",
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
        "compatibility/cubism/index.md",
        fileTree("compatibility/cubism/core-api/observed") { include("*.json") },
        "compatibility/cubism/mapping-packs/draft/cubism-5.2.03-core-model-read.json",
        "compatibility/cubism/mapping-packs/draft/cubism-5.3.02-core-model-read.json",
        "compatibility/cubism/profiles/draft/cubism-5.2.03.json",
        "compatibility/cubism/profiles/draft/cubism-5.3.02.json"
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
        "compatibility/cubism/core-api/policy/cubism-core-member-policy.json",
        fileTree("compatibility/cubism/core-api/observed") { include("*.json") }
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
        "compatibility/cubism/core-api/policy/cubism-core-selector-policy.json",
        "compatibility/cubism/mapping-packs/draft/cubism-5.2.03-core-model-read.json",
        "compatibility/cubism/mapping-packs/draft/cubism-5.3.02-core-model-read.json"
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
    workingDir(rootDir)
    inputs.files("scripts/test/check_code_quality.py")
    inputs.files(
        fileTree("sdk/src/main/java") { include("**/*.java") },
        fileTree("runtime/src/main/java") { include("**/*.java") },
        fileTree("bootstrap/src/main/java") { include("**/*.java") },
        fileTree("plugins") { include("**/src/main/java/**/*.java") },
        fileTree("compatibility/cubism") { include("**/*.json") }
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

/*
 * Editor-model is the one capability family whose aliases are inline literals rather than
 * constants, so it has no Verified*HostOperations.methodAliasesUsed() for a test to compare
 * against. The repository test used a hand-maintained list instead, which drifted until it
 * matched neither the implementation nor the record. This derives production use and the older
 * exact-use records, then admits only the fixed invoked subset of the broader 5.3.03 static record.
 */
val checkEditorModelAliases by tasks.registering(Exec::class) {
    group = "verification"
    description =
        "Rejects Editor-model selector aliases the implementation invokes without a reviewed " +
            "record, and holds the unused-alias count non-increasing."
    workingDir(rootDir)
    inputs.files(
        "scripts/test/check_editor_model_aliases.py",
        "scripts/test/test_editor_model_aliases.py"
    )
    inputs.files(
        fileTree("runtime/src/main/java/dev/turboism/adapter/cubism") { include("**/*.java") },
        "compatibility/cubism/verification/cubism-5.2.03-editor-model.json",
        "compatibility/cubism/verification/cubism-5.3.02-editor-model.json",
        "compatibility/cubism/verification/cubism-5.3.03-editor-model.json"
    )
    commandLine("python3", "scripts/test/check_editor_model_aliases.py", rootDir.absolutePath)
}

val checkEditorModelAliasesSelfTest by tasks.registering(Exec::class) {
    group = "verification"
    description = "Runs fail-closed regression fixtures for Editor-model alias admission."
    workingDir(rootDir)
    inputs.files(
        "scripts/test/check_editor_model_aliases.py",
        "scripts/test/test_editor_model_aliases.py"
    )
    inputs.files(
        "compatibility/cubism/verification/cubism-5.2.03-editor-model.json",
        "compatibility/cubism/verification/cubism-5.3.02-editor-model.json",
        "compatibility/cubism/verification/cubism-5.3.03-editor-model.json"
    )
    commandLine(
        "python3", "-m", "unittest",
        "scripts.test.test_editor_model_aliases",
        "-v"
    )
}

checkEditorModelAliases.configure {
    dependsOn(checkEditorModelAliasesSelfTest)
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

val checkOfficialPluginReadmes by tasks.registering(Exec::class) {
    group = "verification"
    description = "Verifies every first-party plugin has a README matching its descriptor and delivery role."
    workingDir(rootDir)
    inputs.file("packaging/release-plugins.txt")
    inputs.file("scripts/test/check_official_plugin_readmes.py")
    inputs.files(fileTree("plugins") {
        include("*/README.md")
        include("*/README_zh.md")
        include("*/README_ja.md")
        include("*/src/main/resources/META-INF/turboism/plugin.json")
    })
    commandLine("python3", "scripts/test/check_official_plugin_readmes.py")
}

val pluginEventReferenceReport = layout.buildDirectory.file(
    "reports/plugin-events/plugin-public-events.md"
)

val generatePluginEventReference by tasks.registering(Exec::class) {
    group = "documentation"
    description = "Generates the first-party plugin public-event report under build/reports."
    workingDir(rootDir)
    inputs.files(
        "scripts/plugin_event_metadata.py",
        "scripts/test/generate_plugin_event_reference.py",
        fileTree("plugins") {
            include("*/src/main/resources/META-INF/turboism/plugin.json")
        }
    )
    outputs.file(pluginEventReferenceReport)
    commandLine(
        "python3",
        "scripts/test/generate_plugin_event_reference.py",
        "--root",
        rootDir.absolutePath,
        "--output",
        pluginEventReferenceReport.get().asFile.absolutePath
    )
}

val checkPluginEventReference by tasks.registering {
    group = "verification"
    description = "Proves the first-party plugin public-event report generator is deterministic."
    dependsOn(generatePluginEventReference)
    val verificationReport = layout.buildDirectory.file(
        "tmp/plugin-event-reference-check/plugin-public-events.md"
    )
    inputs.files(
        "scripts/plugin_event_metadata.py",
        "scripts/test/generate_plugin_event_reference.py",
        fileTree("plugins") {
            include("*/src/main/resources/META-INF/turboism/plugin.json")
        }
    )
    inputs.file(pluginEventReferenceReport)
    outputs.file(verificationReport)
    doLast {
        val report = verificationReport.get().asFile
        report.parentFile.mkdirs()
        exec {
            workingDir(rootDir)
            commandLine(
                "python3",
                "scripts/test/generate_plugin_event_reference.py",
                "--root",
                rootDir.absolutePath,
                "--output",
                report.absolutePath
            )
        }
        val generated = pluginEventReferenceReport.get().asFile
        if (!generated.readBytes().contentEquals(report.readBytes())) {
            throw GradleException("plugin event reference generation is not deterministic")
        }
    }
}

val checkMarketReleaseMetadata by tasks.registering(Exec::class) {
    group = "release verification"
    description = "Verifies schema-v4 plugin event metadata in market release sidecars."
    workingDir(rootDir)
    inputs.files(
        "scripts/plugin_event_metadata.py",
        "scripts/release/prepare-market-release.py",
        "scripts/test/test_prepare_market_release.py",
        "packaging/market-plugins.json",
        ".github/workflows/publish-selected-plugins.yml"
    )
    commandLine("python3", "scripts/test/test_prepare_market_release.py")
}

val productionClasses = subprojects
    .filterNot { it.path == ":testing:integration-tests" }
    .map { "${it.path}:classes" }

val checkRemoteHygieneSelfTest by tasks.registering(Exec::class) {
    group = "verification"
    description = "Runs fail-closed fixtures for tracked paths, local configuration, and secret-value hygiene rules."
    workingDir(rootDir)
    inputs.files(
        "scripts/check_remote_hygiene.py",
        "scripts/test/test_check_remote_hygiene.py"
    )
    commandLine("python3", "scripts/test/test_check_remote_hygiene.py")
}

val checkRepositoryHygiene by tasks.registering(Exec::class) {
    group = "verification"
    description = "Rejects tracked secrets, developer-machine paths, and local-only configuration before daily development checks pass."
    dependsOn(checkRemoteHygieneSelfTest)
    workingDir(rootDir)
    commandLine("python3", "scripts/check_remote_hygiene.py", "--worktree")
}

val devCheck by tasks.registering {
    group = "verification"
    description = "Fast production compilation and permanent structural boundaries for an implementation slice."
    dependsOn(
        productionClasses,
        "checkFxValidationBrokerArguments",
        checkDuplicateJavaImports,
        checkPackageLayout,
        "checkModuleBoundaries",
        "checkCodeQuality",
        checkRepositoryHygiene,
        checkEditorModelAliases,
        "validatePluginMeta"
    )
}

val resolvedHostValidationWorktreeId = rootProject.extra["turboismResolvedWorktreeId"] as String

val packageParameterHostValidation by tasks.registering(Exec::class) {
    group = "host verification"
    description = "Packages the test-only SDK probe and parameter host-validation bundle."
    dependsOn("previewBundle", ":plugins:parameter:jar", ":testing:integration-tests:testClasses")
    workingDir(rootDir)
    environment("TURBOISM_WORKTREE_ID", resolvedHostValidationWorktreeId)
    commandLine("bash", "scripts/preview/package-windows-parameter-validation.sh")
}

val packageWorkspaceHostValidation by tasks.registering(Exec::class) {
    group = "host verification"
    description = "Packages the test-only SDK probe and workspace host-validation bundle."
    dependsOn("previewBundle", ":testing:integration-tests:testClasses")
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
    dependsOn("previewBundle", ":plugins:psd-clip-mask-import:jar", ":testing:integration-tests:testClasses")
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
        "verifyFirstPartyPluginReadmes",
        "checkDistributionProtocolContract",
        "checkPreviewBundleLayout",
        "checkPsdClipMaskHostValidationBundle",
        "previewBootstrapBridgeTest",
        ":testing:integration-tests:previewPluginRuntimeTest"
    )
}

val ordinaryTestTasks = subprojects
    .filter { it.tasks.findByName("test") != null }
    .map { "${it.path}:test" }

val checkCompletedCommit by tasks.registering {
    group = "verification"
    description = "Runs the complete automated repository gate for a coherent completed change."
    dependsOn(
        "checkIntegration",
        ordinaryTestTasks,
        ":sdk:javadoc",
        "checkOfficialPluginI18nCompleteness",
        checkOfficialPluginReadmes,
        "checkSdkApiBaselineTool",
        "checkModuleBoundariesSelfTest",
        checkCodeQualitySelfTest,
        checkRemoteHygieneSelfTest,
        checkPluginEventReference,
        "generateSdkApiReport"
    )
}

val checkReleaseTooling by tasks.registering(Exec::class) {
    group = "release verification"
    description = "Verifies changelog extraction, release payloads, and deterministic release planning."
    workingDir(rootDir)
    inputs.files(
        "scripts/release/extract-release-notes.py",
        "scripts/release/verify-release.py",
        "scripts/release/audit-v0.42.0.py",
        "scripts/release/build-updates-manifests.py",
        "scripts/release/turboism-release.py",
        "scripts/release/verify-github-assets.py",
        "scripts/release/verify-plugin-publication.py",
        fileTree("scripts/release/turboism_release") { include("*.py") },
        "scripts/test/test_release_tooling.py",
        "scripts/test/test_release_orchestrator.py",
        "CHANGELOG.md",
        ".github/workflows/release.yml"
    )
    commandLine(
        "python3", "-m", "unittest", "-v",
        "scripts/test/test_release_tooling.py",
        "scripts/test/test_release_orchestrator.py"
    )
}

tasks.register("checkRelease") {
    group = "verification"
    description = "Runs completed-commit verification plus supply-chain and historical release audits."
    dependsOn(
        checkCompletedCommit,
        checkReleaseTooling,
        "checkInstallerVersion",
        "checkSdkApiReferenceBuilder",
        "checkSdkV2ExactApiCompatibility",
        "checkSdkV3ExactApiCompatibility",
        "checkSdkV4ExactApiCompatibility",
        "checkSdkV5ExactApiCompatibility",
        checkMarketReleaseMetadata,
        "checkAsmSupplyChainAdmission",
        "checkMappingReviewWrapperArgs",
        "checkJavaInstaller"
    )
}

tasks.named("check") {
    dependsOn(devCheck)
}
