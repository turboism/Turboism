import dev.turboism.gradle.internal.VerificationStamps

val goldenSnapshotFixtures = fileTree("tests/fixtures/snapshots") { include("**/*") }

tasks.register<Exec>("checkGoldenSnapshotManifest") {
    group = "verification"
    description = "Verifies immutable historical snapshot inventory and SHA-256 digests."
    workingDir(rootDir)
    inputs.file("scripts/check_golden_snapshots.py")
    inputs.files(goldenSnapshotFixtures)
    VerificationStamps.apply(this)
    commandLine("python3", "scripts/check_golden_snapshots.py")
}

tasks.register<Exec>("checkArchitectureRules") {
    group = "verification"
    description = "Runs ast-grep rule tests and enforces production AST guardrails."
    workingDir(rootDir)
    inputs.file("scripts/check_ast_grep.py")
    inputs.file("sgconfig.yml")
    inputs.files(fileTree("tooling/ast-grep/rules") { include("**/*.yml") })
    inputs.files(fileTree("tooling/ast-grep/tests") { include("**/*.yml") })
    inputs.files(fileTree("plugins") { include("**/src/main/java/**/*.java") })
    VerificationStamps.apply(this)
    commandLine("python3", "scripts/check_ast_grep.py")
}
