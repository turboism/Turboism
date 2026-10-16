package dev.turboism.adapter.cubism.editor;

import static dev.turboism.adapter.cubism.editor.EditorArtMeshAuthoringReadAccessTest.*;
import static org.junit.jupiter.api.Assertions.*;

import dev.turboism.adapter.cubism.editor.transaction.EditorAuthoringTransactionCoordinator;
import dev.turboism.adapter.cubism.editor.transaction.EditorRefreshRequirement;
import dev.turboism.adapter.cubism.editor.transaction.EditorUndoContribution;
import dev.turboism.mapping.verification.StaticSelector;
import dev.turboism.mapping.verification.TestVerifiedResolvers;
import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.mapping.verification.selector.EditorArtMeshAuthoringReadSelectorContract;
import dev.turboism.mapping.verification.selector.EditorArtMeshKeyformSelectorContract;
import dev.turboism.mapping.verification.selector.EditorHistoryReadSelectorContract;
import dev.turboism.mapping.verification.selector.EditorInspectorDrawableWriteSelectorContract;
import dev.turboism.mapping.verification.selector.EditorObjectHierarchyEditSelectorContract;
import dev.turboism.mapping.verification.selector.EditorObjectReadSelectorContract;
import dev.turboism.sdk.cubism.history.HistoryAction;
import dev.turboism.sdk.cubism.history.HistoryEntry;
import dev.turboism.sdk.cubism.history.HistoryEntryDetail;
import dev.turboism.sdk.cubism.history.HistorySnapshot;
import dev.turboism.sdk.cubism.id.ArtMeshId;
import dev.turboism.sdk.cubism.model.ArtMeshAuthoringSnapshot;
import dev.turboism.sdk.cubism.model.ArtMeshAuthoringSnapshot.Coordinate;
import dev.turboism.sdk.cubism.model.ArtMeshKeyform;
import dev.turboism.sdk.cubism.model.ArtMeshKeyformPositions;
import dev.turboism.sdk.cubism.model.Drawable;
import dev.turboism.sdk.cubism.model.Point2;
import dev.turboism.sdk.cubism.transaction.AuthoringTransactionOptions;
import dev.turboism.sdk.cubism.transaction.AuthoringTransactionOutcome;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Real lookup/writer/coordinator wiring; only native members/history are in-memory substitutes. */
class EditorArtMeshKeyformTransactionTest {
    private static final String M = "cubism.editor-model.";
    private static final ArtMeshId ID = new ArtMeshId("ArtMeshIntegration");
    private static final List<Point2> A = positions(20);
    private static final List<Point2> B = positions(70);

    private static ArtMeshKeyformPositions edit(Fixture f, float x, float y, List<Point2> positions) {
        return new ArtMeshKeyformPositions(
                tuple(x, y), f.target(x, y).snapshot().formGuid(), positions);
    }

    @Test
    void bulkSourceWritesKeepInstancesCurrentButCoalescePaletteAndCanvasRefresh() {
        onEdt(f -> {
            var result = f.coordinator.execute(f.binding, AuthoringTransactionOptions.of("bulk source import"), () -> {
                var before = f.mesh.authoringSnapshot();
                f.mesh.setOrdinaryKeyformPositions(before, List.of(edit(f, -1, -1, A)));
                assertEquals(A, f.target(-1, -1).snapshot().positions());
                assertEquals(1, f.host.updates, "Native instances stay current for subsequent parent conversion");
                assertEquals(0, f.host.repaints, "Do not rebuild UI for every source write");
                f.mesh.setOrdinaryKeyformPositions(f.mesh.authoringSnapshot(), List.of(edit(f, -1, -1, B)));
                assertEquals(B, f.target(-1, -1).snapshot().positions());
                assertEquals(2, f.host.updates);
                assertEquals(0, f.host.repaints);
                return null;
            });
            assertEquals(AuthoringTransactionOutcome.COMMITTED, result.outcome());
            assertEquals(1, f.host.repaints);
            assertEquals(1, f.host.dirtyMarks);
        });
    }

    @Test
    void explicitStoredSourceBatchEvaluatesOnceBeforeReturningAndRestoresOnFailure() {
        for (boolean abort : List.of(false, true))
            onEdt(f -> {
                final var original = f.mesh.authoringSnapshot();
                var result = f.coordinator.execute(
                        f.binding, AuthoringTransactionOptions.of("source evaluation batch"), () -> {
                            f.coordinator.batchStoredSourceWrites(f.binding, () -> {
                                f.mesh.setOrdinaryKeyformPositions(
                                        f.mesh.authoringSnapshot(), List.of(edit(f, -1, -1, A)));
                                assertEquals(A, f.target(-1, -1).snapshot().positions());
                                f.mesh.setOrdinaryKeyformPositions(
                                        f.mesh.authoringSnapshot(), List.of(edit(f, -1, -1, B)));
                                assertEquals(B, f.target(-1, -1).snapshot().positions());
                                assertEquals(0, f.host.updates, "Stored snapshots do not require instance evaluation");
                            });
                            assertEquals(
                                    1,
                                    f.host.updates,
                                    "Evaluation barrier precedes subsequent instance-dependent operations");
                            if (abort) throw new IllegalStateException("after source batch");
                            return null;
                        });
                assertEquals(
                        abort ? AuthoringTransactionOutcome.ROLLED_BACK : AuthoringTransactionOutcome.COMMITTED,
                        result.outcome());
                if (abort) assertEquals(original, f.mesh.authoringSnapshot());
                else assertEquals(B, f.target(-1, -1).snapshot().positions());
            });
    }

    @Test
    void parentConversionsKeepInstancesCurrentAndRefreshPanelsOnlyAtCommit() {
        onEdt(f -> {
            final var original = f.mesh.authoringSnapshot();
            var result = f.coordinator.execute(f.binding, AuthoringTransactionOptions.of("bulk parent import"), () -> {
                f.mesh.setTargetDeformer(Optional.empty());
                assertNull(f.source.parent);
                assertNotEquals(original.forms(), f.mesh.authoringSnapshot().forms());
                assertEquals(1, f.host.updates, "Native conversion must be evaluated immediately");
                assertEquals(0, f.host.repaints, "Parent changes must not rebuild palettes inside the root");
                f.mesh.setTargetDeformer(Optional.of(new dev.turboism.sdk.cubism.id.DeformerId(ID.value())));
                assertNotNull(f.source.parent);
                assertEquals(2, f.host.updates);
                assertEquals(0, f.host.repaints);
                return null;
            });
            assertEquals(AuthoringTransactionOutcome.COMMITTED, result.outcome());
            assertEquals(1, f.host.repaints);
            assertEquals(1, f.host.dirtyMarks);
        });
    }

    @Test
    void missingParentRecoveryCapabilityRejectsBeforeNativeMutation() {
        onEdt(f -> {
            final var mesh = f.access(resolver(false, true), f.coordinator)
                    .drawables("model", f.model, f.model)
                    .find(ID);
            final var original = f.mesh.authoringSnapshot();
            final var parent = f.source.parent;
            var result =
                    f.coordinator.execute(f.binding, AuthoringTransactionOptions.of("unsupported parent root"), () -> {
                        mesh.setTargetDeformer(Optional.empty());
                        return null;
                    });
            assertNotEquals(AuthoringTransactionOutcome.COMMITTED, result.outcome());
            assertEquals(0, f.host.begins);
            assertSame(parent, f.source.parent);
            assertEquals(original, f.mesh.authoringSnapshot());
        });
    }

