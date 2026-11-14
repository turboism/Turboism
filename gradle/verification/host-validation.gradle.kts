import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.bundling.Jar
import dev.turboism.gradle.internal.VerificationStamps

/*
 * Exact-host validation lanes. None of these tasks is an aggregate member: each one starts a real
 * Cubism host, so a human or a dedicated workflow launches it on demand.
 *
 * The offline contracts at the top of this script stay cheap and prove the shared argument,
 * evidence and terminal rules without a host. The packaging and run tasks below are the lanes
 * themselves; exportHostValidationWorktreeId() makes them fail closed on a forbidden worktree ID
 * before any script runs.
 */

tasks.register<Exec>("checkCubismHostValidationArguments") {
    group = "verification"
    description = "Verifies Cubism host-validation path validation and remote argument transport offline."
    workingDir(rootDir)
    inputs.files(
        "scripts/test/test_cubism_host_validation_arguments.sh",
        "scripts/preview/run-cubism-host-validation.sh",
        "scripts/preview/host-validation-env.sh",
        "scripts/preview/host-validation-transport.sh",
        "packaging/windows-installer/config.template.json"
    )
    VerificationStamps.apply(this)
    commandLine("bash", "scripts/test/test_cubism_host_validation_arguments.sh")
}

tasks.register<Exec>("checkCubismHostValidationJavaEvidence") {
    group = "verification"
    description = "Verifies task-bound Java process evidence matching, gl-thread scan, and zero-match diagnostics offline."
    workingDir(rootDir)
    inputs.files(
        "scripts/test/test_cubism_host_validation_java_evidence.sh",
        "scripts/preview/run-cubism-host-validation.sh",
        "scripts/preview/host-validation-env.sh",
        "scripts/preview/host-validation-transport.sh"
    )
    VerificationStamps.apply(this)
    commandLine("bash", "scripts/test/test_cubism_host_validation_java_evidence.sh")
}

tasks.register<Exec>("checkCubismHostValidationLocalTransport") {
    group = "verification"
    description = "Verifies local host command/copy transport and side-effect-free dry runs."
    workingDir(rootDir)
    inputs.files(
        "scripts/test/test_cubism_host_validation_local_transport.sh",
        "scripts/preview/host-validation-transport.sh",
        "scripts/preview/host-validation-env.sh",
        "scripts/preview/run-cubism-host-validation.sh"
    )
    VerificationStamps.apply(this)
    commandLine("bash", "scripts/test/test_cubism_host_validation_local_transport.sh")
}

tasks.register<Exec>("checkHistoryValidationProbePackaging") {
    group = "verification"
    description = "Verifies history exact-host probe JARs include their descriptor-declared base i18n catalogs."
    workingDir(rootDir)
    inputs.files(
        "scripts/test/test_history_validation_probe_packaging.sh",
        "scripts/preview/package-windows-history-panel-validation.sh",
        "scripts/preview/run-history-native-ui-validation.sh",
        "scripts/preview/run-history-primary-validation.sh",
        "scripts/preview/run-cubism-host-validation.sh",
        "scripts/preview/host-validation-env.sh",
        "scripts/preview/host-validation-transport.sh"
    )
    VerificationStamps.apply(this)
    commandLine("bash", "scripts/test/test_history_validation_probe_packaging.sh")
}

tasks.register<Exec>("checkGraalScriptHostValidationDryRun") {
    group = "verification"
    description = "Verifies the Graal script host-validation wrapper uses configurable Java and packaged wildcard classpath."
    workingDir(rootDir)
    inputs.files(
        "scripts/test/test_graal_script_host_validation_dry_run.sh",
        "scripts/preview/run-graal-script-host-validation.sh"
    )
    VerificationStamps.apply(this)
    commandLine("bash", "scripts/test/test_graal_script_host_validation_dry_run.sh")
}

tasks.register<Exec>("checkGraalPreviewLauncherContract") {
    group = "verification"
    description = "Verifies ProbeOnly validates a configured Graal library closure without claiming host readiness."
    workingDir(rootDir)
    inputs.files(
        "scripts/test/test_graal_preview_launcher_contract.sh",
        "scripts/preview/launch-cubism-turboism.ps1"
    )
    VerificationStamps.apply(this)
    commandLine("bash", "scripts/test/test_graal_preview_launcher_contract.sh")
}

