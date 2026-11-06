package dev.turboism.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.config.RuntimeStartupConfig;
import dev.turboism.config.optimization.Optimization;
import dev.turboism.config.optimization.OptimizationCatalog;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Proves the one launch decision table: fail-closed on a rejected configuration, refusal codes
 * that stay byte-identical to the literals the contributors used before the relations were
 * declared, the admission precedence over the declared relations, and immutability.
 */
class OptimizationPlanTest {

    @TempDir
    Path home;

    /** Declared override property to its previous value; {@code null} means it was not set. */
    private final Map<String, String> properties = new LinkedHashMap<>();

    @BeforeEach
    void clearOverrides() {
        for (final Optimization optimization : OptimizationCatalog.all()) {
            for (final String property : optimization.overrideProperties()) {
                properties.put(property, System.getProperty(property));
                System.clearProperty(property);
            }
        }
    }

    @AfterEach
    void restoreOverrides() {
        properties.forEach((property, previous) -> {
            if (previous == null) System.clearProperty(property);
            else System.setProperty(property, previous);
        });
        properties.clear();
    }

    /**
     * I6: an unreadable or invalid {@code config.json} leaves every optimization off with the
     * rejected-configuration reason, so a typo in the file can never enable a native optimization.
     */
    @Test
    void rejectedConfigRefusesEveryEntry() throws Exception {
        Files.writeString(home.resolve("config.json"), "{\"launcher\":{\"modelUpdateSkip\":true,}");
        final OptimizationPlan plan = plan();

        for (final Optimization optimization : OptimizationCatalog.persisted()) {
            assertEquals(
                    "invalid-config",
                    plan.choice(optimization).source(),
                    optimization.key() + " must report the rejected-configuration source");
            assertFalse(plan.admitted(optimization), optimization.key() + " must stay off");
            final OptimizationPlan.Decision.Refused refusal = refusal(plan, optimization);
            assertEquals(
                    OptimizationPlan.Decision.CONFIG_REJECTED,
                    refusal.reasonCode(),
                    optimization.key() + " must refuse with the rejected-configuration reason");
            assertTrue(refusal.cause().isEmpty(), "a rejected configuration is not a relation refusal");
        }
    }

    /**
     * I6, second half: an unreadable file is rejected as well, and the fail-closed startup subset
     * the installers consult carries safe mode instead of a half-parsed policy.
     */
    @Test
    void unreadableFileFailsClosedAndThePolicyRefusesEveryHook() throws Exception {
        Files.createDirectory(home.resolve("config.json"));
        final OptimizationPlan plan = plan();

        for (final Optimization optimization : OptimizationCatalog.persisted()) {
            assertEquals("config-rejected", refusal(plan, optimization).reasonCode());
        }
        assertTrue(plan.policy() != null && plan.policy().safeMode(), "a rejected file must fail closed");
        assertFalse(plan.policy().hookEnabled("cubism.model-update.unchanged-frame-skip"));
    }

    /**
     * I7: every relation reports the refusal code the contributor hard-coded before the relation
     * was declared, so the status dialog keeps naming the same reason.
     */
    @Test
    void relationReasonCodesMatchTheLegacyLiterals() throws Exception {
        // The literals the three contributors returned before the relations were declared; the
        // catalogue must reproduce them byte-for-byte.
        assertLegacy(
                OptimizationCatalog.UPLOAD_ELISION, OptimizationCatalog.MODEL_UPDATE_SKIP, "modelUpdateSkip-disabled");
        assertLegacy(
                OptimizationCatalog.UPLOAD_ELISION,
                OptimizationCatalog.UNIFORM_LOCATION_CACHE,
                "uniformLocationCache-enabled");
        assertLegacy(
                OptimizationCatalog.MESH_SETUP_ELISION,
                OptimizationCatalog.MODEL_UPDATE_SKIP,
                "modelUpdateSkip-disabled");
        assertLegacy(
                OptimizationCatalog.CLIP_MASK_SCISSOR_CLEAR,
                OptimizationCatalog.CLIP_MASK_DEDUPLICATION,
                "clipMaskDeduplication-disabled");
        assertLegacy(
                OptimizationCatalog.INCREMENTAL_UPDATE_AFFECTERS,
                OptimizationCatalog.INCREMENTAL_UPDATE,
                "incrementalUpdate-disabled");

        // ... and the plan refuses with exactly those codes, in that order.
        writeConfig(Map.of("uploadElision", true), Map.of());
        assertEquals(
                "modelUpdateSkip-disabled",
                refusal(plan(), OptimizationCatalog.UPLOAD_ELISION).reasonCode());

        writeConfig(Map.of("uploadElision", true, "modelUpdateSkip", true, "uniformLocationCache", true), Map.of());
        assertEquals(
                "uniformLocationCache-enabled",
                refusal(plan(), OptimizationCatalog.UPLOAD_ELISION).reasonCode());

        writeConfig(Map.of("meshSetupElision", true), Map.of());
        assertEquals(
                "modelUpdateSkip-disabled",
                refusal(plan(), OptimizationCatalog.MESH_SETUP_ELISION).reasonCode());

        writeConfig(Map.of("clipMaskScissorClear", true), Map.of());
        assertEquals(
                "clipMaskDeduplication-disabled",
                refusal(plan(), OptimizationCatalog.CLIP_MASK_SCISSOR_CLEAR).reasonCode());

        writeConfig(Map.of("incrementalUpdateAffecters", true), Map.of());
        assertEquals(
                "incrementalUpdate-disabled",
                refusal(plan(), OptimizationCatalog.INCREMENTAL_UPDATE_AFFECTERS)
                        .reasonCode());

        writeConfig(Map.of(), Map.of());
        assertEquals(
                OptimizationPlan.Decision.PREFERENCE_DISABLED,
                refusal(plan(), OptimizationCatalog.UPLOAD_ELISION).reasonCode());
    }