    @Test
    void nonRootParentConversionKeepsNativeFactoryAndOneRoot() {
        for (boolean abort : List.of(false, true))
            onEdt(f -> {
                final var original = f.mesh.authoringSnapshot();
                final var parent = f.source.parent;
                f.source.parent = null;
                final var root = f.mesh.authoringSnapshot();
                var result =
                        f.coordinator.execute(f.binding, AuthoringTransactionOptions.of("attach native parent"), () -> {
                            f.mesh.setTargetDeformer(
                                    Optional.of(new dev.turboism.sdk.cubism.id.DeformerId(ID.value())));
                            assertSame(parent, f.source.parent);
                            assertTrue(f.source.nonRootFactoryCalled);
                            f.target(-1, -1).setPositions(A);
                            if (abort) throw new IllegalStateException("after attach");
                            return null;
                        });
                assertEquals(
                        abort ? AuthoringTransactionOutcome.ROLLED_BACK : AuthoringTransactionOutcome.COMMITTED,
                        result.outcome());
                assertEquals(1, f.host.begins);
                if (abort) {
                    assertNull(f.source.parent);
                    assertEquals(root, f.mesh.authoringSnapshot());
                } else
                    assertEquals(
                            original.sourceGeometry(),
                            f.mesh.authoringSnapshot().sourceGeometry());
            });
    }

    @Test
    void parentConversionAndKeyformWriteShareOneRoot() {
        onEdt(f -> {
            final var original = f.mesh.authoringSnapshot();
            var result =
                    f.coordinator.execute(f.binding, AuthoringTransactionOptions.of("import parent and forms"), () -> {
                        f.mesh.setTargetDeformer(Optional.empty());
                        assertNull(f.source.parent);
                        assertNotEquals(
                                original.forms(), f.mesh.authoringSnapshot().forms());
                        f.target(-1, -1).setPositions(A);
                        return null;
                    });
            assertEquals(AuthoringTransactionOutcome.COMMITTED, result.outcome());
            assertEquals(1, f.host.begins);
            assertEquals(1, f.host.entries.size());
            assertEquals(2, f.host.listeners);
            assertEquals(original.sourceGeometry(), f.mesh.authoringSnapshot().sourceGeometry());
        });
    }

    @Test
    void parentEagerMutationRestoresAllFormsEvenWhenAdmissionRejects() {
        for (boolean nativeAbort : List.of(false, true))
            for (boolean reject : List.of(false, true))
                onEdt(f -> {
                    final var original = f.mesh.authoringSnapshot();
                    final var parent = f.source.parent;
                    f.host.nativeAbort = nativeAbort;
                    f.host.rejectAdmission = reject;
                    var result = f.coordinator.execute(
                            f.binding, AuthoringTransactionOptions.of("failed import parent"), () -> {
                                f.mesh.setTargetDeformer(Optional.empty());
                                f.target(-1, -1).setPositions(A);
                                throw new IllegalStateException("later import failure");
                            });
                    assertEquals(AuthoringTransactionOutcome.ROLLED_BACK, result.outcome());
                    assertSame(parent, f.source.parent);
                    assertEquals(original, f.mesh.authoringSnapshot());
                    assertEquals(1, f.host.begins);
                    assertTrue(f.host.entries.isEmpty());
                });
    }

    @Test
    void parentRecoveryRejectsReplacedFormIdentity() {
        onEdt(f -> {
            f.host.nativeAbort = false;
            var result = f.coordinator.execute(f.binding, AuthoringTransactionOptions.of("corrupt parent form"), () -> {
                f.mesh.setTargetDeformer(Optional.empty());
                final var old = f.source.data.forms.get(0);
                f.source.data.forms.set(0, new StoredForm(old.guid.text(), 99));
                throw new IllegalStateException("foreign form replacement");
            });
            assertEquals(AuthoringTransactionOutcome.RECOVERY_FAILED, result.outcome());
        });
    }

    @Test
    void createdMeshBakeRecoversWhenNativeAbortAlreadyRemovedTheMesh() {
        for (boolean nativeAbort : List.of(false, true))
            onEdt(f -> {
                f.model.present = false;
                f.host.nativeAbort = nativeAbort;
                var result = f.coordinator.execute(f.binding, AuthoringTransactionOptions.of("create and bake"), () -> {
                    f.coordinator.mutateCreated(
                            f.binding,
                            f.source,
                            new EditorUndoContribution(
                                    "cubism.art-mesh.create",
                                    "model:art-mesh:" + ID.value(),
                                    "Create mesh",
                                    (edit, label) -> {
                                        var undo = new NativeUndo(List.of());
                                        undo.specialUndo = () -> f.model.present = false;
                                        assertTrue(((NativeEdit) edit).add(undo, true));
                                    },
                                    () -> f.model.present = true,
                                    () -> f.model.present,
                                    () -> f.model.present = false,
                                    () -> !f.model.present,
                                    Set.of()));
                    assertFalse(f.coordinator.isCreatedTargetRestored(f.binding, f.source));
                    f.mesh.setOrdinaryKeyformPositions(
                            f.mesh.authoringSnapshot(), List.of(edit(f, -1, -1, A), edit(f, 1, 1, B)));
                    throw new IllegalStateException("failure after bake");
                });
                assertEquals(AuthoringTransactionOutcome.ROLLED_BACK, result.outcome());
                assertFalse(f.model.present);
                assertEquals(0, f.host.history().position());
                assertFalse(f.coordinator.isCreatedTargetRestored(f.binding, f.source));
            });
    }

    @Test
    void disappearingPreexistingMeshStillReportsRecoveryFailure() {
        onEdt(f -> {
            var result =
                    f.coordinator.execute(f.binding, AuthoringTransactionOptions.of("lost preexisting mesh"), () -> {
                        f.mesh.setOrdinaryKeyformPositions(
                                f.mesh.authoringSnapshot(), List.of(edit(f, -1, -1, A), edit(f, 1, 1, B)));
                        f.model.present = false;
                        throw new IllegalStateException("unexpected removal");
                    });
            assertEquals(AuthoringTransactionOutcome.RECOVERY_FAILED, result.outcome());
            assertFalse(f.coordinator.isCreatedTargetRestored(f.binding, f.source));
        });
    }