tasks.register("checkGraalScriptHostValidation") {
    group = "verification"
    description = "Runs offline Graal script host-validation contracts."
    dependsOn(
        "checkCubismHostValidationArguments",
        "checkCubismHostValidationJavaEvidence",
        "checkGraalScriptHostValidationDryRun",
        "checkGraalPreviewLauncherContract"
    )
}

val hostProbeDirectories = listOf(
    "validation/animation-timeline-host-probe",
    "validation/atlas-polygon-host-probe",
    "validation/clipmask-viewer-host-probe",
    "validation/parameter-batch-transfer-host-probe"
)

// The shared trigger gate owns the wait nine probes used to implement themselves. Its self check
// compiles SessionGate against a temporary state directory and an injected clock, so a regression
// in the deadline, the session turn or the timeout action fails offline instead of on a host run.
val checkSessionGateSelftest by tasks.registering(Exec::class) {
    group = "verification"
    description = "Runs the offline shared trigger-gate contract (standalone deadline, session turn, step marker)."
    workingDir(rootDir)
    val script = "validation/shared/check-session-gate.sh"
    val scriptFile = rootProject.file(script)
    inputs.files(fileTree("validation/shared") { include("**/*") })
    inputs.files(hostProbeDirectories.map { directory -> fileTree(directory) { include("**/*") } })
    doFirst {
        // A missing check must fail instead of turning into an empty green run.
        require(scriptFile.isFile) {
            "$script is missing: the shared trigger-gate contract cannot be verified"
        }
    }
    VerificationStamps.apply(this)
    commandLine("bash", script)
}

val checkProbeTerminalSelftest by tasks.registering(Exec::class) {
    group = "verification"
    description = "Runs the offline shared probe-terminal contract (PASS/FAIL/BLOCKED/SKIPPED, zero assertions)."
    workingDir(rootDir)
    val script = "validation/shared/check-probe-terminal.sh"
    val scriptFile = rootProject.file(script)
    // validation/shared is covered as a tree so a missing script reaches the explicit
    // require below instead of a generic "input file does not exist" validation error.
    inputs.files(fileTree("validation/shared") { include("**/*") })
    inputs.files(hostProbeDirectories.map { directory -> fileTree(directory) { include("**/*") } })
    inputs.file("scripts/preview/host_validation_evidence.py")
    doFirst {
        // A missing check must fail instead of turning into an empty green run.
        require(scriptFile.isFile) {
            "$script is missing: the shared probe-terminal contract cannot be verified"
        }
    }
    VerificationStamps.apply(this)
    commandLine("bash", script)
}

// Execute the wrapper argv and producer/consumer contracts offline. Declaring the
// scripts and shared/probe sources prevents stale stamps after a contract change.
val checkHostProbeResultContract by tasks.registering(Exec::class) {
    group = "verification"
    description = "Checks probe status tokens, wrapper argv, result layout and Runner/evidence agreement offline."
    workingDir(rootDir)
    inputs.files(
        "scripts/test/check_validation_tools_sync.py",
        "scripts/test/test_host_validation_probe_statuses.py",
        "scripts/test/test_host_validation_evidence.py",
        "scripts/test/test_cubism_host_validation_result.py",
        "scripts/test/test_host_validation_probe_wrappers.py",
        "scripts/test/test_host_validation_probe_result_layout.py",
        "scripts/dev/worktree-id.sh",
        "scripts/dev/worktree-id-lib.sh",
        "compatibility/cubism/reviewed-releases.json"
    )
    inputs.files(fileTree("scripts/preview") { include("**/*.py", "**/*.sh", "**/*.json") })
    inputs.files(fileTree("validation/shared") { include("**/*") })
    inputs.files(hostProbeDirectories.map { directory -> fileTree(directory) { include("**/*") } })
    VerificationStamps.apply(this)
    commandLine(
        "python3", "-m", "unittest", "-v",
        "scripts.test.test_host_validation_probe_statuses",
        "scripts.test.test_host_validation_evidence",
        "scripts.test.test_cubism_host_validation_result",
        "scripts.test.test_host_validation_probe_wrappers",
        "scripts.test.test_host_validation_probe_result_layout"
    )
}

