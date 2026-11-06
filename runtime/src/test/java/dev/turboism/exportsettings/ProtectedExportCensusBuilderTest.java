package dev.turboism.exportsettings;

import static dev.turboism.exportsettings.ProtectedExportTestFixtures.FakeDeformer;
import static dev.turboism.exportsettings.ProtectedExportTestFixtures.FakeHost;
import static dev.turboism.exportsettings.ProtectedExportTestFixtures.FakeMotionSyncSettings;
import static dev.turboism.exportsettings.ProtectedExportTestFixtures.FakePassThrough;
import static dev.turboism.exportsettings.ProtectedExportTestFixtures.FakePhysicsSettings;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.sdk.cubism.export.ProtectedExportCensus;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Census-building vectors: the planner input the runtime hands to the plugin, plus the
 * bounded rejections for a census member that cannot be pinned.
 */
class ProtectedExportCensusBuilderTest {

    @Test
    void censusesDeformersArtMeshesSettingsAndReservedPools() {
        final FakeHost host = new FakeHost();
        host.original.model.physicsSettings.add(new FakePhysicsSettings("phys-1-guid", "hairPhys", "hair"));
        host.original.model.motionSyncSettings.add(new FakeMotionSyncSettings("ms-1-guid", "MotionSync1", "voice", 7));

        final ProtectedExportCensus census = ProtectedExportCensusBuilder.build(host, host.original.model);

        assertEquals(List.of("g-leaf", "g-root", "g-unbound"), census.deformerGuids());
        assertEquals(Map.of("g-leaf", "g-root", "g-unbound", "g-root"), census.deformerParentGuids());
        assertEquals(List.of("m-a-guid", "m-b-guid"), census.artMeshGuids());
        assertEquals(List.of("phys-1-guid"), census.physicsSettingGuids());
        assertEquals(List.of("ms-1-guid"), census.motionSyncSettingGuids());
        assertTrue(census.passThroughGuids().isEmpty());
        // Every authored identity is reserved, so no token can alias something that survives.
        assertTrue(census.reservedSourceNames().containsAll(Set.of("meshA", "meshB", "hair", "voice")));
        assertTrue(census.reservedSourceNames().contains("part-name-part-1"), "part names are reserved too");
        assertTrue(census.reservedSourceIds().containsAll(Set.of("id-a", "id-b", "part-1", "hairPhys", "MotionSync1")));
        assertTrue(census.reservedSourceIds().contains("param-1"));
    }

    @Test
    void censusesPassThroughObjectsWithoutPlanningThemForARewrite() {
        final FakeHost host = new FakeHost();
        host.original.model.passThrough.add(
                new FakePassThrough("alias", "alias-1-guid", "alias-1", "Alias1", List.of("m-a-guid")));

        final ProtectedExportCensus census = ProtectedExportCensusBuilder.build(host, host.original.model);

        assertEquals(Set.of("alias-1-guid"), census.passThroughGuids());
        assertTrue(census.reservedSourceIds().contains("alias-1"));
        assertTrue(census.reservedSourceNames().contains("Alias1"));
    }

    @Test
    void deformerSubCensusResolvesOnlyEdgesInsideTheCensus() {
        final FakeHost host = new FakeHost();
        // A parent edge pointing outside the deformer census is a root, matching the
        // host's own downward-traversal semantics.
        host.original.model.deformers.add(new FakeDeformer("g-dangling", "g-unknown", List.of()));

        final ProtectedExportCensusBuilder.DeformerCensus census =
                ProtectedExportCensusBuilder.deformerCensus(host, host.original.model);

        assertEquals(List.of("g-dangling", "g-leaf", "g-root", "g-unbound"), census.guids());
        assertEquals(Map.of("g-leaf", "g-root", "g-unbound", "g-root"), census.parentGuids());
    }

    @Test
    void rejectsDuplicateDeformerGuids() {
        final FakeHost host = new FakeHost();
        host.duplicateDeformerGuids = true;

        assertRejected("protected-export.deformer-guid-duplicate", host);
    }

    @Test
    void rejectsABlankDeformerGuid() {
        final FakeHost host = new FakeHost();
        host.original.model.deformers.set(0, new FakeDeformer(" ", null));

        assertRejected("protected-export.deformer-guid-missing", host);
    }

