import org.gradle.api.tasks.Exec
import dev.turboism.gradle.internal.VerificationStamps

/*
 * Gates over the build tooling itself: the mapping-review wrapper, the test-execution guard, the
 * changelog renderer and the host-tooling runners. A regression here silently stops another gate
 * from proving its subject, so each contract executes the real script instead of a copy.
 *
 * Leaves only; gradle/verification.gradle.kts owns every aggregate membership.
 */

tasks.register<Exec>("checkMappingReviewWrapperArgs") {
    group = "verification"
    description = "Verifies mapping-review wrapper argv transport and args-file hardening offline."
    workingDir(rootDir)
    inputs.files(
        "scripts/test/test_mapping_review_wrapper_args.sh",
        "scripts/dev/mapping-review.sh",
        "scripts/dev/worktree-id.sh",
        fileTree("buildSrc/src/main/java/dev/turboism/gradle/internal") { include("*.java") }
    )
    VerificationStamps.apply(this)
    commandLine("bash", "scripts/test/test_mapping_review_wrapper_args.sh")
}

val checkTestExecutionGuard by tasks.registering(Exec::class) {
    group = "verification"
    description = "Replays the buildSrc TestExecutionGuard rules against fixture JUnit XML."
    workingDir(rootDir)
    inputs.files(
        "buildSrc/build.gradle.kts",
        fileTree("buildSrc/src") { include("**/*.java", "**/*.kt") }
    )
    VerificationStamps.apply(this)
    commandLine("./gradlew", "-p", "buildSrc", "test", "--console=plain")
}

/*
 * The tracked plugin-sections.nsh is generated for the Windows installer and was
 * never compared with a regeneration. The gate rebuilds the sections from the
 * repository plugin metadata through the production generator block and compares
 * bytes; see packaging/windows-installer/check_plugin_sections.py.
 */
tasks.register<Exec>("checkLocalizedChangelogs") {
    group = "verification"
    description = "Verifies the localized changelogs under docs/<locale>/ render exactly from release-notes JSON."
    workingDir(rootDir)
    inputs.files(
        "docs/en/CHANGELOG.md",
        "docs/zh/CHANGELOG.md",
        "docs/ja/CHANGELOG.md",
        "docs/ko/CHANGELOG.md",
        fileTree("distribution/release-notes") { include("*.json") },
        "scripts/release/render_localized_changelogs.py"
    )
    VerificationStamps.apply(this)
    commandLine("python3", "scripts/release/render_localized_changelogs.py", "--check")
}

tasks.register<Exec>("checkLocalizedChangelogsSelfTest") {
    group = "verification"
    description = "Self-test for the localized changelog renderer."
    workingDir(rootDir)
    inputs.files(
        "scripts/release/render_localized_changelogs.py",
        "scripts/test/test_render_localized_changelogs.py"
    )
    VerificationStamps.apply(this)
    commandLine("python3", "scripts/test/test_render_localized_changelogs.py")
}

val checkPerformanceProbeReports by tasks.registering(Exec::class) {
    group = "verification"
    description = "Checks legacy and image-pipeline diagnostic evidence without launching Cubism."
    workingDir(rootDir)
    inputs.files(
        "scripts/preview/verify-cubism-performance-probe.py",
        "scripts/test/test_image_performance_probe.py"
    )
    VerificationStamps.apply(this)
    commandLine("python3", "-m", "unittest", "-v", "scripts/test/test_image_performance_probe.py")
}

val checkHostToolingSelfTest by tasks.registering(Exec::class) {
    group = "verification"
    description = "Runs offline host-tooling Python and worktree shell contracts."
    dependsOn(":sdk:jar", ":bootstrap:jar")
    workingDir(rootDir)
    // Always run: the worktree contracts inspect the current checkout and artifacts.
    commandLine("python3", "scripts/test/run_host_tooling_selftest.py")
}

val modelImport3dHostToolingTest by tasks.registering(Exec::class) {
    group = "host verification"
    description = "Runs offline 3D import tooling suites in four processes; missing authorized inputs skip. Opt-in: ~42s exceeds the 30s regular-gate budget (JDK compile, dense matrices and byte-budget controls)."
    workingDir(rootDir)
    val pluginJar = project(":plugins:model-import-3d").tasks.named<Jar>("jar")
    dependsOn(pluginJar)
    doFirst {
        environment("TURBOISM_MI3D_PLUGIN_JAR", pluginJar.get().archiveFile.get().asFile.absolutePath)
    }
    commandLine("python3", "validation/model-import-3d-host-probe/test-tooling.py", "test_model_import_3d_*.py")
}

val worktreeIdGateTest by tasks.registering(Exec::class) {
    group = "host verification"
    description = "Runs worktree ID gate contracts with multiple isolated Gradle invocations."
    workingDir(rootDir)
    commandLine("bash", "scripts/test/test_worktree_id_gate.sh")
}