/*
 * Coverage aggregation is advisory only: one JaCoCo HTML/XML report across all
 * Java modules with no thresholds and no gate. Each module's exec data lands in
 * its own worktree build directory; modules without tests contribute nothing.
 */
val resolvedHostValidationWorktreeId = rootProject.extra["turboismResolvedWorktreeId"] as String
val hostValidationWorktreeIdError = rootProject.extra["turboismWorktreeIdError"] as String?

// Marks an Exec task as a worktree-ID consumer: exports the resolved ID and fails
// closed on its validation verdict before touching packaging or the host queue.
// Explicit marking only — an Exec task inherits the caller's environment, so
// probing the env map would flag every Exec task whenever the variable is set.
// The verdict is captured as a plain value so the action never reaches the project.
fun Exec.exportHostValidationWorktreeId() {
    environment("TURBOISM_WORKTREE_ID", resolvedHostValidationWorktreeId)
    val verdict = hostValidationWorktreeIdError
    doFirst {
        verdict?.let { throw GradleException(it) }
    }
}

val packageParameterHostValidation by tasks.registering(Exec::class) {
    group = "host verification"
    description = "Packages the test-only SDK probe and parameter host-validation bundle."
    dependsOn("previewBundle", ":plugins:parameter:jar", ":testing:integration-tests:testClasses")
    workingDir(rootDir)
    exportHostValidationWorktreeId()
    commandLine("bash", "scripts/preview/package-windows-parameter-validation.sh")
}

val packageWorkspaceHostValidation by tasks.registering(Exec::class) {
    group = "host verification"
    description = "Packages the test-only SDK probe and workspace host-validation bundle."
    dependsOn("previewBundle", ":testing:integration-tests:testClasses")
    workingDir(rootDir)
    exportHostValidationWorktreeId()
    commandLine("bash", "scripts/preview/package-windows-workspace-validation.sh")
}

val packageThemeHostValidation by tasks.registering(Exec::class) {
    group = "host verification"
    description = "Packages the UI theme plugin for exact-host validation."
    dependsOn("previewBundle", ":plugins:ui-theme:jar")
    workingDir(rootDir)
    exportHostValidationWorktreeId()
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

val buildUpdateCheckHostProbe by tasks.registering(Exec::class) {
    group = "host verification"
    description = "Builds the test-only SDK update-check host exerciser."
    dependsOn(":sdk:jar")
    workingDir(rootDir)
    commandLine("bash", "validation/update-check-host-probe/build.sh")
}

tasks.register<Exec>("validateStatusBarHost5302") {
    group = "host verification"
    description = "Runs the automated exact-host Cubism 5.3.02 native status-bar matrix."
    dependsOn("previewBundle", ":sdk:jar", buildStatusBarHostProbe)
    workingDir(rootDir)
    exportHostValidationWorktreeId()
    commandLine("bash", "scripts/preview/run-status-bar-host-validation.sh", "5302")
}

val buildBoundingBoxOverlayHostProbe by tasks.registering(Exec::class) {
    group = "host verification"
    description = "Builds and self-checks the validation-only SDK bounding-box overlay exerciser."
    dependsOn(":sdk:jar")
    workingDir(rootDir)
    commandLine("bash", "validation/bounding-box-overlay-host-probe/build.sh")
}

fun registerBoundingBoxOverlayHostValidation(name: String, version: String, displayVersion: String) {
    tasks.register<Exec>(name) {
        group = "host verification"
        description = "Runs the exact-host Cubism $displayVersion bounding-box overlay matrix."
        dependsOn("previewBundle", ":sdk:jar", buildBoundingBoxOverlayHostProbe)
        workingDir(rootDir)
        exportHostValidationWorktreeId()
        commandLine("bash", "scripts/preview/run-bounding-box-overlay-host-validation.sh", version)
    }
}

registerBoundingBoxOverlayHostValidation("validateBoundingBoxOverlayHost5203", "5203", "5.2.03")
registerBoundingBoxOverlayHostValidation("validateBoundingBoxOverlayHost5302", "5302", "5.3.02")
registerBoundingBoxOverlayHostValidation("validateBoundingBoxOverlayHost5303", "5303", "5.3.03")

val buildWarpAltSymmetryHostProbe by tasks.registering(Exec::class) {
    group = "host verification"
    description = "Builds and self-checks the validation-only Warp deformer Alt-symmetry reconnaissance probe."
    dependsOn(":sdk:jar")
    workingDir(rootDir)
    commandLine("bash", "validation/warp-deformer-alt-symmetry-host-probe/build.sh")
}

fun registerWarpAltSymmetryHostValidation(name: String, version: String) {
    tasks.register<Exec>(name) {
        group = "host verification"
        description = "Runs the exact-host Cubism $version Warp deformer Alt-symmetry reconnaissance probe."
        dependsOn("previewBundle", ":sdk:jar", ":plugins:warp-deformer-alt-symmetry:jar", ":plugins:artmesh-mirror:jar", buildWarpAltSymmetryHostProbe)
        workingDir(rootDir)
        exportHostValidationWorktreeId()
        commandLine("bash", "scripts/preview/run-warp-deformer-alt-symmetry-host-validation.sh", version)
    }
}

registerWarpAltSymmetryHostValidation("validateWarpAltSymmetryHost5203", "5203")
registerWarpAltSymmetryHostValidation("validateWarpAltSymmetryHost5302", "5302")
registerWarpAltSymmetryHostValidation("validateWarpAltSymmetryHost5303", "5303")

val buildBoundingBoxWarpMirrorHostProbe by tasks.registering(Exec::class) {
    group = "host verification"
    description = "Builds and self-checks the validation-only BoundingBox Warp-mirror reconnaissance probe."
    dependsOn(":sdk:jar")
    workingDir(rootDir)
    commandLine("bash", "validation/boundingbox-warp-mirror-host-probe/build.sh")
}

tasks.register<Exec>("validateBoundingBoxWarpMirrorHost5303") {
    group = "host verification"
    description = "Runs the exact-host Cubism 5.3.03 BoundingBox Warp-mirror reconnaissance probe."
    dependsOn("previewBundle", ":sdk:jar", ":plugins:boundingbox-warp-mirror:jar", buildBoundingBoxWarpMirrorHostProbe)
    workingDir(rootDir)
    exportHostValidationWorktreeId()
    commandLine("bash", "scripts/preview/run-boundingbox-warp-mirror-host-validation.sh", "5303")
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
        exportHostValidationWorktreeId()
        commandLine("bash", "scripts/preview/run-fps-host-validation.sh", version)
    }
}