    @Test
    void drawOrderBatchPreservesPositionsAndSharesOneRootWithNativeRecovery() {
        onEdt(f -> {
            var before = f.mesh.authoringSnapshot();
            var edits = List.of(
                    new dev.turboism.sdk.cubism.model.ArtMeshKeyformDrawOrder(
                            tuple(-1, -1), f.target(-1, -1).snapshot().formGuid(), 120),
                    new dev.turboism.sdk.cubism.model.ArtMeshKeyformDrawOrder(
                            tuple(1, 1), f.target(1, 1).snapshot().formGuid(), 880));
            var result = f.coordinator.execute(f.binding, AuthoringTransactionOptions.of("batch orders"), () -> {
                f.mesh.setOrdinaryKeyformDrawOrders(before, edits);
                assertEquals(120, f.target(-1, -1).snapshot().drawOrder());
                assertEquals(880, f.target(1, 1).snapshot().drawOrder());
                assertEquals(
                        before.forms().stream()
                                .map(ArtMeshAuthoringSnapshot.Form::positions)
                                .toList(),
                        f.mesh.authoringSnapshot().forms().stream()
                                .map(ArtMeshAuthoringSnapshot.Form::positions)
                                .toList());
                throw new IllegalStateException("injected order batch failure");
            });
            assertEquals(AuthoringTransactionOutcome.ROLLED_BACK, result.outcome());
            assertEquals(before, f.mesh.authoringSnapshot());
            assertEquals(1, f.host.snapshots);
        });
    }

