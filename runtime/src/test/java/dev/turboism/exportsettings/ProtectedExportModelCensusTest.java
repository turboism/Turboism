package dev.turboism.exportsettings;

import static dev.turboism.exportsettings.ProtectedExportTestFixtures.FakeArtMesh;
import static dev.turboism.exportsettings.ProtectedExportTestFixtures.FakeGlue;
import static dev.turboism.exportsettings.ProtectedExportTestFixtures.FakeHost;
import static dev.turboism.exportsettings.ProtectedExportTestFixtures.FakeMotionSyncSettings;
import static dev.turboism.exportsettings.ProtectedExportTestFixtures.FakeParameter;
import static dev.turboism.exportsettings.ProtectedExportTestFixtures.FakePassThrough;
import static dev.turboism.exportsettings.ProtectedExportTestFixtures.FakePhysicsSettings;
import static dev.turboism.exportsettings.ProtectedExportTestFixtures.FakeUnsupported;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.sdk.cubism.export.ProtectedExportPlan;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Direct vectors for the identity census and its post-mutation drift enforcement: the
 * snapshot a plan is verified against, the bounded rejections for identities that cannot
 * be pinned, and every drift family the post-mutation comparison must fail closed on.
 *
 * <p>These cases drive the census without the orchestrator, so a failure isolates the
 * comparison rule instead of a session phase: authored identities are snapshotted, the
 * allowed mutation set is applied, and each unauthorized drift is asserted one at a
 * time.</p>
 */
class ProtectedExportModelCensusTest {

    private static final String FAILURE_KEY = ProtectedExportOrchestrator.OBFUSCATE_FAILED_KEY;

    /** Planned ArtMesh tokens matching {@link #renameArtMeshesToPlan}'s rewrite. */
    private static final Map<String, ProtectedExportPlan.Target> ART_MESH_TOKENS = Map.of(
            "m-a-guid", new ProtectedExportPlan.Target("ArtMesh_a", "ArtMeshId_a"),
            "m-b-guid", new ProtectedExportPlan.Target("ArtMesh_b", "ArtMeshId_b"));

    private static ProtectedExportPlan plan(
            final List<String> deformerOrder,
            final Map<String, ProtectedExportPlan.Target> artMeshes,
            final Map<String, ProtectedExportPlan.Target> physics) {
        return new ProtectedExportPlan(deformerOrder, artMeshes, physics, Map.of(), Set.of());
    }

    /** Plan that rewrites the fixture's two ArtMeshes and nothing else. */
    private static ProtectedExportPlan artMeshPlan() {
        return plan(List.of(), ART_MESH_TOKENS, Map.of());
    }

    /** Obfuscation step for the ArtMesh channel — always exactly the planned tokens. */
    private static void renameArtMeshesToPlan(final FakeHost host) {
        host.original.model.artMeshes.get(0).name = "ArtMesh_a";
        host.original.model.artMeshes.get(0).drawableId = "ArtMeshId_a";
        host.original.model.artMeshes.get(1).name = "ArtMesh_b";
        host.original.model.artMeshes.get(1).drawableId = "ArtMeshId_b";
    }

    private static SessionRejection rejectionOf(
            final ProtectedExportModelCensus census,
            final ProtectedExportModelCensus.ModelCensus before,
            final ProtectedExportModelCensus.ModelCensus after,
            final ProtectedExportPlan plan) {
        return assertThrows(SessionRejection.class, () -> census.verifyPostMutationCensus(before, after, plan));
    }

    private static String driftDetail(
            final ProtectedExportModelCensus census,
            final FakeHost host,
            final ProtectedExportModelCensus.ModelCensus before,
            final ProtectedExportPlan plan) {
        final SessionRejection rejection =
                rejectionOf(census, before, census.censusModel(host.original.model, FAILURE_KEY), plan);
        assertEquals(FAILURE_KEY, rejection.failureKey);
        return rejection.detail;
    }