registerFpsHostValidation("validateFpsHost5203", "5203")
registerFpsHostValidation("validateFpsHost5302", "5302")

val buildModelUpdateSkipHostProbe by tasks.registering(Exec::class) {
    group = "host verification"
    description = "Builds the test-only SDK model-update-skip host exerciser."
    dependsOn(":sdk:jar")
    workingDir(rootDir)
    commandLine("bash", "validation/model-update-skip-host-probe/build.sh")
}

fun registerModelUpdateSkipHostValidation(name: String, version: String) {
    tasks.register<Exec>(name) {
        group = "host verification"
        description = "Runs the exact-host Cubism $version model-update-skip leg (-PturboismHostValidationMode=off|probe|on)."
        dependsOn("previewBundle", ":sdk:jar", buildModelUpdateSkipHostProbe)
        workingDir(rootDir)
        exportHostValidationWorktreeId()
        val mode = providers.gradleProperty("turboismHostValidationMode").orElse("probe")
        doFirst {
            commandLine(
                "bash",
                "scripts/preview/run-model-update-skip-host-validation.sh",
                mode.get(),
                version
            )
        }
    }
}

registerModelUpdateSkipHostValidation("validateModelUpdateSkipHost5203", "5203")
registerModelUpdateSkipHostValidation("validateModelUpdateSkipHost5302", "5302")
registerModelUpdateSkipHostValidation("validateModelUpdateSkipHost5303", "5303")

val buildIncrementalUpdateHostProbe by tasks.registering(Exec::class) {
    group = "host verification"
    description = "Builds the test-only SDK incremental-update host exerciser."
    dependsOn(":sdk:jar")
    workingDir(rootDir)
    commandLine("bash", "validation/incremental-update-host-probe/build.sh")
}

