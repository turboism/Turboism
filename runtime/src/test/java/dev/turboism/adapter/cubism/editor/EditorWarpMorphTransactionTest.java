package dev.turboism.adapter.cubism.editor;

import static dev.turboism.sdk.cubism.transaction.AuthoringTransactionOutcome.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.function.Consumer;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class EditorWarpMorphTransactionTest {
    @ParameterizedTest
    @org.junit.jupiter.params.provider.MethodSource("reviewedVersions")
    void twoIndependentParametersPreserveParentControlsAndOneUndoGroup(String version) throws Exception {
        edt(f -> {
            f.resolver = f.resolver(version, null, null);
            var before = f.warpState();
            assertEquals(
                    COMMITTED,
                    f.transaction(() -> {
                                f.createWarpOpacity("m", 1, 0);
                                f.createWarpOpacity("n", .5f, .25f);
                            })
                            .outcome());
            var after = f.warpState();
            assertEquals(2, after.keys.size());
            assertTrue(before.protectedSame(after));
            assertEquals(
                    0, after.stored.get(after.keys.get(0).formGuid()).form().opacity());
            assertEquals(
                    .25f, after.stored.get(after.keys.get(1).formGuid()).form().opacity());
            assertEquals(1, f.position);
            for (int i = 0; i < 3; i++) {
                f.undo();
                assertTrue(before.sameValues(f.warpState()));
                f.redo();
                assertTrue(after.sameValues(f.warpState()));
            }
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"partial", "null", "double-add", "setter", "refresh", "callback"})
    void failedEagerMutationRestoresMembershipAndHistory(String failure) throws Exception {
        edt(f -> {
            var before = f.warpState();
            var history = f.history();
            var result = f.transaction(() -> {
                f.createWarpOpacity("m", 1, 0);
                switch (failure) {
                    case "partial" -> f.partialAdd = true;
                    case "null" -> f.forceNativeNull = true;
                    case "wrong-seed" -> f.wrongSeed = true;
                    case "double-add" -> f.doubleAdd = true;
                    case "setter" ->
                        f.afterMutation = () -> {
                            if (f.mutations == 4) throw new IllegalStateException("setter");
                        };
                    case "refresh" ->
                        f.afterRefresh = () -> {
                            throw new IllegalStateException("refresh");
                        };
                }
                f.createWarpOpacity("n", 1, .3f);
                if (failure.equals("callback")) throw new IllegalStateException("callback");
            });
            assertEquals(ROLLED_BACK, result.outcome());
            assertTrue(before.sameValues(f.warpState()));
            assertEquals(history, f.history());
        });
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "snapshot",
                "listener",
                "root",
                "duplicate",
                "ordinary",
                "range",
                "constraints",
                "locked",
                "extended"
            })
    void admissionRejectsWithoutNativeMutation(String fault) throws Exception {
        edt(f -> {
            switch (fault) {
                case "snapshot" -> f.failSnapshot = true;
                case "listener" -> f.rejectListener = true;
                case "root" -> f.rejectRoot = true;
                case "duplicate" -> f.createWarpOpacity("m", 1, 0);
                case "ordinary" -> f.source.grid.bindings.add(new EditorArtMeshMorphFixture.Definition("axis", false));
                case "range" -> f.model.parameters.get(2).defaultValue = .5f;
                case "constraints" -> f.source.targets.constraints.entries.add(new Object());
                case "locked" -> f.source.locked = true;
                case "extended" -> f.source.extended = new Object();
            }
            int writes = f.mutations;
            assertNotEquals(
                    COMMITTED,
                    f.transaction(() -> f.createWarpOpacity("m", 1, 0)).outcome());
            assertEquals(writes, f.mutations);
        });
    }

    @Test
    void invalidInputAndMissingAuthorityNeverMutate() throws Exception {
        edt(f -> {
            assertThrows(IllegalArgumentException.class, () -> f.createWarpOpacity("m", 0, 0));
            assertThrows(IllegalArgumentException.class, () -> f.createWarpOpacity("m", 1, Float.NaN));
            f.resolver = f.resolver("5.3.02", "cubism.editor-model.deformer-form.set-opacity", null);
            assertNotEquals(
                    COMMITTED,
                    f.transaction(() -> f.createWarpOpacity("m", 1, 0)).outcome());
            assertEquals(0, f.mutations);
        });
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.MethodSource("reviewedVersions")
    void sourceControlKeysSupportBothDirectionsAndAbsoluteControls(String version) throws Exception {
        edt(f -> {
            f.resolver = f.resolver(version, null, null);
            var parameter = f.model.parameters.get(2);
            parameter.minimum = -30;
            parameter.maximum = 30;
            var controls = java.util.List.of(
                    new dev.turboism.sdk.cubism.model.Point2(1, 2),
                    new dev.turboism.sdk.cubism.model.Point2(3, 4),
                    new dev.turboism.sdk.cubism.model.Point2(5, 6),
                    new dev.turboism.sdk.cubism.model.Point2(7, 8));
            var before = f.warpState();
            assertEquals(
                    COMMITTED,
                    f.transaction(() -> {
                                for (float key : new float[] {-30, 30})
                                    EditorWarpMorphAccess.create(
                                            f.resolver,
                                            new dev.turboism.sdk.cubism.id.DeformerId("warp"),
                                            () -> f.currentSource,
                                            f.model,
                                            f.model,
                                            new dev.turboism.sdk.cubism.id.ParameterId("Paramm"),
                                            key,
                                            null,
                                            controls,
                                            () -> {},
                                            () -> f.coordinator.isCreatedTargetRestored(f.binding, f.source),
                                            contribution -> f.coordinator.mutate(f.binding, contribution),
                                            f::refreshNative);
                            })
                            .outcome());
            var after = f.warpState();
            assertEquals(2, after.keys.size());
            for (var key : after.keys)
                assertEquals(controls, after.stored.get(key.formGuid()).form().controlPoints());
            f.undo();
            assertTrue(before.sameValues(f.warpState()));
            f.redo();
            assertTrue(after.sameValues(f.warpState()));
        });
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.MethodSource("reviewedVersions")
    void rollbackAcceptsRemovedWarpOnlyWhenThisRootOwnsItsCreation(String version) throws Exception {
        for (boolean created : new boolean[] {true, false})
            edt(f -> {
                f.resolver = f.resolver(version, null, null);
                f.nativeAbort = true;
                var removed = new java.util.concurrent.atomic.AtomicBoolean();
                var before = f.history();
                var result = f.transaction(() -> {
                    var creation = new dev.turboism.adapter.cubism.editor.transaction.EditorUndoContribution(
                            "create",
                            "warp",
                            "Create Warp",
                            (edit, label) -> ((EditorArtMeshMorphFixture.Group) edit)
                                    .add(
                                            new EditorArtMeshMorphFixture.Undo() {
                                                public void undo() {
                                                    removed.set(true);
                                                }

                                                public void redo() {
                                                    removed.set(false);
                                                }
                                            },
                                            true),
                            () -> {},
                            () -> true,
                            () -> removed.set(true),
                            removed::get,
                            java.util.Set.of());
                    if (created) f.coordinator.mutateCreated(f.binding, f.source, creation);
                    else f.coordinator.mutate(f.binding, creation);
                    EditorWarpMorphAccess.create(
                            f.resolver,
                            new dev.turboism.sdk.cubism.id.DeformerId("warp"),
                            () -> {
                                if (removed.get()) throw new IllegalStateException("Deformer reference is stale: warp");
                                return f.source;
                            },
                            f.model,
                            f.model,
                            new dev.turboism.sdk.cubism.id.ParameterId("Paramm"),
                            1,
                            .5f,
                            null,
                            () -> {},
                            () -> f.coordinator.isCreatedTargetRestored(f.binding, f.source),
                            contribution -> f.coordinator.mutate(f.binding, contribution),
                            f::refreshNative);
                    throw new IllegalStateException("Abort after native Warp morph creation");
                });
                assertEquals(created ? ROLLED_BACK : RECOVERY_FAILED, result.outcome());
                assertTrue(removed.get());
                assertEquals(before, f.history());
            });
    }

    private static java.util.stream.Stream<String> reviewedVersions() {
        return dev.turboism.mapping.verification.ReviewedHostArtifacts.reviewedCubismVersions().stream()
                .sorted();
    }

    private static void edt(Consumer<EditorArtMeshMorphFixture> test) throws Exception {
        final Throwable[] failure = new Throwable[1];
        SwingUtilities.invokeAndWait(() -> {
            try {
                var fixture = new EditorArtMeshMorphFixture();
                fixture.neutralWarp();
                test.accept(fixture);
            } catch (Throwable t) {
                failure[0] = t;
            }
        });
        if (failure[0] != null) throw new AssertionError(failure[0]);
    }
}
