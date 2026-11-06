package dev.turboism.exportsettings;

import static dev.turboism.exportsettings.ProtectedExportTestFixtures.CANONICAL_PLANNER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.sdk.cubism.export.ProtectedExportCensus;
import dev.turboism.sdk.cubism.export.ProtectedExportPlan;
import dev.turboism.sdk.cubism.export.ProtectedExportPlanResult;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Admission vectors for a plugin-authored {@link ProtectedExportPlan}.
 *
 * <p>The golden token expectations are the same SHA-256 values the plugin's planner test
 * asserts, so a divergence between the plugin's producer and the runtime's verifier fails
 * here instead of silently changing what a protected export publishes.</p>
 */
class ProtectedExportPlanAdmissionTest {

    @Test
    void admitsTheCanonicalPlanOfAGoldenCensus() {
        final ProtectedExportCensus census = census(
                List.of("guid-a", "guid-b"),
                List.of(),
                Map.of(),
                List.of(),
                List.of(),
                Set.of(),
                Set.of("meshA", "meshB", "id-a", "id-b"),
                Set.of("meshA", "meshB", "id-a", "id-b"));

        final ProtectedExportPlan plan = admitted(census);

        assertEquals(
                Map.of(
                        "guid-a", new ProtectedExportPlan.Target("ArtMesh_2b13a8de1741fb77", "@2b13a8de1741fb77"),
                        "guid-b", new ProtectedExportPlan.Target("ArtMesh_33ce1b8657a81c6a", "@33ce1b8657a81c6a")),
                plan.artMeshTargets());
    }

    @Test
    void verifiesFamilyPrefixedSettingsTokensFromTheSamePools() {
        final ProtectedExportCensus census = census(
                List.of("guid-a"),
                List.of(),
                Map.of(),
                List.of("phys-1-guid"),
                List.of("ms-1-guid"),
                Set.of(),
                Set.of(),
                Set.of());

        final ProtectedExportPlan plan = admitted(census);

        assertEquals(
                new ProtectedExportPlan.Target("Physics_09714b8d444a5df5", "PhysicsId_09714b8d444a5df5"),
                plan.physicsSettings().get("phys-1-guid"));
        assertEquals(
                new ProtectedExportPlan.Target("MotionSync_2a4c293b9c6af707", "MotionSyncId_2a4c293b9c6af707"),
                plan.motionSyncSettings().get("ms-1-guid"));
    }

    @Test
    void rejectsATokenThatIsNotTheCanonicalAllocation() {
        final ProtectedExportCensus census =
                census(List.of("guid-a"), List.of(), Map.of(), List.of(), List.of(), Set.of(), Set.of(), Set.of());
        final ProtectedExportPlan plan = admitted(census);

        assertRejected(
                "protected-export.obfuscation-token-mismatch",
                new ProtectedExportPlan(
                        plan.deformerOrderLeafToRoot(),
                        Map.of(
                                "guid-a",
                                new ProtectedExportPlan.Target("ArtMesh_deadbeefdeadbeef", "@deadbeefdeadbeef")),
                        plan.physicsSettings(),
                        plan.motionSyncSettings(),
                        plan.passThroughGuids()),
                census);
    }

    @Test
    void rejectsAPlanThatOmitsOrInvendsARewrittenIdentity() {
        final ProtectedExportCensus census =
                census(List.of("guid-a"), List.of(), Map.of(), List.of(), List.of(), Set.of(), Set.of(), Set.of());
        final ProtectedExportPlan plan = admitted(census);

        assertRejected(
                "protected-export.plan-obfuscation-identities",
                new ProtectedExportPlan(
                        plan.deformerOrderLeafToRoot(),
                        Map.of(),
                        plan.physicsSettings(),
                        plan.motionSyncSettings(),
                        plan.passThroughGuids()),
                census);
        assertRejected(
                "protected-export.plan-obfuscation-identities",
                new ProtectedExportPlan(
                        plan.deformerOrderLeafToRoot(),
                        Map.of(
                                "guid-a",
                                plan.artMeshTargets().get("guid-a"),
                                "guid-b",
                                new ProtectedExportPlan.Target("ArtMesh_0123456789abcdef", "@0123456789abcdef")),
                        plan.physicsSettings(),
                        plan.motionSyncSettings(),
                        plan.passThroughGuids()),
                census);
    }

