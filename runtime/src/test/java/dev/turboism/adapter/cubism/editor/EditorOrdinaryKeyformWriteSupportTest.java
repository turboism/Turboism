package dev.turboism.adapter.cubism.editor;

import static org.junit.jupiter.api.Assertions.*;

import dev.turboism.mapping.verification.*;
import dev.turboism.mapping.verification.selector.*;
import dev.turboism.sdk.cubism.model.OrdinaryKeyformWriteSupport.Check;
import dev.turboism.sdk.cubism.model.OrdinaryKeyformWriteSupport.Operation;
import java.util.*;
import org.junit.jupiter.api.Test;

class EditorOrdinaryKeyformWriteSupportTest {
    @Test
    void reportsEachWriterAndInfrastructureWithoutLoadingHostClasses() {
        for (var kind : EditorObjectReadCore.Kind.values()) {
            var result = EditorOrdinaryKeyformWriteSupport.read(resolver("5.3.02", "", "", false), kind, true);
            assertEquals("5.3.02", result.hostVersion());
            assertEquals(Check.PRESENT, result.reviewedHost());
            assertEquals(Check.PRESENT, result.writerSelectors());
            assertEquals(Check.PRESENT, result.historySelectors());
            assertEquals(Check.PRESENT, result.transactionCoordinator());
            assertEquals(Check.UNKNOWN, result.writePermission());
            assertEquals(
                    Check.ABSENT,
                    EditorOrdinaryKeyformWriteSupport.read(resolver("5.3.02", "", "", false), kind, false)
                            .transactionCoordinator());
        }
    }

    @Test
    void missingAliasesCapabilitiesAndUnreviewedHostsRemainDistinct() {
        for (var kind : EditorObjectReadCore.Kind.values()) {
            for (String alias : aliases(kind)) {
                assertEquals(
                        Check.ABSENT,
                        EditorOrdinaryKeyformWriteSupport.read(resolver("5.3.02", alias, "", false), kind, true)
                                .writerSelectors(),
                        alias);
            }
            assertEquals(
                    Check.ABSENT,
                    EditorOrdinaryKeyformWriteSupport.read(resolver("5.4.00", "", "", false), kind, true)
                            .reviewedHost());
            assertEquals(
                    Check.ABSENT,
                    EditorOrdinaryKeyformWriteSupport.read(resolver("5.3.02", "", "", true), kind, true)
                            .reviewedHost());
            var noHistory = EditorOrdinaryKeyformWriteSupport.read(
                    resolver("5.3.02", "", "cubism.editor-history.read", false), kind, true);
            assertEquals(Check.ABSENT, noHistory.historySelectors());
        }
    }

    @Test
    void opacitySelectorObservationIsIndependentOfGeometryAndDoesNotLoadNativeMembers() {
        var all = EditorOrdinaryKeyformWriteSupport.read(
                resolver("5.3.02", "", "", false), EditorObjectReadCore.Kind.ART_MESH, true);
        assertEquals(Check.PRESENT, all.selectorsFor(Operation.ART_MESH_VALUES_WITH_OPACITY));
        assertEquals(Check.PRESENT, all.selectorsFor(Operation.ART_MESH_POSITIONS));
        assertEquals(Check.UNKNOWN, all.selectorsFor(Operation.WARP_CONTROL_POINTS));
        for (String missing :
                List.of("cubism.editor-model.drawable-form.opacity", "cubism.editor-model.drawable-form.set-opacity")) {
            var absent = EditorOrdinaryKeyformWriteSupport.read(
                    resolver("5.3.02", missing, "", false), EditorObjectReadCore.Kind.ART_MESH, true);
            assertEquals(Check.PRESENT, absent.writerSelectors());
            assertEquals(Check.PRESENT, absent.selectorsFor(Operation.ART_MESH_POSITIONS));
            assertEquals(Check.PRESENT, absent.selectorsFor(Operation.ART_MESH_DRAW_ORDERS));
            assertEquals(Check.PRESENT, absent.selectorsFor(Operation.ART_MESH_VALUES));
            assertEquals(Check.ABSENT, absent.selectorsFor(Operation.ART_MESH_VALUES_WITH_OPACITY));
        }
    }

    @Test
    void eachKindReportsOnlyItsTypedOperationsWhileHostAndHistoryRemainSeparateGuards() {
        for (var kind : EditorObjectReadCore.Kind.values()) {
            var support = EditorOrdinaryKeyformWriteSupport.read(resolver("5.3.02", "", "", false), kind, true);
            var expected =
                    switch (kind) {
                        case ART_MESH ->
                            Set.of(
                                    Operation.ART_MESH_POSITIONS,
                                    Operation.ART_MESH_DRAW_ORDERS,
                                    Operation.ART_MESH_VALUES,
                                    Operation.ART_MESH_VALUES_WITH_OPACITY);
                        case WARP -> Set.of(Operation.WARP_CONTROL_POINTS);
                        case ROTATION -> Set.of(Operation.ROTATION_FORMS);
                    };
            assertEquals(expected, support.operationSelectors().keySet());
            assertTrue(support.operationSelectors().values().stream().allMatch(c -> c == Check.PRESENT));
            var noWriter = EditorOrdinaryKeyformWriteSupport.read(
                    resolver("5.3.02", aliases(kind).iterator().next(), "", false), kind, true);
            assertTrue(noWriter.operationSelectors().values().stream().allMatch(c -> c == Check.ABSENT));
            var unreviewed = EditorOrdinaryKeyformWriteSupport.read(resolver("5.4.00", "", "", false), kind, true);
            assertEquals(Check.ABSENT, unreviewed.reviewedHost());
        }
    }

    private static Set<String> aliases(EditorObjectReadCore.Kind kind) {
        return switch (kind) {
            case ART_MESH -> EditorArtMeshKeyformSelectorContract.REQUIRED_ALIASES;
            case WARP -> EditorWarpAuthoringSelectorContract.WRITE_ALIASES;
            case ROTATION -> {
                var all = new HashSet<>(EditorRotationAuthoringSelectorContract.WRITE_ALIASES);
                all.addAll(EditorObjectWriteSelectorContract.ROTATION_REQUIRED_ALIASES);
                yield all;
            }
        };
    }

    private static VerifiedMemberResolver resolver(
            String version, String missingAlias, String missingCapability, boolean compatible) {
        var capabilities = new HashSet<>(Set.of(
                EditorArtMeshKeyformSelectorContract.CAPABILITY_ID,
                EditorWarpAuthoringSelectorContract.WRITE_CAPABILITY_ID,
                EditorRotationAuthoringSelectorContract.WRITE_CAPABILITY_ID,
                "cubism.editor-history.read"));
        capabilities.remove(missingCapability);
        var all = new HashSet<>(EditorHistoryReadSelectorContract.REQUIRED_ALIASES);
        for (var kind : EditorObjectReadCore.Kind.values()) all.addAll(aliases(kind));
        all.addAll(EditorArtMeshKeyformSelectorContract.OPACITY_REQUIRED_ALIASES);
        var selectors = all.stream()
                .filter(a -> !a.equals(missingAlias))
                .map(a -> StaticSelector.classSelector(a, "host/MustNeverBeLoaded"))
                .toList();
        return compatible
                ? TestVerifiedResolvers.createCompatible(
                        version, "5.9.99", "adapter.editor-model.readwrite", capabilities, selectors, getLoader())
                : TestVerifiedResolvers.create(
                        version, "adapter.editor-model.readwrite", capabilities, selectors, getLoader());
    }

    private static ClassLoader getLoader() {
        return EditorOrdinaryKeyformWriteSupportTest.class.getClassLoader();
    }
}
