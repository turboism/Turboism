import dev.turboism.gradle.internal.VerificationStamps

val goldenSnapshotFixtures = fileTree("testing/fixtures/snapshots") { include("**/*") }
val astGrepConfig = "tooling/ast-grep/sgconfig.yml"

tasks.register<Exec>("checkGoldenSnapshotManifest") {
    group = "verification"
    description = "Verifies immutable historical snapshot inventory, provenance and coverage."
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
    inputs.file(astGrepConfig)
    inputs.files(fileTree("tooling/ast-grep/rules") { include("**/*.yml") })
    inputs.files(fileTree("tooling/ast-grep/tests") { include("**/*.yml") })
    inputs.files(fileTree("plugins") { include("**/src/main/java/**/*.java") })
    VerificationStamps.apply(this)
    commandLine("python3", "scripts/check_ast_grep.py")
}

tasks.register<Exec>("checkAxiomaticDesignMatrix") {
    group = "verification"
    description = "Verifies Axiomatic Design FR/DP matrix schema, evidence paths, inventory alignment, and SCC classification."
    workingDir(rootDir)
    inputs.files(
        "scripts/dev/axiomatic_design_matrix.py",
        "docs/architecture/design-matrix.schema.json",
        "docs/architecture/design-matrix.json",
        "docs/architecture/AXIOMATIC_DESIGN.md",
        "docs/architecture/dependency-inventory.json",
        "settings.gradle.kts"
    )
    VerificationStamps.apply(this)
    commandLine("python3", "scripts/dev/axiomatic_design_matrix.py", "--check")
}
