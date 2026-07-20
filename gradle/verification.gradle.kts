import org.gradle.api.tasks.Exec

tasks.register<Exec>("checkMappingPipelineClosure") {
    group = "verification"
    description = "Validates the BT5 mapping-pipeline closure evidence and boundaries."
    workingDir(rootDir)
    commandLine("bash", "scripts/test/test_bt5_mapping_pipeline_closure.sh")
}

tasks.register<Exec>("checkMappingReviewWrapperArgs") {
    group = "verification"
    description = "Verifies mapping-review wrapper argv transport and args-file hardening offline."
    workingDir(rootDir)
    commandLine("bash", "scripts/test/test_mapping_review_wrapper_args.sh")
}

tasks.register<Exec>("checkPluginInspectionContract") {
    group = "verification"
    description = "Verifies the strict plugin inspection schema, fixtures, streaming, and evidence boundaries."
    workingDir(rootDir)
    commandLine("bash", "scripts/test/test_plugin_inspection_contract.sh")
}

val checkLegacyFrameworkCapabilityExtraction by tasks.registering(Exec::class) {
    group = "verification"
    description = "Validates the 13-plugin legacy-to-framework capability extraction and closed governance schema."
    workingDir(rootDir)
    inputs.files(
        "docs/migration/capabilities/legacy-framework-capability-extraction.tsv",
        "docs/migration/capabilities/capability-catalog.tsv",
        "docs/migration/capabilities/plugin-readiness-matrix.tsv",
        "docs/migration/plans/legacy-framework-capability-extraction-prd.md",
        fileTree("docs/migration/salvage-notes") { include("legacy-turboism-*.md") },
        "scripts/test/test_legacy_framework_capability_extraction.py"
    )
    commandLine("python3", "scripts/test/test_legacy_framework_capability_extraction.py")
}

val checkLegacyFrameworkCapabilityExtractionMutations by tasks.registering(Exec::class) {
    group = "verification"
    description = "Runs sandboxed negative mutations for the legacy capability extraction gate."
    workingDir(rootDir)
    inputs.files(
        "scripts/test/test_legacy_framework_capability_extraction.py",
        "scripts/test/test_legacy_framework_capability_extraction_mutations.py",
        "docs/migration/capabilities/legacy-framework-capability-extraction.tsv",
        "docs/migration/capabilities/capability-catalog.tsv",
        "docs/migration/capabilities/plugin-readiness-matrix.tsv"
    )
    commandLine("python3", "scripts/test/test_legacy_framework_capability_extraction_mutations.py")
}

val checkLegacyUserEffectCensus by tasks.registering(Exec::class) {
    group = "verification"
    description = "Validates the closed user-entry-led legacy effect census, Oracle gates, and old-ledger reconciliation."
    workingDir(rootDir)
    inputs.files(
        "scripts/test/test_legacy_user_effect_census.py",
        "docs/migration/legacy-user-effects.tsv",
        "docs/migration/legacy-user-effect-census.tsv",
        "docs/migration/legacy-user-entry-coverage.tsv",
        "docs/migration/legacy-user-effect-reconciliation.tsv",
        "docs/migration/effect-clusters.tsv",
        "docs/migration/history/legacy-user-effects-pre-census.tsv",
        "docs/migration/capabilities/legacy-framework-capability-extraction.tsv",
        "docs/migration/legacy-framework-extraction-user-effect-map.tsv",
        "docs/migration/legacy-user-effect-census-workbook.md",
        "docs/migration/capabilities/capability-catalog.tsv",
        "docs/adr/0027-user-effect-led-legacy-migration.md"
    )
    commandLine("python3", "scripts/test/test_legacy_user_effect_census.py")
}

val checkLegacyEffectContracts by tasks.registering(Exec::class) {
    group = "verification"
    description = "Validates executable Effect Contracts for every MIGRATE User Effect."
    workingDir(rootDir)
    dependsOn(checkLegacyUserEffectCensus)
    inputs.files(
        "scripts/test/test_legacy_effect_contracts.py",
        "docs/migration/legacy-user-effects.tsv",
        "docs/migration/effect-clusters.tsv",
        "docs/migration/effect-contracts/index.tsv",
        "docs/migration/effect-contracts/scenarios.tsv",
        fileTree("docs/migration/effect-contracts") { include("*.md") },
        "docs/migration/capabilities/capability-catalog.tsv"
    )
    commandLine("python3", "scripts/test/test_legacy_effect_contracts.py")
}