    @Test
    void rejectsAReservedNameCollisionThatThePlannerShouldHaveAvoided() {
        // The planner ignores the reserved pool and hands out the identity's own name
        // prefix: the runtime recomputes the allocation and refuses the plan.
        final ProtectedExportCensus census = census(
                List.of("guid-a"),
                List.of(),
                Map.of(),
                List.of(),
                List.of(),
                Set.of(),
                Set.of("ArtMesh_2b13a8de1741fb77"),
                Set.of());

        final ProtectedExportPlan plan = admitted(census);
        assertEquals(
                new ProtectedExportPlan.Target("ArtMesh_2b13a8de1741fb778", "@2b13a8de1741fb778"),
                plan.artMeshTargets().get("guid-a"));
        assertRejected(
                "protected-export.obfuscation-token-mismatch",
                new ProtectedExportPlan(
                        List.of(),
                        Map.of(
                                "guid-a",
                                new ProtectedExportPlan.Target("ArtMesh_2b13a8de1741fb77", "@2b13a8de1741fb77")),
                        Map.of(),
                        Map.of(),
                        Set.of()),
                census);
    }

    @Test
    void rejectsADeformerOrderThatAppliesAParentBeforeItsChild() {
        final Map<String, String> parents = new LinkedHashMap<>();
        parents.put("g-leaf", "g-root");
        final ProtectedExportCensus census = census(
                List.of(), List.of("g-leaf", "g-root"), parents, List.of(), List.of(), Set.of(), Set.of(), Set.of());

        assertRejected(
                "protected-export.deformer-cycle",
                new ProtectedExportPlan(List.of("g-root", "g-leaf"), Map.of(), Map.of(), Map.of(), Set.of()),
                census);
    }

    @Test
    void rejectsAnOrderThatIsNotTheDeformerCensusOnceEach() {
        final ProtectedExportCensus census = census(
                List.of(), List.of("g-root", "g-other"), Map.of(), List.of(), List.of(), Set.of(), Set.of(), Set.of());

        assertRejected(
                "protected-export.plan-deformer-order",
                new ProtectedExportPlan(List.of("g-root"), Map.of(), Map.of(), Map.of(), Set.of()),
                census);
        assertRejected(
                "protected-export.plan-deformer-order",
                new ProtectedExportPlan(List.of("g-root", "g-foreign"), Map.of(), Map.of(), Map.of(), Set.of()),
                census);
    }

    @Test
    void rejectsAPassThroughSetThatDisagreesWithTheCensus() {
        final ProtectedExportCensus census =
                census(List.of(), List.of(), Map.of(), List.of(), List.of(), Set.of("glue-1-guid"), Set.of(), Set.of());

        assertRejected(
                "protected-export.plan-pass-through-guids",
                new ProtectedExportPlan(List.of(), Map.of(), Map.of(), Map.of(), Set.of()),
                census);
    }

    @Test
    void planTypeCarriesNoWriteVerb() {
        // A plan authorizes nothing: it exposes copied identities only, so no host write,
        // open, save or export can be reached from it.
        final List<String> members = java.util.Arrays.stream(ProtectedExportPlan.class.getMethods())
                .filter(method -> method.getDeclaringClass() == ProtectedExportPlan.class)
                .map(method -> method.getName())
                .toList();
        assertTrue(
                members.stream()
                        .allMatch(name -> name.startsWith("get")
                                || name.equals("equals")
                                || name.equals("hashCode")
                                || name.equals("toString")
                                || name.equals("deformerOrderLeafToRoot")
                                || name.equals("artMeshTargets")
                                || name.equals("physicsSettings")
                                || name.equals("motionSyncSettings")
                                || name.equals("passThroughGuids")),
                "unexpected plan member: " + members);
    }

    private static ProtectedExportPlan admitted(final ProtectedExportCensus census) {
        final ProtectedExportPlanResult result = CANONICAL_PLANNER.plan(census);
        assertTrue(result instanceof ProtectedExportPlanResult.Admitted, "expected an admitted plan: " + result);
        final ProtectedExportPlan plan = ((ProtectedExportPlanResult.Admitted) result).plan();
        ProtectedExportPlanAdmission.verify(plan, census);
        return plan;
    }

    private static void assertRejected(
            final String expectedKey, final ProtectedExportPlan plan, final ProtectedExportCensus census) {
        final ProtectedExportRejection rejection =
                assertThrows(ProtectedExportRejection.class, () -> ProtectedExportPlanAdmission.verify(plan, census));
        assertEquals(expectedKey, rejection.getMessage());
    }

    private static ProtectedExportCensus census(
            final List<String> artMeshes,
            final List<String> deformers,
            final Map<String, String> parents,
            final List<String> physics,
            final List<String> motionSync,
            final Set<String> passThrough,
            final Set<String> reservedNames,
            final Set<String> reservedIds) {
        return new ProtectedExportCensus(
                deformers, parents, artMeshes, physics, motionSync, passThrough, reservedNames, reservedIds);
    }
}