    @Test
    void snapshotsEveryModelIdentityBeforeMutation() {
        final FakeHost host = new FakeHost();
        host.original.model.glues.add(new FakeGlue(
                "glue-1-guid",
                "Glue1",
                "glue-one",
                host.original.model.artMeshes.get(0),
                host.original.model.artMeshes.get(1)));
        host.original.model.passThrough.add(
                new FakePassThrough("art-path", "ap-1-guid", "ArtPath1", "art-path-one", List.of("m-a-guid")));
        host.original.model.physicsSettings.add(new FakePhysicsSettings("phys-1-guid", "hairPhys", "hair"));
        host.original.model.motionSyncSettings.add(new FakeMotionSyncSettings("ms-1-guid", "MotionSync1", "voice", 7));
        final ProtectedExportModelCensus census = new ProtectedExportModelCensus(host);

        final ProtectedExportModelCensus.ModelCensus snapshot = census.censusModel(host.original.model, FAILURE_KEY);

        // The synthetic root part is never serialized, so it never enters the census.
        assertEquals(Set.of("part-1-guid"), snapshot.parts().keySet());
        final ProtectedExportModelCensus.PartIdentity part = snapshot.parts().get("part-1-guid");
        assertEquals("part-1", part.id());
        assertEquals("part-name-part-1", part.name());
        assertEquals(List.of("m-a-guid", "m-b-guid", "g-leaf", "g-root", "g-unbound"), part.childGuids());
        assertNull(part.targetDeformerGuid());
        assertEquals(List.of(), part.referenceGuids());
        assertEquals(List.of(), part.flags());

        // Parameter identities carry the evaluable contract plus the authored key union.
        assertEquals(Set.of("param-1"), snapshot.parameters().keySet());
        final ProtectedExportModelCensus.ParameterIdentity parameter =
                snapshot.parameters().get("param-1");
        assertEquals(0f, parameter.min());
        assertEquals(1f, parameter.max());
        assertEquals(0f, parameter.defaultValue());
        assertEquals(Boolean.FALSE, parameter.repeat());
        assertEquals(List.of(0f, 0.5f, 1f), parameter.keys());

        assertEquals(Set.of("m-a-guid", "m-b-guid"), snapshot.artMeshes().keySet());
        assertEquals("meshA", snapshot.artMeshes().get("m-a-guid").name());
        assertEquals("id-a", snapshot.artMeshes().get("m-a-guid").drawableId());

        // Glue and art-path ride untouched; both are pinned with family and references.
        assertEquals(Set.of("glue-1-guid", "ap-1-guid"), snapshot.passThrough().keySet());
        assertEquals("glue", snapshot.passThrough().get("glue-1-guid").family());
        assertEquals(
                List.of("m-a-guid", "m-b-guid"),
                snapshot.passThrough().get("glue-1-guid").referenceGuids());
        assertEquals("art-path", snapshot.passThrough().get("ap-1-guid").family());
        assertEquals(
                List.of("m-a-guid"), snapshot.passThrough().get("ap-1-guid").referenceGuids());

        assertEquals(Set.of("phys-1-guid"), snapshot.physicsSettings().keySet());
        assertEquals("hairPhys", snapshot.physicsSettings().get("phys-1-guid").id());
        assertEquals(Set.of("ms-1-guid"), snapshot.motionSyncSettings().keySet());
        assertTrue(
                snapshot.physicsSettings().get("phys-1-guid").signature().contains("totalAngle=90.0"),
                "the settings signature pins behavior content: "
                        + snapshot.physicsSettings().get("phys-1-guid").signature());
        assertEquals(
                List.of("gravity=0.0,-1.0", "wind=0.0,0.0", "fps=60", "selected=null"), snapshot.physicsSetSignature());
        assertEquals(List.of(), snapshot.featureFlags());
        assertTrue(snapshot.embeddedContent()
                .keySet()
                .containsAll(snapshot.passThrough().keySet()));

        // The staged contracts are projections of the same snapshot.
        assertEquals(
                new ProtectedExportStaging.ParameterExpectation("param-1", 0f, 1f, 0f, false, List.of(0f, 0.5f, 1f)),
                census.parameterExpectations(snapshot).get("param-1"));
        assertEquals(Set.of("part-1"), census.partIdSet(snapshot));
        assertEquals(Set.of("Glue1"), census.glueIdSet(snapshot));
    }

    @Test
    void rejectsDuplicateGuidsAcrossCensusFamilies() {
        final FakeHost host = new FakeHost();
        // An art-path colliding with an ArtMesh GUID makes the pinned identity ambiguous.
        host.original.model.passThrough.add(new FakePassThrough("art-path", "m-a-guid", "ap-1", "artPath", List.of()));
        final ProtectedExportModelCensus census = new ProtectedExportModelCensus(host);

        final SessionRejection rejection =
                assertThrows(SessionRejection.class, () -> census.censusModel(host.original.model, FAILURE_KEY));

        assertEquals(FAILURE_KEY, rejection.failureKey);
        assertEquals("protected-export.duplicate-guid:m-a-guid", rejection.detail);
    }