fun registerIncrementalUpdateHostValidation(name: String, version: String) {
    tasks.register<Exec>(name) {
        group = "host verification"
        description = "Runs the exact-host Cubism $version incremental-update leg (-PturboismHostValidationMode=off|probe|on)."
        dependsOn("previewBundle", ":sdk:jar", buildIncrementalUpdateHostProbe)
        workingDir(rootDir)
        exportHostValidationWorktreeId()
        val mode = providers.gradleProperty("turboismHostValidationMode").orElse("probe")
        doFirst {
            commandLine(
                "bash",
                "scripts/preview/run-incremental-update-host-validation.sh",
                mode.get(),
                version
            )
        }
    }
}

registerIncrementalUpdateHostValidation("validateIncrementalUpdateHost5203", "5203")
registerIncrementalUpdateHostValidation("validateIncrementalUpdateHost5302", "5302")
registerIncrementalUpdateHostValidation("validateIncrementalUpdateHost5303", "5303")

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
        exportHostValidationWorktreeId()
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
    exportHostValidationWorktreeId()
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
        exportHostValidationWorktreeId()
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
        exportHostValidationWorktreeId()
        commandLine("bash", "scripts/preview/run-theme-host-validation.sh", version)
    }
}

val packagePsdClipMaskHostValidation by tasks.registering(Exec::class) {
    group = "host verification"
    description = "Packages the PSD clip-mask import plugin and its test-only SDK probe for exact-host validation."
    dependsOn("previewBundle", ":plugins:psd-clip-mask-import:jar", ":testing:integration-tests:testClasses")
    workingDir(rootDir)
    exportHostValidationWorktreeId()
    // Bind the bundle to the exact artifact of the current Gradle jar task;
    // no directory scanning or version heuristics may substitute for it.
    val psdClipMaskPluginJar = project(":plugins:psd-clip-mask-import")
        .tasks.named<Jar>("jar").flatMap { it.archiveFile }
    doFirst {
        environment("PSD_CLIP_MASK_PLUGIN_JAR", psdClipMaskPluginJar.get().asFile.absolutePath)
    }
    commandLine("bash", "scripts/preview/package-windows-psd-clip-mask-validation.sh")
}

val checkPsdClipMaskHostValidationBundle by tasks.registering(Exec::class) {
    group = "verification"
    description = "Asserts the PSD clip-mask host-validation bundle jars carry descriptor-declared i18n catalogs."
    dependsOn(packagePsdClipMaskHostValidation)
    workingDir(rootDir)
    exportHostValidationWorktreeId()
    commandLine("bash", "scripts/preview/check-psd-clip-mask-validation-bundle.sh")
}

tasks.register<Exec>("validatePsdClipMaskHost5302") {
    group = "host verification"
    description = "Runs the automated exact-host Cubism 5.3.02 PSD clip-mask read/write/Undo/Redo matrix."
    dependsOn(packagePsdClipMaskHostValidation)
    workingDir(rootDir)
    exportHostValidationWorktreeId()
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
    exportHostValidationWorktreeId()
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
        exportHostValidationWorktreeId()
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
        exportHostValidationWorktreeId()
        commandLine("bash", "scripts/preview/run-workspace-host-validation.sh", version)
    }
}

registerThemeHostValidation("validateThemeHost5302", "5302", "5.3.02")
registerThemeHostValidation("validateThemeHost5203", "5203", "5.2.03")
registerParameterHostValidation("validateParameterHost5302", "5302", "5.3.02")
registerParameterHostValidation("validateParameterHost5203", "5203", "5.2.03")
registerWorkspaceHostValidation("validateWorkspaceHost5302", "5302", "5.3.02")
registerWorkspaceHostValidation("validateWorkspaceHost5203", "5203", "5.2.03")

/*
 * External-consumer gate: stages an inline minimal standalone plugin project to a
 * unique temporary directory and builds it exactly like a third-party consumer would —
 * the SDK is the real :sdk:jar artifact dropped into libs/, and every declared
 * repository is cleared through an init script so nothing resolves from ~/.m2 or
 * the monorepo. The produced plugin JAR is bound to its tracked descriptor through
 * the production PluginMetaValidationCli / FirstPartyMetadataVerificationCli gate
 * tools (runtime-internal; they never enter the consumer compile classpath). The
 * cleared repositories are what makes the gate fail closed on a drifted descriptor
 * or an SDK that only ~/.m2 could supply, so a stale local cache cannot mask drift.
 */