val checkLegacyPluginB1Admission by tasks.registering(Exec::class) {
    group = "verification"
    description = "Verifies the exact 14 pure-business behaviors admitted after migration-foundation closure."
    workingDir(rootDir)
    dependsOn("checkSdkPhase1ExactApiCompatibility", "checkModuleBoundaries")
    inputs.files(
        "scripts/test/check_legacy_plugin_b1_admission.py",
        "scripts/test/check_legacy_plugin_b1_source_boundaries.py",
        "scripts/test/test_legacy_plugin_b1_source_boundaries.py",
        fileTree("plugins") {
            include("*/src/main/java/**/*.java")
            include("*/src/main/resources/META-INF/turboism/plugin.json")
        },
        "docs/migration/capabilities/legacy-framework-capability-extraction.tsv",
        "docs/migration/legacy-plugin-migration-foundation-closure-report.md",
        "docs/migration/plans/legacy-plugin-b1-execution-plan.md",
        "docs/migration/prompts/legacy-plugin-b1-orchestrator-prompt.md",
        "docs/migration/behavior-specs/legacy-plugin-b1-pure-behaviors.md",
        "docs/migration/salvage-notes/legacy-plugin-b1-source-boundary.md",
        "scripts/test/scan_migration_docs_safety.py"
    )
    commandLine("python3", "scripts/test/check_legacy_plugin_b1_admission.py")
}

tasks.register("checkOfficialPluginI18nCompleteness") {
    group = "verification"
    description = "Verifies baseline key completeness for participating official plugin catalogs."
    dependsOn(":tests:officialPluginI18nCompletenessTest")
}

val checkAsyncHostReadStructuralBoundaries by tasks.registering(Exec::class) {
    group = "verification"
    description = "Rejects plugin-owned Thread, Executor, and Timer resources and synchronous Project Inspector host reads."
    workingDir(rootDir)
    inputs.files(fileTree("plugins") { include("*/src/main/java/**/*.java") }, "scripts/test/test_async_host_read_foundation.py")
    commandLine("python3", "scripts/test/test_async_host_read_foundation.py")
}

val checkAsyncHostReadFoundation by tasks.registering {
    group = "verification"
    description = "Verifies the bounded v1 async host-read foundation and Project Inspector reference consumer."
    dependsOn(
        ":sdk:asyncHostReadContractTest", ":runtime:asyncHostReadFoundationTest",
        ":plugins:project-inspector:asyncHostReadConsumerTest", ":tests:asyncHostReadPreviewIntegrationTest",
        checkAsyncHostReadStructuralBoundaries, checkLegacyFrameworkCapabilityExtraction,
        checkLegacyFrameworkCapabilityExtractionMutations
    )
}

val checkMigrationSuiteBundleReproducibility by tasks.registering(Exec::class) {
    group = "verification"
    description = "Rebuilds the migration-suite bundle twice and compares the exact 16-file raw SHA-256 roster."
    workingDir(rootDir)
    inputs.files("build.gradle.kts", "tests/build.gradle.kts", "scripts/test/test_migration_suite_bundle_reproducibility.sh")
    onlyIf { providers.environmentVariable("TURBOISM_MIGRATION_SUITE_REPRO_INNER").orNull != "1" }
    commandLine("bash", "scripts/test/test_migration_suite_bundle_reproducibility.sh")
}

tasks.named("check") {
    dependsOn(
        checkAsyncHostReadFoundation, "checkModuleBoundaries", "checkAsmSupplyChainAdmission",
        "checkMappingPipelineClosure", "checkMappingReviewWrapperArgs", "checkPluginInspectionRuntime",
        "checkDistributionProtocolContract", checkLegacyFrameworkCapabilityExtraction,
        checkLegacyFrameworkCapabilityExtractionMutations, checkLegacyUserEffectCensus,
        checkLegacyEffectContracts, checkLegacyPluginB1Admission,
        "checkSdkApiBaselineTool", "checkSdkApiReferenceBuilder",
        "checkSdkPrePhaseApiCompatibility", "checkSdkPhase1ExactApiCompatibility",
        "checkPreviewBundleLayout", "previewAgentSmokeTest",
        "checkPreviewRuntimeReports", ":tests:previewPluginRuntimeTest", ":tests:migrationSuiteSafeTest",
        checkMigrationSuiteBundleReproducibility, "checkOfficialPluginI18nCompleteness", "validatePluginMeta"
    )
}