    @Test
    void rejectsDuplicateGuidBetweenArtMeshAndPart() {
        final FakeHost host = new FakeHost();
        host.original.model.artMeshes.add(new FakeArtMesh("part-1-guid", "meshC", "id-c"));
        final ProtectedExportModelCensus census = new ProtectedExportModelCensus(host);

        final SessionRejection rejection =
                assertThrows(SessionRejection.class, () -> census.censusModel(host.original.model, FAILURE_KEY));

        assertEquals("protected-export.duplicate-guid:part-1-guid", rejection.detail);
    }

    @Test
    void rejectsMembersWithoutPinnableIdentityByFamilyCount() {
        final FakeHost host = new FakeHost();
        host.original.model.unpinnableObjects.add(new FakeUnsupported("art-path"));
        host.original.model.unpinnableObjects.add(new Object());
        final ProtectedExportModelCensus census = new ProtectedExportModelCensus(host);

        final SessionRejection rejection = assertThrows(
                SessionRejection.class,
                () -> census.censusModel(host.original.model, ProtectedExportOrchestrator.PREFLIGHT_FAILED_KEY));

        // The rejection keeps the caller's phase key and only counts the unpinnable members.
        assertEquals(ProtectedExportOrchestrator.PREFLIGHT_FAILED_KEY, rejection.failureKey);
        assertEquals("protected-export.unpinnable-structure:art-path=1,unknown=1", rejection.detail);
    }

    @Test
    void rejectsSettingsThatCannotBePinned() {
        final FakeHost host = new FakeHost();
        host.original.model.physicsSettings.add(new FakePhysicsSettings("", "hairPhys", "hair"));
        final ProtectedExportModelCensus census = new ProtectedExportModelCensus(host);

        final SessionRejection rejection =
                assertThrows(SessionRejection.class, () -> census.censusModel(host.original.model, FAILURE_KEY));

        assertEquals("protected-export.unpinnable-settings:physics", rejection.detail);
    }

    @Test
    void rejectsGlueReferencesThatDoNotResolveToACensusedArtMesh() {
        final FakeHost host = new FakeHost();
        // A Glue target that stopped resolving is corrupt input, not a pass-through case.
        host.original.model.glues.add(
                new FakeGlue("glue-1-guid", "Glue1", "glue-one", host.original.model.artMeshes.get(0), null));
        final ProtectedExportModelCensus census = new ProtectedExportModelCensus(host);

        final SessionRejection rejection =
                assertThrows(SessionRejection.class, () -> census.censusModel(host.original.model, FAILURE_KEY));

        assertEquals("glue-reference-drift", rejection.detail);
    }

    @Test
    void acceptsThePlannedMutationSetOnly() {
        final FakeHost host = new FakeHost();
        final FakePassThrough member =
                new FakePassThrough("art-path", "ap-1-guid", "ArtPath1", "art-path-one", List.of());
        member.targetDeformerGuid = "g-leaf";
        host.original.model.passThrough.add(member);
        final FakePhysicsSettings physics = new FakePhysicsSettings("phys-1-guid", "hairPhys", "hair");
        host.original.model.physicsSettings.add(physics);
        final ProtectedExportModelCensus census = new ProtectedExportModelCensus(host);
        final ProtectedExportModelCensus.ModelCensus before = census.censusModel(host.original.model, FAILURE_KEY);
        final ProtectedExportPlan plan = plan(
                List.of("g-leaf"),
                ART_MESH_TOKENS,
                Map.of("phys-1-guid", new ProtectedExportPlan.Target("Physics_1", "PhysicsId_1")));

        // Exactly what flatten and obfuscation may do: drop the planned deformer from
        // part membership, re-root a pass-through parent edge to null (the only
        // permitted parent transition), rewrite the planned identities, keep every
        // behavior signature.
        host.original.model.parts.get(0).childGuids.remove("g-leaf");
        member.targetDeformerGuid = null;
        renameArtMeshesToPlan(host);
        physics.id = "PhysicsId_1";
        physics.name = "Physics_1";
        final ProtectedExportModelCensus.ModelCensus after = census.censusModel(host.original.model, FAILURE_KEY);

        census.verifyPostMutationCensus(before, after, plan);

        assertEquals(
                new ProtectedExportStaging.PhysicsSettingExpectation(
                        "PhysicsId_1",
                        "Physics_1",
                        after.physicsSettings().get("phys-1-guid").signature()),
                census.physicsExpectations(after, plan).get(0));
    }

