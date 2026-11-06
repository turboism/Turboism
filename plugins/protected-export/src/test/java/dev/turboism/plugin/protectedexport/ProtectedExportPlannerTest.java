package dev.turboism.plugin.protectedexport;

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
 * Token and order vectors for the plugin's single census-driven planner.
 *
 * <p>The expected tokens are SHA-256 golden values, so these cases pin the exact scheme the
 * runtime's {@code ProtectedExportPlanAdmission} verifies rather than only its shape.</p>
 */
class ProtectedExportPlannerTest {

    private final ProtectedExportPlanner planner = new ProtectedExportPlanner();

    @Test
    void plansArtMeshTokensFromTheCensusGuidHash() {
        final ProtectedExportPlan plan = admit(census(
                List.of("guid-a", "guid-b"),
                List.of(),
                Map.of(),
                List.of(),
                List.of(),
                Set.of(),
                Set.of("meshA", "meshB", "id-a", "id-b"),
                Set.of("meshA", "meshB", "id-a", "id-b")));

        assertEquals(
                Map.of(
                        "guid-a", new ProtectedExportPlan.Target("ArtMesh_2b13a8de1741fb77", "@2b13a8de1741fb77"),
                        "guid-b", new ProtectedExportPlan.Target("ArtMesh_33ce1b8657a81c6a", "@33ce1b8657a81c6a")),
                plan.artMeshTargets());
        assertTrue(plan.deformerOrderLeafToRoot().isEmpty());
        assertTrue(plan.physicsSettings().isEmpty());
        assertTrue(plan.motionSyncSettings().isEmpty());
    }

    @Test
    void lengthensPrefixesPastReservedIdentitiesAndOwnNames() {
        // The identity's own name reserves "ArtMesh_<hash16>", so the allocation lengthens
        // until the pair collides with nothing — a rewrite must always be a real change.
        final ProtectedExportPlan plan = admit(census(
                List.of("guid-a", "guid-b"),
                List.of(),
                Map.of(),
                List.of(),
                List.of(),
                Set.of(),
                Set.of("ArtMesh_2b13a8de1741fb77"),
                Set.of()));

        assertEquals(
                new ProtectedExportPlan.Target("ArtMesh_2b13a8de1741fb778", "@2b13a8de1741fb778"),
                plan.artMeshTargets().get("guid-a"));
        assertEquals(
                new ProtectedExportPlan.Target("ArtMesh_33ce1b8657a81c6a", "@33ce1b8657a81c6a"),
                plan.artMeshTargets().get("guid-b"));
    }

    @Test
    void reservesAnIdTokenAsWellAsAName() {
        final ProtectedExportPlan plan = admit(census(
                List.of("guid-a"),
                List.of(),
                Map.of(),
                List.of(),
                List.of(),
                Set.of(),
                Set.of(),
                Set.of("@2b13a8de1741fb77")));

        assertEquals(
                new ProtectedExportPlan.Target("ArtMesh_2b13a8de1741fb778", "@2b13a8de1741fb778"),
                plan.artMeshTargets().get("guid-a"));
    }

    @Test
    void ordersDeformersLeafToRootAndStartsFromEveryRoot() {
        final Map<String, String> parents = new LinkedHashMap<>();
        parents.put("g-leaf", "g-root");
        parents.put("g-middle", "g-root");

        final ProtectedExportPlan plan = admit(census(
                List.of(),
                List.of("g-root", "g-leaf", "g-middle"),
                parents,
                List.of(),
                List.of(),
                Set.of(),
                Set.of(),
                Set.of()));

        assertEquals(List.of("g-leaf", "g-middle", "g-root"), plan.deformerOrderLeafToRoot());
    }