    /**
     * I9: the truth table over two relations. Preference first, then the relations in declaration
     * order, so the reported reason is the first one that failed.
     */
    @Test
    void admissionPrecedenceIsPreferenceThenRelationsInDeclarationOrder() throws Exception {
        final List<Case> cases = List.of(
                // uploadElision, modelUpdateSkip, uniformLocationCache -> expected
                new Case(false, false, false, "preference-disabled"),
                new Case(false, true, true, "preference-disabled"),
                new Case(true, false, false, "modelUpdateSkip-disabled"),
                new Case(true, false, true, "modelUpdateSkip-disabled"),
                new Case(true, true, true, "uniformLocationCache-enabled"),
                new Case(true, true, false, null));
        for (final Case testCase : cases) {
            writeConfig(
                    Map.of(
                            "uploadElision", testCase.uploadElision(),
                            "modelUpdateSkip", testCase.modelUpdateSkip(),
                            "uniformLocationCache", testCase.uniformLocationCache()),
                    Map.of());
            final OptimizationPlan plan = plan();
            final OptimizationPlan.Decision decision = plan.decision(OptimizationCatalog.UPLOAD_ELISION);
            if (testCase.expectedReason() == null) {
                assertInstanceOf(
                        OptimizationPlan.Decision.Install.class, decision, "the fully satisfied case installs");
                assertTrue(plan.admitted(OptimizationCatalog.UPLOAD_ELISION));
            } else {
                assertEquals(
                        testCase.expectedReason(),
                        refusal(plan, OptimizationCatalog.UPLOAD_ELISION).reasonCode(),
                        "uploadElision=" + testCase.uploadElision() + " modelUpdateSkip=" + testCase.modelUpdateSkip()
                                + " uniformLocationCache=" + testCase.uniformLocationCache());
                assertFalse(plan.admitted(OptimizationCatalog.UPLOAD_ELISION));
            }
        }
    }

    /** I9 for the derived default: the probe follows the entries it serves, and its switch wins. */
    @Test
    void derivedEntryFollowsItsTargetsAndItsExplicitSwitchWins() throws Exception {
        writeConfig(Map.of(), Map.of());
        assertFalse(plan().admitted(OptimizationCatalog.SORTING_INFO_CACHE));

        writeConfig(Map.of("modelUpdateSkip", true), Map.of());
        assertTrue(plan().admitted(OptimizationCatalog.SORTING_INFO_CACHE));
        assertEquals(
                "derived-preference",
                plan().choice(OptimizationCatalog.SORTING_INFO_CACHE).source());

        writeConfig(Map.of("meshSetupElision", true, "modelUpdateSkip", true), Map.of());
        assertTrue(plan().admitted(OptimizationCatalog.SORTING_INFO_CACHE));

        writeConfig(Map.of("clipMaskDeduplication", true), Map.of());
        assertTrue(plan().admitted(OptimizationCatalog.SORTING_INFO_CACHE));

        // An explicit switch overrides the derived default in both directions.
        writeConfig(Map.of("modelUpdateSkip", true), Map.of());
        System.setProperty("turboism.optimization.sortingInfoCache", "false");
        assertFalse(plan().admitted(OptimizationCatalog.SORTING_INFO_CACHE));
        writeConfig(Map.of(), Map.of());
        System.setProperty("turboism.optimization.sortingInfoCache", "true");
        assertTrue(plan().admitted(OptimizationCatalog.SORTING_INFO_CACHE));
    }

