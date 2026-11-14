import dev.turboism.gradle.internal.GateManifest

/*
 * The verification gate assembly point. This script owns exactly three things:
 *
 *   1. the aggregate gates, registered from gradle/gates.txt through GateManifest;
 *   2. the four dynamic `@token` collections the manifest cannot name statically;
 *   3. the root `check` lifecycle alias.
 *
 * Everything else lives in gradle/verification/, split by the domain whose contract it proves:
 *
 *   code-quality.gradle.kts       source, package and repository hygiene rules
 *   tooling-contracts.gradle.kts  gates over the build tooling itself
 *   plugin-contracts.gradle.kts   first-party plugin descriptor, README and API-range rules
 *   cubism-contracts.gradle.kts   verification records, DRAFT packs and compatibility matrix
 *   host-validation.gradle.kts    exact-host lanes; never a default gate
 *   release-gates.gradle.kts      release-only signing, payload and receipt gates
 *
 * The split exists so a leaf task change touches one small script instead of the single file every
 * branch used to edit. Membership still has exactly one statement: gradle/gates.txt. The domain
 * scripts register leaves only and must never re-open an aggregate; scripts/test/test_ci_coverage.py
 * enforces that over every gradle/**/*.gradle.kts, and this script is the one file allowed to call
 * GateManifest.load.
 *
 * Verification is deliberately layered by cost:
 *
 *   devCheck          daily production compilation, permanent boundaries, and
 *                     the focused admission unit-test suites
 *   focused tests     selected by the current SDD acceptance conditions
 *   checkIntegration  packaged/runtime and affected cross-module behavior
 *   checkRelease      supply-chain, API tooling, packaging, and release checks
 *   host validation   explicit exact-version Cubism runs; never a default gate
 *
 * Historical M1-M16/M13/M14 governance tasks are intentionally absent.
 */

/*
 * Application order is a real dependency: the record-consumer wiring in cubism-contracts.gradle.kts
 * resolves task names with tasks.named(), so every producer it names must already be registered.
 */
apply(from = "gradle/verification/code-quality.gradle.kts")
apply(from = "gradle/verification/tooling-contracts.gradle.kts")
apply(from = "gradle/verification/plugin-contracts.gradle.kts")
apply(from = "gradle/verification/cubism-contracts.gradle.kts")
apply(from = "gradle/verification/host-validation.gradle.kts")
apply(from = "gradle/verification/release-gates.gradle.kts")

// :testing:integration-tests is excluded so the fast gate never waits for the packaged suites.
val productionClasses = subprojects
    .filterNot { it.path == ":testing:integration-tests" }
    .map { "${it.path}:classes" }

// Task-graph time is after every project is evaluated, so a provider can inspect subproject tasks
// without forcing an evaluation order at configuration time.
val ordinaryTestTasks = provider {
    subprojects.filter { it.tasks.findByName("test") != null }.map { "${it.path}:test" }
}

val spotlessCheckTasks = provider {
    subprojects.filter { it.tasks.findByName("spotlessCheck") != null }.map { "${it.path}:spotlessCheck" }
}

/*
 * The tokens are the four dynamic collections the manifest cannot name statically. Loading fails
 * closed both ways: a member naming a token this map does not bind, and a bound token no member
 * names, both break the build instead of silently shrinking a gate.
 */
val gateTokens: Map<String, Any> = mapOf(
    "@all-module-classes" to productionClasses,
    "@all-module-tests" to ordinaryTestTasks,
    "@all-module-spotless" to spotlessCheckTasks,
    // Registered on demand by TurboismPluginExtension.verifySubscriberCatalog; the live collection
    // keeps devCheck wired without a hardcoded plugin list and matches nothing if it is not.
    "@matching:checkGeneratedSubscriberCatalogs" to tasks.matching { it.name == "checkGeneratedSubscriberCatalogs" }
)

/*
 * The aggregate gates are declared in gradle/gates.txt and registered here, once, after every leaf
 * task exists: a member that is not a `@token` stays a string, so a task registered by a script
 * applied after this one still resolves lazily by name.
 */
val gateManifest = GateManifest.load(rootDir.toPath().resolve("gradle/gates.txt"), gateTokens.keys)

gateManifest.aggregateNames().forEach { name ->
    val aggregate = gateManifest.aggregate(name)
    tasks.register(name) {
        group = aggregate.group()
        description = aggregate.description()
        dependsOn(aggregate.members().map { member -> gateTokens[member] ?: member })
    }
}

// The root lifecycle task is an alias for the daily gate, not a second aggregate.
tasks.named("check") {
    dependsOn("devCheck")
}
