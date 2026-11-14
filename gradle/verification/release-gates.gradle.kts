import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.SourceSetContainer
import dev.turboism.gradle.internal.VerificationStamps

/*
 * Release-only gates: framework signing, release payload planning and bootstrap-receipt
 * integration. checkReleasePackaging and checkRelease name them from gradle/gates.txt; ordinary
 * CI never waits for them.
 *
 * Leaves only; gradle/verification.gradle.kts owns every aggregate membership.
 */

val checkFrameworkUpdateTooling by tasks.registering(Exec::class) {
    group = "verification"
    description = "Tests deterministic offline framework signing and build inventory boundaries."
    workingDir(rootDir)
    inputs.files(
        "scripts/release/build-framework-update.py",
        "scripts/release/prepare-framework-client.py",
        "scripts/release/prepare-framework-release.py",
        fileTree("scripts/release/turboism_release") { include("*.py") },
        "scripts/test/test_release_tooling.py",
        "scripts/test/test_framework_client.py",
        "scripts/test/test_framework_release.py",
        "bootstrap/build.gradle.kts",
        ".github/workflows/release.yml",
        ".github/workflows/release-github-only.yml",
        ".github/workflows/framework-release-signing.yml",
        fileTree("packaging") { include("**/*") },
        "LICENSE",
        "graal-host/build.gradle.kts",
        "buildSrc/src/main/kotlin/turboism.java-conventions.gradle.kts"
    )
    VerificationStamps.apply(this)
    commandLine("python3", "-m", "unittest", "-v",
        "scripts.test.test_release_tooling.FrameworkUpdateToolTest",
        "scripts.test.test_framework_client",
        "scripts.test.test_framework_release")
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
        "scripts/release/build-framework-update.py",
        "packaging/framework-update-layout.json",
        "scripts/release/turboism-release.py",
        "scripts/release/promote-github-release.py",
        "scripts/release/verify-github-assets.py",
        "scripts/release/verify-plugin-publication.py",
        fileTree("scripts/release/turboism_release") { include("*.py") },
        "scripts/test/test_release_tooling.py",
        "scripts/test/test_release_orchestrator.py",
        "scripts/test/test_release_promotion.py",
        ".github/workflows/release-github-only.yml",
        "docs/RELEASING.md",
        "docs/en/CHANGELOG.md",
        ".github/workflows/release.yml"
    )
    VerificationStamps.apply(this)
    commandLine(
        "python3", "-m", "unittest", "-v",
        "scripts/test/test_release_tooling.py",
        "scripts/test/test_release_orchestrator.py",
        "scripts/test/test_release_promotion.py"
    )
}

// Ordinary CI owns the completed-commit closure; the channel workflow runs the release delta once.

// Python gates and their live-tree fixtures read the canonical materialized records.
// The materializer itself is registered in the root build, outside this gate script.
val checkBootstrapReceipt by tasks.registering(Exec::class) {
    group = "verification"
    description = "Verifies bootstrap receipt initialization, layout consistency and collision safety."
    workingDir(rootDir)
    dependsOn(":runtime:writeBootstrapReceiptTestClasspath")
    inputs.files(
        "scripts/test/test_bootstrap_receipt.py",
        fileTree("scripts/release/turboism_release") { include("*.py") },
        "packaging/framework-update-layout.json",
        "packaging/windows-installer/bootstrap-receipt.ps1",
        "packaging/windows-installer/assemble-release.sh",
        "packaging/windows-installer/installer.nsi",
        "packaging/windows-installer/configure_turboism.ps1",
        "packaging/windows-installer/launch-cubism-turboism.ps1",
        "runtime/src/main/java/dev/turboism/update/UpdateManifestEntry.java"
    )
    val classpathFile = project(":runtime").layout.buildDirectory
        .file("update-test/bootstrap-receipt-test-classpath.txt")
    inputs.file(classpathFile)
    // Track the actual runtime test classpath bytes (main/test outputs plus dependency JARs), not only the
    // exported classpath string: a recompiled class at an unchanged path must still invalidate this gate.
    val runtimeTestClasspath = project(":runtime").extensions
        .getByType<SourceSetContainer>()["test"].runtimeClasspath
    inputs.files(runtimeTestClasspath)
    VerificationStamps.apply(this)
    doFirst {
        environment(
            "TURBOISM_BOOTSTRAP_RECEIPT_TEST_CLASSPATH_FILE",
            classpathFile.get().asFile.absolutePath
        )
    }
    commandLine("python3", "-m", "unittest", "-v", "scripts.test.test_bootstrap_receipt")
}

// Real built-agent premain evidence for the framework launch lease (issue #50). The probe itself is a JavaExec in
// :bootstrap (it owns the built JAR input, the test-classes dependency and the fork classpath); the checkCompletedCommit
// dependency list above links it into the ordinary gate so a premain handshake regression fails loudly. It validates
// whatever identity the built artifact really carries, so a numbered CI build stays valid.

/*
 * The aggregate gates are declared in gradle/gates.txt and registered here, once, after every leaf
 * task above exists: a member that is not a `@token` stays a string, so a task registered by a
 * script applied after this one still resolves lazily by name.
 *
 * The tokens are the four dynamic collections the manifest cannot name statically. Loading fails
 * closed both ways: a member naming a token this map does not bind, and a bound token no member
 * names, both break the build instead of silently shrinking a gate.
 */