    @Test
    void drawOrderBatchRejectsDefaultDuplicateAndMismatchedTargetsBeforeMutation() {
        onEdt(f -> {
            var before = f.mesh.authoringSnapshot();
            var good = new dev.turboism.sdk.cubism.model.ArtMeshKeyformDrawOrder(
                    tuple(-1, -1), f.target(-1, -1).snapshot().formGuid(), 120);
            assertThrows(
                    IllegalArgumentException.class,
                    () -> f.mesh.setOrdinaryKeyformDrawOrders(before, List.of(good, good)));
            assertThrows(
                    UnsupportedOperationException.class,
                    () -> f.mesh.setOrdinaryKeyformDrawOrders(
                            before,
                            List.of(
                                    good,
                                    new dev.turboism.sdk.cubism.model.ArtMeshKeyformDrawOrder(
                                            tuple(0, 0),
                                            f.target(0, 0).snapshot().formGuid(),
                                            120))));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> f.mesh.setOrdinaryKeyformDrawOrders(
                            before,
                            List.of(new dev.turboism.sdk.cubism.model.ArtMeshKeyformDrawOrder(
                                    tuple(1, 1), good.formGuid(), 120))));
            assertEquals(before, f.mesh.authoringSnapshot());
            assertEquals(0, f.host.snapshots);
        });
    }

    @Test
    void bulkWritesTwoTargetsWithOneNativeSnapshotAndNoopsWithoutHistory() {
        onEdt(f -> {
            var before = f.mesh.authoringSnapshot();
            f.mesh.setOrdinaryKeyformPositions(before, List.of(edit(f, -1, -1, A), edit(f, 1, 1, B)));
            assertEquals(1, f.host.snapshots);
            assertEquals(1, f.host.commits);
            assertEquals(A, f.target(-1, -1).snapshot().positions());
            assertEquals(B, f.target(1, 1).snapshot().positions());
            var after = f.mesh.authoringSnapshot();
            assertTrue(EditorArtMeshKeyformAccess.sameLayout(before, after));
            assertEquals(
                    before.forms().stream()
                            .map(ArtMeshAuthoringSnapshot.Form::drawOrder)
                            .toList(),
                    after.forms().stream()
                            .map(ArtMeshAuthoringSnapshot.Form::drawOrder)
                            .toList());
            f.mesh.setOrdinaryKeyformPositions(after, List.of(edit(f, -1, -1, A), edit(f, 1, 1, B)));
            f.mesh.setOrdinaryKeyformPositions(after, List.of());
            assertEquals(1, f.host.snapshots);
            assertEquals(1, f.host.commits);
        });
    }

    @Test
    void bulkValidatesEveryTargetBeforeAnyWrite() {
        onEdt(f -> {
            var before = f.mesh.authoringSnapshot();
            var good = edit(f, -1, -1, A);
            for (var bad : List.of(
                    good,
                    new ArtMeshKeyformPositions(tuple(1, 1), "wrong-guid", B),
                    new ArtMeshKeyformPositions(
                            tuple(1, 1), f.target(1, 1).snapshot().formGuid(), List.of()))) {
                assertThrows(
                        IllegalArgumentException.class,
                        () -> f.mesh.setOrdinaryKeyformPositions(before, List.of(good, bad)));
                assertEquals(before, f.mesh.authoringSnapshot());
            }
            var base = edit(f, 0, 0, B);
            assertThrows(
                    UnsupportedOperationException.class,
                    () -> f.mesh.setOrdinaryKeyformPositions(before, List.of(good, base)));
            assertEquals(0, f.host.snapshots);
            assertEquals(0, f.host.begins);
            f.target(1, 1).setPositions(B);
            assertThrows(IllegalStateException.class, () -> f.mesh.setOrdinaryKeyformPositions(before, List.of(good)));
        });
    }

    @Test
    void bulkAndIndividualWritesRestoreWholeRootWithEitherNativeAbortBehavior() {
        for (boolean nativeAbort : List.of(false, true))
            for (boolean batchFirst : List.of(false, true))
                onEdt(f -> {
                    f.host.nativeAbort = nativeAbort;
                    var before = f.mesh.authoringSnapshot();
                    var history = f.host.history();
                    var result = f.coordinator.execute(
                            f.binding, AuthoringTransactionOptions.of("bulk mixed rollback"), () -> {
                                if (!batchFirst) f.target(1, 1).setDrawOrder(700);
                                f.mesh.setOrdinaryKeyformPositions(
                                        f.mesh.authoringSnapshot(), List.of(edit(f, -1, -1, A), edit(f, 1, 1, B)));
                                if (batchFirst) f.target(1, 1).setDrawOrder(700);
                                throw new IllegalArgumentException("caller aborts");
                            });
                    assertEquals(AuthoringTransactionOutcome.ROLLED_BACK, result.outcome());
                    assertEquals(before, f.mesh.authoringSnapshot());
                    assertEquals(history, f.host.history());
                    assertEquals(2, f.host.snapshots);
                });
    }

    @Test
    void bulkRefreshFailureRestoresAllTargets() {
        for (boolean nativeAbort : List.of(false, true))
            onEdt(f -> {
                f.host.nativeAbort = nativeAbort;
                var before = f.mesh.authoringSnapshot();
                var history = f.host.history();
                f.host.afterNextUpdate = () -> {
                    throw new IllegalStateException("refresh failed");
                };
                var result = f.coordinator.execute(f.binding, AuthoringTransactionOptions.of("bulk failure"), () -> {
                    f.mesh.setOrdinaryKeyformPositions(before, List.of(edit(f, -1, -1, A), edit(f, 1, 1, B)));
                    return null;
                });
                assertEquals(AuthoringTransactionOutcome.ROLLED_BACK, result.outcome());
                assertEquals(before, f.mesh.authoringSnapshot());
                assertEquals(history, f.host.history());
            });
    }

    @Test
    void bulkMidSetterFailureRestoresEarlierAndCurrentTargets() {
        for (boolean nativeAbort : List.of(false, true))
            onEdt(f -> {
                f.host.nativeAbort = nativeAbort;
                var before = f.mesh.authoringSnapshot();
                var history = f.host.history();
                var edits = List.of(edit(f, -1, -1, A), edit(f, 1, 1, B));
                String last = edits.get(1).formGuid();
                f.source.data.forms.stream()
                                .filter(form -> form.guid.text().equals(last))
                                .findFirst()
                                .orElseThrow()
                                .afterSetPositions =
                        () -> {
                            throw new IllegalStateException("setter failed after mutation");
                        };
                var result =
                        f.coordinator.execute(f.binding, AuthoringTransactionOptions.of("mid-batch failure"), () -> {
                            f.mesh.setOrdinaryKeyformPositions(before, edits);
                            return null;
                        });
                assertEquals(AuthoringTransactionOutcome.ROLLED_BACK, result.outcome());
                assertEquals(before, f.mesh.authoringSnapshot());
                assertEquals(history, f.host.history());
            });
    }

    @Test
    void bulkRejectsSharedFormsAndMissingHistoryBeforeMutation() {
        onEdt(f -> {
            var good = edit(f, -1, -1, A);
            f.source.data.grid.rows.get(good.formGuid()).addAll(f.source.data.grid.rows.remove("f8"));
            var before = f.mesh.authoringSnapshot();
            assertThrows(
                    IllegalArgumentException.class, () -> f.mesh.setOrdinaryKeyformPositions(before, List.of(good)));
            assertEquals(0, f.host.snapshots);
        });
        onEdt(f -> {
            var before = f.mesh.authoringSnapshot();
            var edits = List.of(edit(f, -1, -1, A));
            var uncoordinated = f.access(f.resolver, null)
                    .drawables("model", f.model, f.model)
                    .find(ID);
            var noHistory = f.access(resolver(true), f.coordinator)
                    .drawables("model", f.model, f.model)
                    .find(ID);
            assertThrows(
                    UnsupportedOperationException.class,
                    () -> uncoordinated.setOrdinaryKeyformPositions(before, edits));
            assertThrows(
                    UnsupportedOperationException.class, () -> noHistory.setOrdinaryKeyformPositions(before, edits));
            assertEquals(0, f.host.snapshots);
        });
    }

    @Test
    void bulkWholeSnapshotReadsDoNotGrowWithTargetCount() {
        int[] reads = new int[2];
        for (int mode = 0; mode < 2; mode++) {
            final int index = mode;
            onEdt(f -> {
                var before = f.mesh.authoringSnapshot();
                var edits = before.cells().stream()
                        .filter(c -> c.coordinates().stream().anyMatch(v -> v.value() != 0))
                        .limit(index == 0 ? 1 : 8)
                        .map(c -> new ArtMeshKeyformPositions(c.coordinates(), c.formGuid(), A))
                        .toList();
                f.source.positionReads = 0;
                f.mesh.setOrdinaryKeyformPositions(before, edits);
                reads[index] = f.source.positionReads;
                assertEquals(1, f.host.snapshots);
            });
        }
        assertTrue(reads[0] > 0);
        assertEquals(reads[0], reads[1], "whole-grid reads must be per batch, not per target");
    }

    @Test
    void bulkBudgetsRejectBeforeAnyHostWrite() {
        onEdt(f -> {
            var before = f.mesh.authoringSnapshot();
            var edit = edit(f, -1, -1, A);
            assertThrows(
                    IllegalArgumentException.class,
                    () -> f.mesh.setOrdinaryKeyformPositions(before, java.util.Collections.nCopies(4097, edit)));
            var oversized = new ArtMeshKeyformPositions(
                    edit.coordinates(), edit.formGuid(), java.util.Collections.nCopies(2_000_001, new Point2(0, 0)));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> f.mesh.setOrdinaryKeyformPositions(before, List.of(oversized)));
            assertEquals(0, f.host.snapshots);
            assertEquals(0, f.host.begins);
        });
    }

    @Test
    void singleWriteAndBatchUseNativeSnapshotsAndOneRootHistoryEntry() {
        onEdt(f -> {
            final var before = f.mesh.authoringSnapshot();
            f.target(1, 1).setPositions(A);
            assertEquals(1, f.host.begins);
            assertEquals(1, f.host.commits);
            assertEquals(1, f.host.history().position());
            assertEquals(1, f.host.snapshots);
            assertEquals(1, f.host.listeners);
            assertEquals(1, f.host.dirtyMarks);
            assertEquals(A, f.target(1, 1).snapshot().positions());
            assertEquals(before.sourceGeometry(), f.mesh.authoringSnapshot().sourceGeometry());
            assertEquals(HistoryAction.DetailLevel.PARTIAL, f.host.detail.detailLevel());
            final var root = f.coordinator.execute(f.binding, AuthoringTransactionOptions.of("two targets"), () -> {
                f.target(-1, -1).setPositions(B);
                f.target(1, 1).setPositions(B);
                return "written";
            });
            assertEquals(AuthoringTransactionOutcome.COMMITTED, root.outcome());
            assertEquals(2, f.host.begins);
            assertEquals(2, f.host.history().position());
            assertEquals(3, f.host.snapshots);
            assertEquals(B, f.target(-1, -1).snapshot().positions());
            assertEquals(B, f.target(1, 1).snapshot().positions());
            assertTrue(f.host.updates >= 3);
            assertTrue(f.host.repaints >= 3);
        });
    }

    @Test
    void noopAndDefaultRefusalDoNotOpenNativeEdit() {
        onEdt(f -> {
            final var before = f.host.history();
            final var result = f.coordinator.execute(f.binding, AuthoringTransactionOptions.of("noop"), () -> {
                final var target = f.target(1, 1);
                target.setPositions(target.snapshot().positions());
                return null;
            });
            assertEquals(AuthoringTransactionOutcome.NO_CHANGE, result.outcome());
            assertThrows(
                    UnsupportedOperationException.class, () -> f.target(0, 0).setPositions(A));
            assertEquals(0, f.host.begins);
            assertEquals(0, f.host.snapshots);
            assertEquals(0, f.host.dirtyMarks);
            assertEquals(before, f.host.history());
        });
    }

    @Test
    void callbackFailureRestoresWholeRootForDifferentAndRepeatedTargetsWithEitherAbortBehavior() {
        for (final boolean nativeAbort : List.of(false, true)) {
            for (final boolean sameTarget : List.of(false, true)) {
                onEdt(f -> {
                    f.host.nativeAbort = nativeAbort;
                    final var before = f.mesh.authoringSnapshot();
                    final var history = f.host.history();
                    final var result =
                            f.coordinator.execute(f.binding, AuthoringTransactionOptions.of("rollback"), () -> {
                                f.target(1, 1).setPositions(A);
                                f.target(sameTarget ? 1 : -1, sameTarget ? 1 : -1)
                                        .setPositions(B);
                                throw new IllegalArgumentException("caller aborts");
                            });
                    assertEquals(
                            AuthoringTransactionOutcome.ROLLED_BACK,
                            result.outcome(),
                            "nativeAbort=" + nativeAbort + ", sameTarget=" + sameTarget);
                    // Independent whole-root oracle, not just each contribution's restored predicate.
                    assertEquals(before, f.mesh.authoringSnapshot());
                    assertEquals(history, f.host.history());
                    assertEquals(1, f.host.begins);
                    assertEquals(1, f.host.aborts);
                    assertEquals(0, f.host.commits);
                    assertEquals(0, f.host.dirtyMarks);
                    assertEquals(2, f.host.snapshots);
                });
            }
        }
    }

    @Test
    void admissionRejectionAfterEarlierWriteNeverMutatesSecondTargetAndRestoresRoot() {
        onEdt(f -> {
            final var before = f.mesh.authoringSnapshot();
            final var history = f.host.history();
            final var result = f.coordinator.execute(f.binding, AuthoringTransactionOptions.of("reject undo"), () -> {
                f.target(1, 1).setPositions(A);
                f.host.rejectAdmission = true;
                f.target(-1, -1).setPositions(B);
                fail("rejected admission must throw");
                return null;
            });
            assertEquals(AuthoringTransactionOutcome.ROLLED_BACK, result.outcome());
            assertEquals(before, f.mesh.authoringSnapshot());
            assertEquals(history, f.host.history());
            assertEquals(2, f.host.snapshots);
            assertEquals(1, f.host.listeners);
            assertEquals(0, f.host.commits);
        });
    }

    @Test
    void postSetterRefreshFailureRestoresPositionsAndHistory() {
        for (final boolean nativeAbort : List.of(false, true))
            onEdt(f -> {
                f.host.nativeAbort = nativeAbort;
                final var before = f.mesh.authoringSnapshot();
                final var history = f.host.history();
                f.host.afterNextUpdate = () -> {
                    throw new IllegalStateException("native update failed");
                };
                final var result =
                        f.coordinator.execute(f.binding, AuthoringTransactionOptions.of("failed update"), () -> {
                            f.target(1, 1).setPositions(A);
                            return null;
                        });
                assertEquals(AuthoringTransactionOutcome.ROLLED_BACK, result.outcome());
                assertEquals(before, f.mesh.authoringSnapshot());
                assertEquals(history, f.host.history());
            });
    }

    @ParameterizedTest
    @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void secondRefreshFailureDoesNotMisreportAnAlreadyRestoredRoot(
            final boolean nativeAbort, final boolean sameTarget) {
        onEdt(f -> {
            f.host.nativeAbort = nativeAbort;
            final var before = f.mesh.authoringSnapshot();
            final var history = f.host.history();
            final var result =
                    f.coordinator.execute(f.binding, AuthoringTransactionOptions.of("second update fails"), () -> {
                        f.target(1, 1).setPositions(A);
                        f.host.afterNextUpdate = () -> {
                            throw new IllegalStateException("second native update failed");
                        };
                        f.target(sameTarget ? 1 : -1, sameTarget ? 1 : -1).setPositions(B);
                        fail("second update must throw");
                        return null;
                    });
            // Model/history are independent of each contribution's intermediate before image.
            assertEquals(before, f.mesh.authoringSnapshot());
            assertEquals(history, f.host.history());
            assertEquals(1, f.host.aborts);
            assertEquals(0, f.host.commits);
            assertEquals(2, f.host.snapshots);
            assertEquals(AuthoringTransactionOutcome.ROLLED_BACK, result.outcome());
        });
    }

    @Test
    void uncertainCollateralMutationIsRecoveryFailedNotFalseRolledBack() {
        onEdt(f -> {
            f.host.nativeAbort = false;
            f.host.afterNextUpdate = () -> f.source.data.forms.get(4).positions[0] = 999;
            final var result = f.coordinator.execute(f.binding, AuthoringTransactionOptions.of("collateral"), () -> {
                f.target(1, 1).setPositions(A);
                return null;
            });
            assertEquals(AuthoringTransactionOutcome.RECOVERY_FAILED, result.outcome());
            assertEquals(999, f.source.data.forms.get(4).positions[0]);
            assertEquals(0, f.host.commits);
        });
    }

    @Test
    void absentCoordinatorAndHistoryCapabilityNeverFallBackToIndependentEnvelope() {
        onEdt(f -> {
            final var uncoordinated = f.access(f.resolver, null)
                    .drawables("model", f.model, f.model)
                    .find(ID);
            assertThrows(
                    UnsupportedOperationException.class,
                    () -> uncoordinated.ordinaryKeyform(tuple(1, 1)).setPositions(A));
            assertThrows(
                    UnsupportedOperationException.class,
                    () -> uncoordinated.ordinaryKeyform(tuple(1, 1)).setDrawOrder(600));
            final var noHistory = f.access(resolver(true), f.coordinator)
                    .drawables("model", f.model, f.model)
                    .find(ID);
            assertThrows(
                    UnsupportedOperationException.class,
                    () -> noHistory.ordinaryKeyform(tuple(1, 1)).setPositions(A));
            assertThrows(
                    UnsupportedOperationException.class,
                    () -> noHistory.ordinaryKeyform(tuple(1, 1)).setDrawOrder(600));
            assertEquals(0, f.host.begins);
            assertEquals(0, f.host.snapshots);
        });
    }

    @ParameterizedTest
    @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void mixedSuccessfulWritesTouchOnlyExplicitFieldsAndShareOneUndo(
            final boolean orderFirst, final boolean sameTarget) {
        onEdt(f -> {
            final var before = f.mesh.authoringSnapshot();
            final var first = f.target(1, 1);
            final var second = f.target(sameTarget ? 1 : -1, sameTarget ? 1 : -1);
            final String positionGuid = (orderFirst ? second : first).snapshot().formGuid();
            final String orderGuid = (orderFirst ? first : second).snapshot().formGuid();
            final var result = f.coordinator.execute(f.binding, AuthoringTransactionOptions.of("mixed fields"), () -> {
                if (orderFirst) {
                    first.setDrawOrder(600);
                    second.setPositions(A);
                } else {
                    first.setPositions(A);
                    second.setDrawOrder(600);
                }
                return null;
            });
            final var forms = before.forms().stream()
                    .map(form -> new ArtMeshAuthoringSnapshot.Form(
                            form.formGuid(),
                            form.formGuid().equals(positionGuid) ? A : form.positions(),
                            form.formGuid().equals(orderGuid) ? 600 : form.drawOrder()))
                    .toList();
            assertEquals(
                    new ArtMeshAuthoringSnapshot(
                            before.artMeshId(),
                            before.sourceGuid(),
                            before.sourceGeometry(),
                            before.axes(),
                            before.cells(),
                            forms,
                            before.morphKeys()),
                    f.mesh.authoringSnapshot());
            assertEquals(AuthoringTransactionOutcome.COMMITTED, result.outcome());
            assertEquals(1, f.host.begins);
            assertEquals(1, f.host.commits);
            assertEquals(1, f.host.history().position());
            assertEquals(2, f.host.snapshots);
            assertEquals(2, f.host.listeners);
            assertEquals(1, f.host.dirtyMarks);
        });
    }

    @ParameterizedTest
    @CsvSource({
        "false,false,false,false", "false,false,false,true", "false,false,true,false", "false,false,true,true",
        "false,true,false,false", "false,true,false,true", "false,true,true,false", "false,true,true,true",
        "true,false,false,false", "true,false,false,true", "true,false,true,false", "true,false,true,true",
        "true,true,false,false", "true,true,false,true", "true,true,true,false", "true,true,true,true"
    })
    void mixedWritesRestoreWholeRootAcrossBothAbortModelsAndFailurePoints(
            final boolean nativeAbort,
            final boolean orderFirst,
            final boolean sameTarget,
            final boolean refreshFailure) {
        onEdt(f -> {
            f.host.nativeAbort = nativeAbort;
            final var before = f.mesh.authoringSnapshot();
            final var history = f.host.history();
            final var first = f.target(1, 1);
            final var second = f.target(sameTarget ? 1 : -1, sameTarget ? 1 : -1);
            final int[] steps = {0};
            final var result =
                    f.coordinator.execute(f.binding, AuthoringTransactionOptions.of("mixed rollback"), () -> {
                        if (orderFirst) first.setDrawOrder(600);
                        else first.setPositions(A);
                        steps[0]++;
                        if (refreshFailure)
                            f.host.afterNextUpdate = () -> {
                                steps[0]++;
                                throw new IllegalStateException("second mixed refresh failed");
                            };
                        if (orderFirst) second.setPositions(B);
                        else second.setDrawOrder(700);
                        assertFalse(refreshFailure, "refresh failure must propagate before callback continues");
                        steps[0]++;
                        throw new IllegalStateException("callback after both writes");
                    });
            assertEquals(2, steps[0], "failure point was actually reached");
            assertEquals(before, f.mesh.authoringSnapshot());
            assertEquals(history, f.host.history());
            assertEquals(AuthoringTransactionOutcome.ROLLED_BACK, result.outcome());
            assertEquals(1, f.host.begins);
            assertEquals(1, f.host.aborts);
            assertEquals(0, f.host.commits);
            assertEquals(0, f.host.dirtyMarks);
            assertEquals(2, f.host.snapshots);
        });
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void threeContributionsRecoverIntermediateValuesInReverseWithoutFalseFailure(final boolean nativeAbort) {
        onEdt(f -> {
            f.host.nativeAbort = nativeAbort;
            final var before = f.mesh.authoringSnapshot();
            final var history = f.host.history();
            final var target = f.target(1, 1);
            final var result = f.coordinator.execute(f.binding, AuthoringTransactionOptions.of("repeat mixed"), () -> {
                target.setDrawOrder(600);
                target.setPositions(A);
                f.host.afterNextUpdate = () -> {
                    throw new IllegalStateException("third refresh failed");
                };
                target.setDrawOrder(700);
                fail("third write must throw");
                return null;
            });
            assertEquals(before, f.mesh.authoringSnapshot());
            assertEquals(history, f.host.history());
            assertEquals(3, f.host.snapshots);
            assertEquals(AuthoringTransactionOutcome.ROLLED_BACK, result.outcome());
            assertEquals(0, f.host.commits);
        });
    }

    @Test
    void orderNoopBoundsAndDefaultRefusalHaveZeroEditHistoryDirtyOrRefresh() {
        onEdt(f -> {
            final var before = f.mesh.authoringSnapshot();
            final var history = f.host.history();
            final var target = f.target(1, 1);
            final var result = f.coordinator.execute(f.binding, AuthoringTransactionOptions.of("order noop"), () -> {
                target.setDrawOrder(target.snapshot().drawOrder());
                return null;
            });
            assertEquals(AuthoringTransactionOutcome.NO_CHANGE, result.outcome());
            for (final int order : List.of(-1, 1001, Integer.MIN_VALUE, Integer.MAX_VALUE)) {
                assertThrows(IllegalArgumentException.class, () -> target.setDrawOrder(order));
            }
            final var base = f.target(0, 0);
            assertThrows(UnsupportedOperationException.class, () -> base.setDrawOrder(600));
            assertThrows(
                    UnsupportedOperationException.class,
                    () -> base.setDrawOrder(base.snapshot().drawOrder()));
            assertEquals(before, f.mesh.authoringSnapshot());
            assertEquals(history, f.host.history());
            assertEquals(0, f.host.begins);
            assertEquals(0, f.host.snapshots);
            assertEquals(0, f.host.dirtyMarks);
            assertEquals(0, f.host.updates);
            assertEquals(0, f.host.repaints);
        });
    }

    @Test
    void orderMutationWithProtectedCollateralRemainsRecoveryFailed() {
        onEdt(f -> {
            f.host.nativeAbort = false;
            final var history = f.host.history();
            f.host.afterNextUpdate = () -> f.source.data.forms.get(4).order = 999;
            final var result =
                    f.coordinator.execute(f.binding, AuthoringTransactionOptions.of("order collateral"), () -> {
                        f.target(1, 1).setDrawOrder(600);
                        return null;
                    });
            assertEquals(AuthoringTransactionOutcome.RECOVERY_FAILED, result.outcome());
            assertEquals(999, f.source.data.forms.get(4).order);
            assertEquals(history, f.host.history());
            assertEquals(0, f.host.commits);
        });
    }

    private static void onEdt(final Consumer<Fixture> test) {
        EditorHostThread.dispatch("keyform transaction integration", () -> {
            try {
                test.accept(new Fixture());
                return null;
            } finally {
                NativeHost.CURRENT.remove();
            }
        });
    }

    private static void edt() {
        assertTrue(SwingUtilities.isEventDispatchThread());
    }

    private static List<Point2> positions(final float x) {
        return List.of(new Point2(x, 30), new Point2(x + 10, 30), new Point2(x + 10, 40), new Point2(x, 40));
    }

    private static List<Coordinate> tuple(final float x, final float y) {
        return List.of(new Coordinate("x", x), new Coordinate("y", y));
    }

    private static final class Fixture {
        final NativeSource source = new NativeSource();
        final NativeModel model = new NativeModel(source);
        final NativeHost host = new NativeHost(model);
        final EditorAuthoringTransactionCoordinator.Binding binding = new EditorAuthoringTransactionCoordinator.Binding(
                "keyform-test", "document", 1, "model", 1, Thread.currentThread());
        final EditorAuthoringTransactionCoordinator coordinator = new EditorAuthoringTransactionCoordinator(host);
        final VerifiedMemberResolver resolver = resolver(false);
        final Drawable mesh;

        Fixture() {
            edt();
            NativeHost.CURRENT.set(host);
            source.parent = new NativeSource();
            source.previousParent = source.parent;
            mesh = access(resolver, coordinator)
                    .drawables("model", model, model)
                    .find(ID);
        }

        EditorObjectReadAccess access(final VerifiedMemberResolver r, final EditorAuthoringTransactionCoordinator c) {
            final EditorObjectReadAccess.CurrentGuard guard = (id, value) -> {
                edt();
                if (!"model".equals(id) || value != model) throw new IllegalStateException("stale model");
            };
            return new EditorObjectReadAccess(
                    r,
                    guard,
                    new EditorMorphTargetAccess(r, guard::requireCurrent),
                    null,
                    null,
                    null,
                    c,
                    c == null ? null : () -> binding);
        }

        ArtMeshKeyform target(final float x, final float y) {
            return mesh.ordinaryKeyform(tuple(x, y));
        }
    }

    private static VerifiedMemberResolver resolver(final boolean denyHistory) {
        return resolver(denyHistory, false);
    }

    private static VerifiedMemberResolver resolver(final boolean denyHistory, final boolean denyParent) {
        final var selectors = new LinkedHashMap<String, StaticSelector>();
        for (final var selector : EditorArtMeshAuthoringReadAccessTest.selectors()) {
            final var mapped =
                    selector.ownerInternalName().equals(Source.class.getName().replace('.', '/'))
                            ? method(selector.alias(), NativeSource.class, selector.memberName())
                            : selector;
            selectors.put(mapped.alias(), mapped);
        }
        final List<StaticSelector> wired = List.of(
                StaticSelector.classSelector(
                        M + "parameter-controllable-handler.class",
                        NativeSource.class.getName().replace('.', '/')),
                method(M + "parameter-controllable-source.target-deformer-source", NativeSource.class, "parent"),
                method(M + "parameter-controllable-source.guid", NativeSource.class, "guid"),
                method(
                        M + "parameter-controllable-source.set-target-deformer-guid",
                        NativeSource.class,
                        "setParent",
                        Guid.class),
                method(
                        M + "parameter-controllable-handler.change-target-deformer-guid",
                        NativeSource.class,
                        "changeParent",
                        NativeModel.class,
                        Guid.class,
                        boolean.class),
                method(
                        M + "parameter-controllable-handler.change-target-deformer",
                        NativeSource.class,
                        "changeParentById",
                        NativeModel.class,
                        Guid.class),
                method(M + "model-source.all-deformers", NativeModel.class, "parentSources"),
                StaticSelector.constructor(
                        M + "deformer-id.create",
                        Guid.class.getName().replace('.', '/'),
                        "(Ljava/lang/String;)V",
                        StaticSelector.ACCESS_PUBLIC),
                StaticSelector.field(
                        M + "deformer-guid.companion",
                        ParentCompanion.class.getName().replace('.', '/'),
                        "INSTANCE",
                        "L" + ParentCompanion.class.getName().replace('.', '/') + ";",
                        StaticSelector.ACCESS_PUBLIC | StaticSelector.ACCESS_STATIC),
                method(M + "deformer-guid.root", ParentCompanion.class, "root"),
                StaticSelector.classSelector(
                        M + "art-mesh.class", NativeMesh.class.getName().replace('.', '/')),
                StaticSelector.classSelector(
                        M + "art-mesh-source.class",
                        NativeSource.class.getName().replace('.', '/')),
                method(M + "model-source.all-art-meshes", NativeModel.class, "sources"),
                method(M + "model.all-art-meshes", NativeModel.class, "meshes"),
                method(M + "art-mesh.source", NativeMesh.class, "source"),
                method(M + "parameter-controllable-source.id", NativeSource.class, "id"),
                method(M + "parameter-controllable-source.handler", NativeSource.class, "handler"),
                method(M + "art-mesh-form.set-positions", StoredForm.class, "setPositions", float[].class),
                method(M + "drawable-form.set-draw-order", StoredForm.class, "setDrawOrder", int.class),
                method(
                        M + "parameter-controllable-handler.create-undo-for-keyform-edit",
                        NativeSource.class,
                        "snapshotUndo",
                        String.class),
                method(M + "undo.add", NativeEdit.class, "add", NativeUndo.class, boolean.class),
                method(M + "undo.add-listener", NativeUndo.class, "addListener", NativeListener.class),
                StaticSelector.classSelector(
                        M + "undo-listener.class",
                        NativeListener.class.getName().replace('.', '/')),
                method(M + "model-source.update-instances", NativeModel.class, "updateInstances"),
                StaticSelector.staticMethod(
                        M + "app-controller.instance",
                        NativeHost.class.getName().replace('.', '/'),
                        "instance",
                        "()L" + NativeHost.class.getName().replace('.', '/') + ";",
                        StaticSelector.ACCESS_PUBLIC),
                method(M + "app-controller.complete-pack", NativeHost.class, "completePack"),
                method(M + "complete-pack.update-part-palette", NativeHost.class, "parts", boolean.class),
                method(M + "complete-pack.update-deformer-palette", NativeHost.class, "deformers", boolean.class),
                method(M + "complete-pack.repaint-canvas", NativeHost.class, "repaint", boolean.class));
        wired.forEach(s -> selectors.put(s.alias(), s));
        // Unused legacy surfaces only satisfy their broad gate. Accidental invocation is a test failure.
        final var legacyAliases = new HashSet<>(EditorObjectReadSelectorContract.REQUIRED_ALIASES);
        legacyAliases.addAll(EditorHistoryReadSelectorContract.REQUIRED_ALIASES);
        legacyAliases.addAll(EditorObjectHierarchyEditSelectorContract.REQUIRED_ALIASES);
        legacyAliases.addAll(EditorInspectorDrawableWriteSelectorContract.REQUIRED_ALIASES);
        for (final var alias : legacyAliases) selectors.putIfAbsent(alias, method(alias, Unused.class, "fail"));
        final var capabilities = new HashSet<>(EditorArtMeshAuthoringReadSelectorContract.CAPABILITY_IDS);
        capabilities.add(EditorObjectReadSelectorContract.CAPABILITY_ID);
        if (!denyParent) capabilities.add(EditorObjectHierarchyEditSelectorContract.CAPABILITY_ID);
        capabilities.add(EditorArtMeshKeyformSelectorContract.CAPABILITY_ID);
        if (!denyHistory) capabilities.add("cubism.editor-history.read");
        return TestVerifiedResolvers.create(
                "5.3.02",
                EditorObjectReadSelectorContract.ADAPTER_SLICE_ID,
                capabilities,
                List.copyOf(selectors.values()),
                NativeSource.class.getClassLoader());
    }

    public static final class Unused {
        public Object fail() {
            throw new AssertionError("unexpected legacy selector");
        }
    }

    public static final class ParentCompanion {
        public static final ParentCompanion INSTANCE = new ParentCompanion();

        public Guid root() {
            edt();
            return new Guid("root");
        }
    }

    public static final class NativeSource {
        NativeSource parent;
        NativeSource previousParent;
        boolean nonRootFactoryCalled;

        public NativeUndo changeParentById(final NativeModel model, final Guid id) {
            edt();
            nonRootFactoryCalled = true;
            assertEquals(ID.value(), id.text());
            return changeParent(model, previousParent.guid(), false);
        }

        public NativeSource parent() {
            edt();
            return parent;
        }

        public void setParent(final Guid guid) {
            edt();
            parent = guid.text().equals("root") ? null : previousParent;
        }

        public NativeUndo changeParent(final NativeModel model, final Guid guid, final boolean unused) {
            edt();
            final var undo = new NativeUndo(data.forms);
            final var oldParent = parent;
            undo.afterUndo = () -> parent = oldParent;
            setParent(guid);
            for (var form : data.forms) {
                form.positions = form.positions.clone();
                for (int i = 0; i < form.positions.length; i++) form.positions[i] += 100;
            }
            return undo;
        }

        final Source data = new Source();
        int positionReads;

        NativeSource() {
            data.targets.targets.clear();
            data.forms.removeIf(f -> f.guid.text().startsWith("m"));
            final var last = data.grid.rows.get("f0").remove(1);
            data.grid.rows.put("f8", new ArrayList<>(List.of(last)));
            data.forms.add(new StoredForm("f8", 8));
        }

        public Guid id() {
            edt();
            return new Guid(ID.value());
        }

        public Guid guid() {
            return data.guid();
        }

        public float[] positions() {
            positionReads++;
            return data.positions();
        }

        public float[] uvs() {
            return data.uvs();
        }

        public int[] indices() {
            return data.indices();
        }

        public List<StoredForm> forms() {
            return data.forms();
        }

        public Grid grid() {
            return data.grid();
        }

        public Targets targets() {
            return data.targets();
        }

        public NativeSource handler() {
            edt();
            return this;
        }

        public NativeUndo snapshotUndo(final String label) {
            edt();
            NativeHost.instance().snapshots++;
            return new NativeUndo(data.forms);
        }
    }

    public record NativeMesh(NativeSource source) {
        @Override
        public NativeSource source() {
            edt();
            return source;
        }
    }

    public static final class NativeModel {
        final NativeSource source;
        final NativeMesh mesh;
        boolean present = true;

        NativeModel(final NativeSource source) {
            this.source = source;
            mesh = new NativeMesh(source);
        }

        public List<NativeSource> parentSources() {
            edt();
            return List.of(source.previousParent);
        }

        public List<NativeSource> sources() {
            edt();
            return present ? List.of(source) : List.of();
        }

        public List<NativeMesh> meshes() {
            edt();
            return present ? List.of(mesh) : List.of();
        }

        public void updateInstances() {
            edt();
            final var host = NativeHost.instance();
            host.updates++;
            final var callback = host.afterNextUpdate;
            host.afterNextUpdate = () -> {};
            callback.run();
        }
    }

    public interface NativeListener {
        void changed(Object event);
    }

    public static final class NativeUndo {
        final List<StoredForm> forms;
        final List<float[]> before;
        final List<Integer> beforeOrders;
        final List<NativeListener> listeners = new ArrayList<>();
        Runnable specialUndo;
        Runnable afterUndo = () -> {};

        NativeUndo(final List<StoredForm> forms) {
            this.forms = List.copyOf(forms);
            before = forms.stream().map(f -> f.positions.clone()).toList();
            beforeOrders = forms.stream().map(f -> f.order).toList();
        }

        public void addListener(final NativeListener listener) {
            edt();
            listeners.add(listener);
            NativeHost.instance().listeners++;
        }

        void undo() {
            if (specialUndo != null) {
                specialUndo.run();
                return;
            }
            edt();
            for (int i = 0; i < forms.size(); i++) {
                forms.get(i).positions = before.get(i).clone();
                forms.get(i).order = beforeOrders.get(i);
            }
            afterUndo.run();
            listeners.forEach(l -> l.changed(this));
        }
    }

    public static final class NativeEdit {
        final List<NativeUndo> children = new ArrayList<>();

        public boolean add(final NativeUndo undo, final boolean accepted) {
            edt();
            assertTrue(accepted);
            if (NativeHost.instance().rejectAdmission) return false;
            children.add(undo);
            return true;
        }
    }

    public static final class NativeHost implements EditorAuthoringTransactionCoordinator.Host {
        @Override
        public void undoEditGroup(
                final dev.turboism.adapter.cubism.editor.transaction.EditorAuthoringTransactionCoordinator.Binding
                        expected,
                final Object edit) {}

        static final ThreadLocal<NativeHost> CURRENT = new ThreadLocal<>();
        final NativeModel model;
        final List<HistoryEntry> entries = new ArrayList<>();
        int begins, commits, aborts, snapshots, listeners, dirtyMarks, updates, repaints;
        boolean nativeAbort = true, rejectAdmission;
        String label;
        HistoryEntryDetail detail;
        Runnable afterNextUpdate = () -> {};

        NativeHost(final NativeModel model) {
            this.model = model;
        }

        public static NativeHost instance() {
            edt();
            return CURRENT.get();
        }

        public NativeHost completePack() {
            edt();
            return this;
        }

        public void parts(final boolean update) {
            edt();
            assertTrue(update);
        }

        public void deformers(final boolean update) {
            edt();
            assertTrue(update);
        }

        public void repaint(final boolean update) {
            edt();
            assertTrue(update);
            repaints++;
        }

        @Override
        public boolean isCurrent(final EditorAuthoringTransactionCoordinator.Binding binding) {
            edt();
            return binding.hostThread() == Thread.currentThread()
                    && binding.modelIdentity().equals("model");
        }

        HistorySnapshot history() {
            return new HistorySnapshot(
                    HistorySnapshot.Availability.AVAILABLE,
                    1,
                    commits,
                    entries.size(),
                    List.copyOf(entries),
                    !entries.isEmpty(),
                    false,
                    "document-binding",
                    "manager-binding");
        }

        @Override
        public HistorySnapshot history(final EditorAuthoringTransactionCoordinator.Binding binding) {
            edt();
            return history();
        }

        @Override
        public Object beginEdit(final EditorAuthoringTransactionCoordinator.Binding binding, final String label) {
            edt();
            begins++;
            this.label = label;
            return new NativeEdit();
        }

        @Override
        public void endEdit(
                final EditorAuthoringTransactionCoordinator.Binding binding, final Object edit, final boolean abort) {
            edt();
            if (abort) {
                aborts++;
                if (nativeAbort) {
                    final var children = ((NativeEdit) edit).children;
                    for (int i = children.size() - 1; i >= 0; i--)
                        children.get(i).undo();
                }
            } else {
                commits++;
                entries.add(new HistoryEntry(entries.size(), label, true));
            }
        }

        @Override
        public void refresh(
                final EditorAuthoringTransactionCoordinator.Binding binding,
                final Set<EditorRefreshRequirement> requirements) {
            edt();
            assertTrue(requirements.contains(EditorRefreshRequirement.MARK_DIRTY));
            if (requirements.contains(EditorRefreshRequirement.PART_PALETTE)) parts(true);
            if (requirements.contains(EditorRefreshRequirement.DEFORMER_PALETTE)) deformers(true);
            if (requirements.contains(EditorRefreshRequirement.CANVAS)) repaint(true);
            dirtyMarks++;
        }

        @Override
        public Optional<String> prepareHistoryMetadata(
                final EditorAuthoringTransactionCoordinator.Binding binding,
                final Object edit,
                final String transactionId,
                final HistoryEntryDetail detail,
                final Optional<HistoryAction> action) {
            edt();
            this.detail = detail;
            return Optional.empty();
        }

        @Override
        public Optional<String> committedHistoryEntryId(
                final EditorAuthoringTransactionCoordinator.Binding binding,
                final HistorySnapshot before,
                final HistorySnapshot after,
                final String transactionId,
                final String label) {
            return after.position() == before.position() + 1
                    ? Optional.of("entry-" + after.position())
                    : Optional.empty();
        }

        @Override
        public String diagnosticId(final String code, final Throwable failure) {
            return code;
        }
    }
}