    /** A relation only refuses the entry it is declared on; its target keeps its own decision. */
    @Test
    void relationsDoNotLeakToTheTargetEntry() throws Exception {
        writeConfig(Map.of("uploadElision", true, "modelUpdateSkip", true, "uniformLocationCache", true), Map.of());
        final OptimizationPlan plan = plan();
        assertEquals(
                "uniformLocationCache-enabled",
                refusal(plan, OptimizationCatalog.UPLOAD_ELISION).reasonCode());
        assertTrue(plan.admitted(OptimizationCatalog.MODEL_UPDATE_SKIP));
        assertTrue(plan.admitted(OptimizationCatalog.UNIFORM_LOCATION_CACHE));
        assertTrue(
                plan.choice(OptimizationCatalog.UPLOAD_ELISION).enabled(),
                "the preference itself stays on; only the decision folds the conflict");
    }

    /**
     * I12: the plan is immutable and built once per launch, so two hooks of one launch cannot see
     * two preferences and nothing can rewrite a decision after the first hook read it.
     */
    @Test
    void planIsImmutable() throws Exception {
        writeConfig(Map.of("modelUpdateSkip", true), Map.of());
        final HookEnvironment environment = environment();
        final OptimizationPlan plan = environment.plan();

        assertSame(plan, environment.plan(), "one launch resolves one plan");
        assertSame(plan, environment.forHook("first").plan(), "a per-hook environment reuses the launch plan");
        assertSame(plan, environment.forHook("second").plan());
        assertNotSame(plan, environment(), "a new launch resolves its own plan");

        assertThrows(
                UnsupportedOperationException.class,
                () -> plan.decisions().clear(),
                "no caller may rewrite a resolved decision");
        assertThrows(
                UnsupportedOperationException.class,
                () -> OptimizationCatalog.UPLOAD_ELISION.relations().clear(),
                "the declared relations are immutable");
        assertThrows(
                IllegalArgumentException.class,
                () -> plan.decision(undeclaredEntry()),
                "the plan only answers for declared optimizations");
        assertEquals(
                OptimizationCatalog.all().size(),
                plan.decisions().size(),
                "every declared entry has exactly one decision");
    }

    /** The catalogue's relation list is what the plan reads; a hand-built row is not in it. */
    private static Optimization undeclaredEntry() {
        return new Optimization(
                "notDeclared",
                "not-declared",
                Optimization.Access.PERSISTED_LAUNCHER,
                List.of("turboism.optimization.notDeclared"),
                List.of("TURBOISM_NOT_DECLARED"),
                Set.of("cubism.not-declared"),
                Optional.of(new Optimization.Placement("rendering", 199)),
                "settings.optimization.not-declared",
                "settings.optimization.not-declared.tooltip",
                List.of());
    }

    private static void assertLegacy(final Optimization entry, final Optimization target, final String legacyLiteral) {
        final List<Optimization.Relation> matching = entry.relations().stream()
                .filter(relation -> relation.target().equals(target))
                .toList();
        assertEquals(1, matching.size(), entry.key() + " must declare exactly one relation to " + target.key());
        assertEquals(legacyLiteral, matching.get(0).reasonCode());
        assertEquals(
                legacyLiteral,
                Optimization.Relation.expectedReasonCode(matching.get(0).kind(), target),
                "the derived code stays byte-identical for " + entry.key());
    }

    private OptimizationPlan plan() {
        return environment().plan();
    }

    private HookEnvironment environment() {
        return HookEnvironment.builder()
                .options(AgentOptions.parse("home=" + home, home))
                .startupPolicy(new RuntimeStartupConfig(false, true, true, true))
                .build();
    }

    private static OptimizationPlan.Decision.Refused refusal(final OptimizationPlan plan, final Optimization entry) {
        return assertInstanceOf(
                OptimizationPlan.Decision.Refused.class, plan.decision(entry), entry.key() + " must be refused");
    }

    /** Writes one config document with the given launcher and root booleans. */
    private void writeConfig(final Map<String, Boolean> launcher, final Map<String, Boolean> root) throws Exception {
        final List<String> parts = new ArrayList<>();
        parts.add("\"format\":\"turboism.runtime.config\"");
        parts.add("\"schemaVersion\":1");
        parts.add("\"worktreeId\":\"optimization-plan-test\"");
        root.forEach((name, value) -> parts.add('"' + name + "\":" + value));
        final String launcherJson = launcher.entrySet().stream()
                .map(entry -> '"' + entry.getKey() + "\":" + entry.getValue())
                .collect(java.util.stream.Collectors.joining(","));
        parts.add("\"launcher\":{" + launcherJson + "}");
        Files.writeString(home.resolve("config.json"), "{" + String.join(",", parts) + "}");
    }

    /** One row of the two-relation truth table. */
    private record Case(
            boolean uploadElision, boolean modelUpdateSkip, boolean uniformLocationCache, String expectedReason) {}
}