    @Test
    void rejectsParameterIdentityDrift() {
        final FakeHost host = new FakeHost();
        final ProtectedExportModelCensus census = new ProtectedExportModelCensus(host);
        final ProtectedExportModelCensus.ModelCensus before = census.censusModel(host.original.model, FAILURE_KEY);
        renameArtMeshesToPlan(host);

        // A parameter that left the census is a contract loss, not a rewrite.
        host.original.model.parameters.clear();
        assertEquals("parameter-identity-drift", driftDetail(census, host, before, artMeshPlan()));

        // A same-ID parameter whose evaluable range moved is equally unrecoverable.
        host.original.model.parameters.add(new FakeParameter("param-1", 0f, 2f, 0f, false));
        assertEquals("parameter-identity-drift", driftDetail(census, host, before, artMeshPlan()));
    }

    @Test
    void rejectsPartIdentityAndHierarchyDrift() {
        final FakeHost host = new FakeHost();
        final ProtectedExportModelCensus census = new ProtectedExportModelCensus(host);
        final ProtectedExportModelCensus.ModelCensus before = census.censusModel(host.original.model, FAILURE_KEY);
        renameArtMeshesToPlan(host);

        host.original.model.parts.get(0).name = "renamed-part";
        assertEquals("part-identity-drift", driftDetail(census, host, before, artMeshPlan()));

        // A child link the flatten order does not cover may never disappear or move.
        host.original.model.parts.get(0).name = "part-name-part-1";
        host.original.model.parts.get(0).childGuids.add("m-b-guid");
        assertEquals("part-hierarchy-drift", driftDetail(census, host, before, artMeshPlan()));
    }

    @Test
    void rejectsArtMeshIdentityDriftAgainstThePlan() {
        final FakeHost host = new FakeHost();
        final ProtectedExportModelCensus census = new ProtectedExportModelCensus(host);
        final ProtectedExportModelCensus.ModelCensus before = census.censusModel(host.original.model, FAILURE_KEY);
        renameArtMeshesToPlan(host);
        final ProtectedExportModelCensus.ModelCensus after = census.censusModel(host.original.model, FAILURE_KEY);

        // A mesh the plan does not pin rejects even when its identity is unchanged.
        final ProtectedExportPlan partial =
                plan(List.of(), Map.of("m-a-guid", ART_MESH_TOKENS.get("m-a-guid")), Map.of());
        assertEquals("artmesh-identity-drift", rejectionOf(census, before, after, partial).detail);

        // A planned token the copy does not carry is the same drift.
        host.original.model.artMeshes.get(1).drawableId = "id-b";
        assertEquals("artmesh-identity-drift", driftDetail(census, host, before, artMeshPlan()));
    }

    @Test
    void rejectsPassThroughIdentityParentReferenceAndFlagDrift() {
        final FakeHost host = new FakeHost();
        final FakePassThrough member =
                new FakePassThrough("alias", "al-1-guid", "Alias1", "alias-one", List.of("m-a-guid"));
        member.targetDeformerGuid = "g-leaf";
        host.original.model.passThrough.add(member);
        final ProtectedExportModelCensus census = new ProtectedExportModelCensus(host);
        final ProtectedExportModelCensus.ModelCensus before = census.censusModel(host.original.model, FAILURE_KEY);
        renameArtMeshesToPlan(host);

        member.name = "renamed";
        assertEquals("pass-through-identity-drift", driftDetail(census, host, before, artMeshPlan()));
        member.name = "alias-one";

        // A re-rooted parent is allowed only towards null; any other target is drift.
        member.targetDeformerGuid = "g-root";
        assertEquals("pass-through-parent-drift", driftDetail(census, host, before, artMeshPlan()));
        member.targetDeformerGuid = null;

        member.referenceGuids.add("m-b-guid");
        assertEquals("pass-through-reference-drift", driftDetail(census, host, before, artMeshPlan()));
        member.referenceGuids.remove("m-b-guid");

        member.flags.add("clip");
        assertEquals("pass-through-flag-drift", driftDetail(census, host, before, artMeshPlan()));
    }

