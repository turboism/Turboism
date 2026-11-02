import dev.turboism.gradle.internal.VerificationStamps

val goldenSnapshotFixtures = fileTree("tests/fixtures/snapshots") { include("**/*") }

/*
 * The manifest checker and the AST rule runner are fail-closed gates, so their own
 * failure modes are covered by focused selftests: a mutated fixture, an unlisted or
 * escaped path, and a missing or unpinned ast-grep binary must fail instead of skip.
 */
val checkGoldenSnapshotManifestSelfTest by tasks.registering(Exec::class) {
    group = "verification"
    description = "Runs fail-closed fixtures for the immutable persistence snapshot inventory."
    workingDir(rootDir)
    inputs.files(
        "scripts/check_golden_snapshots.py",
        "scripts/test/test_check_golden_snapshots.py"
    )
    VerificationStamps.apply(this)
    commandLine("python3", "scripts/test/test_check_golden_snapshots.py")
}

val checkAstGrepSelfTest by tasks.registering(Exec::class) {
    group = "verification"
    description = "Proves the AST gate rejects a missing or unpinned ast-grep binary instead of skipping."
    workingDir(rootDir)
    inputs.files(
        "scripts/check_ast_grep.py",
        "scripts/test/test_check_ast_grep.py",
        "sgconfig.yml"
    )
    VerificationStamps.apply(this)
    commandLine("python3", "scripts/test/test_check_ast_grep.py")
}

tasks.register<Exec>("checkGoldenSnapshotManifest") {
    group = "verification"
    description = "Verifies immutable historical snapshot inventory and SHA-256 digests."
    workingDir(rootDir)
    dependsOn(checkGoldenSnapshotManifestSelfTest)
    inputs.file("scripts/check_golden_snapshots.py")
    inputs.files(goldenSnapshotFixtures)
    VerificationStamps.apply(this)
    commandLine("python3", "scripts/check_golden_snapshots.py")
}

tasks.register<Exec>("checkArchitectureRules") {
    group = "verification"
    description = "Runs pinned ast-grep rule tests and enforces production AST guardrails."
    workingDir(rootDir)
    dependsOn(checkAstGrepSelfTest)
    inputs.file("scripts/check_ast_grep.py")
    inputs.file("sgconfig.yml")
    inputs.files(fileTree("tooling/ast-grep/rules") { include("**/*.yml") })
    inputs.files(fileTree("tooling/ast-grep/tests") { include("**/*.yml") })
    inputs.files(fileTree("plugins") { include("**/src/main/java/**/*.java") })
    VerificationStamps.apply(this)
    commandLine("python3", "scripts/check_ast_grep.py")
}

tasks.named("devCheck") {
    dependsOn("checkArchitectureRules", "checkGoldenSnapshotManifest", ":runtime:persistenceGoldenSnapshotsTest")
}
tasks.named("checkCompletedCommit") {
    dependsOn("checkArchitectureRules", "checkGoldenSnapshotManifest")
}
tasks.named("checkRelease") {
    dependsOn("checkArchitectureRules", "checkGoldenSnapshotManifest")
}