    @Test
    void rejectsADeformerCycleInsteadOfTruncatingTheOrder() {
        final Map<String, String> parents = new LinkedHashMap<>();
        parents.put("g-leaf", "g-root");
        parents.put("g-root", "g-leaf");

        final ProtectedExportPlanResult result = planner.plan(census(
                List.of(), List.of("g-root", "g-leaf"), parents, List.of(), List.of(), Set.of(), Set.of(), Set.of()));

        assertEquals(new ProtectedExportPlanResult.Rejected("protected-export.deformer-cycle"), result);
    }

    @Test
    void plansFamilyPrefixedSettingsTokensFromTheSamePools() {
        final ProtectedExportPlan plan = admit(census(
                List.of("guid-a"),
                List.of(),
                Map.of(),
                List.of("phys-1-guid"),
                List.of("ms-1-guid"),
                Set.of(),
                Set.of(),
                Set.of()));

        assertEquals(
                new ProtectedExportPlan.Target("ArtMesh_2b13a8de1741fb77", "@2b13a8de1741fb77"),
                plan.artMeshTargets().get("guid-a"));
        assertEquals(
                new ProtectedExportPlan.Target("Physics_09714b8d444a5df5", "PhysicsId_09714b8d444a5df5"),
                plan.physicsSettings().get("phys-1-guid"));
        assertEquals(
                new ProtectedExportPlan.Target("MotionSync_2a4c293b9c6af707", "MotionSyncId_2a4c293b9c6af707"),
                plan.motionSyncSettings().get("ms-1-guid"));
    }

    @Test
    void carriesPassThroughGuidsVerbatimAndNeverRewritesThem() {
        final ProtectedExportPlan plan = admit(census(
                List.of("guid-a"),
                List.of(),
                Map.of(),
                List.of(),
                List.of(),
                Set.of("glue-1-guid", "path-1-guid"),
                Set.of("glueOne"),
                Set.of("glue-one")));

        assertEquals(Set.of("glue-1-guid", "path-1-guid"), plan.passThroughGuids());
        assertTrue(!plan.artMeshTargets().containsKey("glue-1-guid"));
    }

    @Test
    void rejectsAnUnallocatableIdentity() {
        // Every candidate name from the 16-character floor up to the ID-token ceiling is
        // reserved, so no prefix can be allocated and the census must be refused.
        final String hash = "2b13a8de1741fb778d7e733e7a888088cb578d22e5ec6d40e0a88f82d4829cbd";
        final Set<String> blocked = new java.util.LinkedHashSet<>();
        for (int length = 16; length <= 62; length++) {
            blocked.add("ArtMesh_" + hash.substring(0, length));
        }

        final ProtectedExportPlanResult result = planner.plan(census(
                List.of("guid-a"), List.of(), Map.of(), List.of(), List.of(), Set.of(), Set.copyOf(blocked), Set.of()));

        assertEquals(new ProtectedExportPlanResult.Rejected("protected-export.obfuscation-unallocatable"), result);
    }

    @Test
    void plannerInputIsReadOnlyAndCensusIsNotMutated() {
        final ProtectedExportCensus census =
                census(List.of("guid-a"), List.of(), Map.of(), List.of(), List.of(), Set.of(), Set.of(), Set.of());

        planner.plan(census);

        assertEquals(List.of("guid-a"), census.artMeshGuids());
        assertThrows(
                UnsupportedOperationException.class, () -> census.artMeshGuids().add("guid-b"));
    }

    @Test
    void planResultRecordsRejectMalformedReasonKeys() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new ProtectedExportPlanResult.Rejected("Protected export unavailable"));
        assertThrows(IllegalArgumentException.class, () -> new ProtectedExportPlan.Target("", "@token"));
        assertThrows(IllegalArgumentException.class, () -> new ProtectedExportPlan.Target("token", "1digit"));
    }

    private ProtectedExportPlan admit(final ProtectedExportCensus census) {
        final ProtectedExportPlanResult result = planner.plan(census);
        assertTrue(result instanceof ProtectedExportPlanResult.Admitted, "expected an admitted plan but got " + result);
        return ((ProtectedExportPlanResult.Admitted) result).plan();
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