    @Test
    void rejectsAnUnsupportedDeformerFamily() {
        assertMeshCensusRejected(
                "protected-export.unsupported-deformer-family", Map.of("allDeformers", List.of(new Object())));
    }

    @Test
    void rejectsABlankOrDuplicateArtMeshGuidAndAnAmbiguousCensus() {
        assertMeshCensusRejected("protected-export.obfuscation-guid-missing", Map.of("objectGuid", " "));
        assertMeshCensusRejected("protected-export.obfuscation-guid-duplicate", Map.of("objectGuid", "same-guid"));
        assertMeshCensusRejected("protected-export.obfuscation-identity-missing", Map.of("objectLocalName", " "));
        assertMeshCensusRejected("protected-export.obfuscation-census-ambiguous", Map.of("isArtMeshSource", false));
    }

    @Test
    void rejectsABlankPassThroughIdentity() {
        final FakeHost host = new FakeHost();
        host.original.model.passThrough.add(new FakePassThrough("alias", " ", "alias-1", "Alias1", List.of()));

        assertRejected("protected-export.obfuscation-pass-through-identity-missing", host);
    }

    @Test
    void rejectsDuplicateSettingsGuidsAndBlankSettingsIdentities() {
        final FakeHost duplicate = new FakeHost();
        duplicate.original.model.physicsSettings.add(new FakePhysicsSettings("phys-1-guid", "hairPhys", "hair"));
        duplicate.original.model.physicsSettings.add(new FakePhysicsSettings("phys-1-guid", "hairPhys2", "hair2"));
        assertRejected("protected-export.obfuscation-guid-duplicate", duplicate);

        final FakeHost blankId = new FakeHost();
        blankId.original.model.physicsSettings.add(new FakePhysicsSettings("phys-1-guid", " ", "hair"));
        assertRejected("protected-export.obfuscation-identity-missing", blankId);

        final FakeHost blankName = new FakeHost();
        blankName.original.model.motionSyncSettings.add(new FakeMotionSyncSettings("ms-1-guid", "MotionSync1", " ", 7));
        assertRejected("protected-export.obfuscation-identity-missing", blankName);
    }

    private static void assertRejected(final String expectedKey, final FakeHost host) {
        final ProtectedExportRejection rejection = assertThrows(
                ProtectedExportRejection.class, () -> ProtectedExportCensusBuilder.build(host, host.original.model));
        assertEquals(expectedKey, rejection.getMessage());
    }

    private static void assertMeshCensusRejected(final String expectedKey, final Map<String, Object> answers) {
        final ProtectedExportHostOperations host = meshHost(answers);
        final Object modelSource = new Object();
        final ProtectedExportRejection rejection = assertThrows(
                ProtectedExportRejection.class, () -> ProtectedExportCensusBuilder.build(host, modelSource));
        assertEquals(expectedKey, rejection.getMessage());
    }

    /**
     * Minimal census host over a two-ArtMesh model. Each mesh object is its own GUID, and
     * {@code answers} pins one accessor so a single accessor can be made to answer a blank
     * or duplicated value without a bespoke fake per case.
     */
    private static ProtectedExportHostOperations meshHost(final Map<String, Object> answers) {
        final List<String> meshes = List.of("art-1", "art-2");
        return (ProtectedExportHostOperations) Proxy.newProxyInstance(
                ProtectedExportCensusBuilderTest.class.getClassLoader(),
                new Class<?>[] {ProtectedExportHostOperations.class},
                (proxy, method, args) -> {
                    if (answers.containsKey(method.getName())) {
                        return answers.get(method.getName());
                    }
                    switch (method.getName()) {
                        case "equals":
                            return proxy == args[0];
                        case "hashCode":
                            return System.identityHashCode(proxy);
                        case "toString":
                            return "census-mesh-host";
                        case "allDeformers",
                                "allObjects",
                                "allParameters",
                                "allPhysicsSettings",
                                "allMotionSyncSettings":
                            return List.of();
                        case "allArtMeshes":
                            return meshes;
                        case "isArtMeshSource":
                            return Boolean.TRUE;
                        case "objectGuid":
                            return args[0];
                        case "objectLocalName":
                            return "name-" + args[0];
                        case "drawableIdString":
                            return "id-" + args[0];
                        default:
                            break;
                    }
                    return switch (method.getReturnType().getName()) {
                        case "boolean" -> Boolean.FALSE;
                        case "int", "float", "long" -> 0;
                        default -> null;
                    };
                });
    }
}