    @Test
    void rejectsPhysicsSettingsContentDriftAndUnplannedSettings() {
        final FakeHost host = new FakeHost();
        final FakePhysicsSettings physics = new FakePhysicsSettings("phys-1-guid", "hairPhys", "hair");
        host.original.model.physicsSettings.add(physics);
        final ProtectedExportModelCensus census = new ProtectedExportModelCensus(host);
        final ProtectedExportModelCensus.ModelCensus before = census.censusModel(host.original.model, FAILURE_KEY);
        renameArtMeshesToPlan(host);
        final ProtectedExportPlan plan = plan(
                List.of(),
                ART_MESH_TOKENS,
                Map.of("phys-1-guid", new ProtectedExportPlan.Target("Physics_1", "PhysicsId_1")));

        // An obfuscated identity that is not the planned token is drift.
        assertEquals("settings-identity-drift", driftDetail(census, host, before, plan));

        // The planned tokens plus a rewritten behavior value are still drift: every
        // physics input/output/vertex member is pinned, not only the identity.
        physics.id = "PhysicsId_1";
        physics.name = "Physics_1";
        physics.totalAngle = 45f;
        assertEquals("settings-identity-drift", driftDetail(census, host, before, plan));

        // A settings entry the plan does not cover can never be obfuscated consistently.
        physics.totalAngle = 90f;
        assertEquals("settings-identity-drift", driftDetail(census, host, before, artMeshPlan()));

        // The settings-set pin covers gravity/wind/fps/selection for the whole model.
        physics.id = "hairPhys";
        physics.name = "hair";
        host.original.model.physicsSet.selectedGuid = "m-a-guid";
        assertEquals("settings-identity-drift", driftDetail(census, host, before, plan));
    }

    @Test
    void rejectsMotionSyncSettingsDriftWithoutAPlannedToken() {
        final FakeHost host = new FakeHost();
        final FakeMotionSyncSettings sync = new FakeMotionSyncSettings("ms-1-guid", "MotionSync1", "voice", 7);
        host.original.model.motionSyncSettings.add(sync);
        final ProtectedExportModelCensus census = new ProtectedExportModelCensus(host);
        final ProtectedExportModelCensus.ModelCensus before = census.censusModel(host.original.model, FAILURE_KEY);
        renameArtMeshesToPlan(host);
        sync.id = "MotionSyncId_1";
        sync.name = "MotionSync_1";
        final ProtectedExportModelCensus.ModelCensus after = census.censusModel(host.original.model, FAILURE_KEY);

        // The content signature survives the rename; only the missing plan pin rejects.
        assertEquals(
                before.motionSyncSettings().get("ms-1-guid").signature(),
                after.motionSyncSettings().get("ms-1-guid").signature());
        assertEquals("settings-identity-drift", rejectionOf(census, before, after, artMeshPlan()).detail);
    }

    @Test
    void rejectsEmbeddedContentLossOnSurvivingArtMeshes() {
        final FakeHost host = new FakeHost();
        host.embeddedMorphTargets = true;
        final ProtectedExportModelCensus census = new ProtectedExportModelCensus(host);
        final ProtectedExportModelCensus.ModelCensus before = census.censusModel(host.original.model, FAILURE_KEY);
        assertEquals(
                List.of("keyform-morph-target-set"), before.embeddedContent().get("m-a-guid"));
        renameArtMeshesToPlan(host);

        // Flatten may merge content into an ArtMesh, never drop what it already had.
        host.embeddedMorphTargets = false;
        assertEquals("embedded-content-drift", driftDetail(census, host, before, artMeshPlan()));
    }

    @Test
    void rejectsPostMutationCensusWithoutABoundSnapshot() {
        final FakeHost host = new FakeHost();
        final ProtectedExportModelCensus census = new ProtectedExportModelCensus(host);
        final ProtectedExportModelCensus.ModelCensus snapshot = census.censusModel(host.original.model, FAILURE_KEY);

        final SessionRejection rejection = rejectionOf(census, null, snapshot, artMeshPlan());

        assertEquals(FAILURE_KEY, rejection.failureKey);
        assertNull(rejection.detail);
    }
}
