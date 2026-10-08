package dev.turboism.adapter.cubism.editor;

import dev.turboism.sdk.cubism.model.Point2;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.swing.SwingUtilities;
import java.util.List;
import java.util.function.Consumer;

import static dev.turboism.sdk.cubism.transaction.AuthoringTransactionOutcome.*;
import static org.junit.jupiter.api.Assertions.*;

/** Offline native substitutes exercise the actual coordinator, admission and compensation callbacks. */
class EditorArtMeshMorphTransactionTest {
    static final List<Point2> U = List.of(new Point2(40, 20), new Point2(296, 20), new Point2(296, 148), new Point2(40, 148));
    static final List<Point2> V = List.of(new Point2(80, 40), new Point2(336, 40), new Point2(336, 168), new Point2(80, 168));

    @Test void createWriteCreateWriteCommitsOneEntryAndNativeRedoRestoresMembershipAndAbsoluteValues() throws Exception {
        onEdt(f -> {
            final var before = f.state();
            final var result = f.transaction(() -> {
                f.create(1f).setPositions(U);
                // Interior creation must interpolate base -> existing U, not use U-B as a stored value.
                final var half = f.create(.5f);
                assertEquals(List.of(new Point2(24, 14), new Point2(280, 14), new Point2(280, 142), new Point2(24, 142)), half.snapshot().positions());
                half.setPositions(V);
            });
            assertEquals(COMMITTED, result.outcome()); assertEquals(1, f.position); assertEquals(1, f.begins);
            assertEquals(4, f.snapshots); assertEquals(11, f.source.forms.size());
            final var after = f.state();
            assertEquals(U, f.key(1f).snapshot().positions()); assertEquals(V, f.key(.5f).snapshot().positions());
            for (int i = 0; i < 3; i++) {
                f.undo(); assertTrue(before.sameValues(f.state()));
                f.redo(); assertTrue(after.sameValues(f.state()));
                assertEquals(11, f.source.forms.size()); assertEquals(2, f.source.targets.targets.size());
            }
            assertTrue(before.protectedUnchanged(f.state()));
        });
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void callbackRollbackRebuildsIntermediateMembershipWithBothNativeAbortModels(final boolean nativeAbort) throws Exception {
        onEdt(f -> {
            f.nativeAbort = nativeAbort; final var before = f.state(); final var history = f.history();
            final var result = f.transaction(() -> {
                f.create(.5f).setPositions(U); f.create(1f).setPositions(V);
                throw new IllegalStateException("after four contributions");
            });
            assertEquals(ROLLED_BACK, result.outcome()); assertEquals(4, f.mutations);
            assertTrue(before.sameValues(f.state())); assertEquals(history, f.history());
        });
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void partialEagerCreateIsRecoveredEvenWithoutReturnedUndo(final boolean nativeAbort) throws Exception {
        onEdt(f -> {
            f.nativeAbort = nativeAbort; final var before = f.state();
            final var result = f.transaction(() -> {
                f.create(.5f).setPositions(U); f.partialAdd = true; f.create(1f);
            });
            assertEquals(ROLLED_BACK, result.outcome()); assertEquals(3, f.mutations);
            assertTrue(before.sameValues(f.state())); assertEquals(0, f.position);
        });
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void refreshFailureAfterLastWriteRestoresRoot(final boolean nativeAbort) throws Exception {
        onEdt(f -> {
            f.nativeAbort = nativeAbort; final var before = f.state();
            final var result = f.transaction(() -> {
                f.create(.5f).setPositions(U); f.create(1f);
                f.afterRefresh = () -> { throw new IllegalStateException("refresh failed after write"); };
                f.key(1f).setPositions(V);
            });
            assertEquals(ROLLED_BACK, result.outcome()); assertEquals(4, f.mutations);
            assertTrue(before.sameValues(f.state()));
        });
    }

    @ParameterizedTest @ValueSource(strings = {"snapshot", "listener-false", "listener-throw", "root-false", "root-throw"})
    void failedAdmissionNeverRunsEagerMutation(final String fault) throws Exception {
        onEdt(f -> {
            final var before = f.state();
            switch (fault) {
                case "snapshot" -> f.failSnapshot = true;
                case "listener-false" -> f.rejectListener = true;
                case "listener-throw" -> f.throwListener = true;
                case "root-false" -> f.rejectRoot = true;
                case "root-throw" -> f.throwRoot = true;
                default -> throw new AssertionError(fault);
            }
            assertEquals(ROLLED_BACK, f.transaction(() -> f.create(.5f)).outcome());
            assertEquals(0, f.mutations); assertEquals(0, f.refreshes); assertEquals(0, f.position);
            assertTrue(before.sameValues(f.state()));
        });
    }

    @Test void samePositionsAreNoChangeAndPurePositionUndoInvalidatesUnrelatedTargetRecords() throws Exception {
        onEdt(f -> {
            f.create(.5f); f.key("n", 1f, true);
            final var target = f.key(.5f); final var unrelated = f.key("n", 1f, false);
            final var detached = unrelated.snapshot(); final var history = f.history(); final int snapshots = f.snapshots;
            assertEquals(NO_CHANGE, f.transaction(() -> target.setPositions(target.snapshot().positions())).outcome());
            assertEquals(history, f.history()); assertEquals(snapshots, f.snapshots);
            assertEquals(detached, unrelated.snapshot());
            target.setPositions(U); f.undo();
            assertThrows(IllegalStateException.class, target::snapshot);
            assertThrows(IllegalStateException.class, unrelated::snapshot);
            assertEquals(detached, f.key("n", 1f, false).snapshot());
            f.redo(); assertEquals(U, f.key(.5f).snapshot().positions());
            assertThrows(IllegalStateException.class, target::snapshot);
        });
    }

    @Test void wrongDeltaSeedIsRejectedAndRolledBackRatherThanSelfCertified() throws Exception {
        onEdt(f -> {
            final var before = f.state(); f.wrongSeed = true;
            assertEquals(ROLLED_BACK, f.transaction(() -> f.create(.5f)).outcome());
            assertTrue(before.sameValues(f.state())); assertEquals(0, f.position);
        });
    }

    @Test void seedInterpolatesAllBlendableFieldsAndKeepsBaseCoordinates() throws Exception {
        onEdt(f -> {
            final var base = f.source.forms.get(4);
            base.order = 151; base.opacity = .25f;
            base.multiply = new EditorArtMeshMorphFixture.Color(.25f, .5f, .75f, 1f);
            base.screen = new EditorArtMeshMorphFixture.Color(0f, .25f, .5f, .75f);
            base.notes = "base-notes";
            f.create(1f);
            final var endpoint = f.source.forms.get(9);
            assertEquals(151, endpoint.order); assertEquals(.25f, endpoint.opacity);
            endpoint.order = 952; endpoint.opacity = .75f;
            endpoint.multiply = new EditorArtMeshMorphFixture.Color(.75f, 0f, .25f, .5f);
            endpoint.screen = new EditorArtMeshMorphFixture.Color(.5f, .75f, 1f, .25f);
            endpoint.coordinate = "DeformerLocal";
            f.create(.5f);
            final var half = f.source.forms.get(10);
            assertEquals(551, half.order); // Native postBlend truncates 551.5; it does not round to 552.
            assertEquals(.5f, half.opacity);
            assertEquals(new EditorArtMeshMorphFixture.Color(.5f, .25f, .5f, .75f), half.multiply);
            assertEquals(new EditorArtMeshMorphFixture.Color(.25f, .5f, .75f, .5f), half.screen);
            assertEquals("Canvas", half.coordinate); assertEquals("base-notes", half.notes);
            final var before = f.state(); f.key(.5f).setPositions(U); f.undo();
            assertTrue(before.sameValues(f.state()));
        });
    }

    @ParameterizedTest @ValueSource(strings = {"source", "default", "ordinary-opacity", "constraints", "document", "double-add"})
    void ProtectionDamageIsNeverSilentlyRepairedOrReportedRolledBack(final String fault) throws Exception {
        onEdt(f -> {
            if (fault.equals("double-add")) f.doubleAdd = true;
            else f.afterMutation = () -> {
                switch (fault) {
                    case "source" -> f.source.positions[0] += 10;
                    case "default" -> f.source.forms.get(4).positions[0] += 10;
                    case "ordinary-opacity" -> f.source.forms.get(0).opacity = .2f;
                    case "constraints" -> f.source.targets.constraints.entries.add(new Object());
                    case "document" -> f.currentSource = new Object();
                    default -> throw new AssertionError(fault);
                }
            };
            assertEquals(RECOVERY_FAILED, f.transaction(() -> f.create(.5f)).outcome());
            assertEquals(0, f.position);
            if (fault.equals("source")) assertEquals(18f, f.source.positions[0]);
            if (fault.equals("default")) assertEquals(18f, f.source.forms.get(4).positions[0]);
            if (fault.equals("ordinary-opacity")) assertEquals(.2f, f.source.forms.get(0).opacity);
        });
    }

    @Test void seedPreservesNativeFloatRoundTripRatherThanNaiveLerp() throws Exception {
        onEdt(f -> {
            final var base = f.source.forms.get(4);
            java.util.Arrays.fill(base.positions, 512f);
            base.opacity = .1f;
            base.multiply = new EditorArtMeshMorphFixture.Color(.1f, .1f, .1f, .1f);
            f.create(1f).setPositions(java.util.Collections.nCopies(4, new Point2(.3f, .3f)));
            final var endpoint = f.source.forms.get(9);
            endpoint.opacity = .3f;
            endpoint.multiply = new EditorArtMeshMorphFixture.Color(.3f, .3f, .3f, .3f);
            final var half = f.create(.5f);
            assertEquals(java.util.Collections.nCopies(4, new Point2(256.1500244140625f, 256.1500244140625f)), half.snapshot().positions());
            final var attrs = f.state().attributes.get(half.snapshot().formGuid());
            assertEquals(.20000002f, attrs.opacity());
            assertEquals(List.of(.20000002f, .20000002f, .20000002f, .20000002f), attrs.multiply());
        });
    }

    @ParameterizedTest @ValueSource(strings = {"order", "opacity", "multiply", "screen", "coordinate"})
    void incorrectNewSeedAttributesMustNotCommit(final String fault) throws Exception {
        onEdt(f -> {
            final var before = f.state();
            f.afterMutation = () -> {
                final var added = f.source.forms.get(f.source.forms.size() - 1);
                switch (fault) {
                    case "order" -> added.order = 499;
                    case "opacity" -> added.opacity = .5f;
                    case "multiply" -> added.multiply = new EditorArtMeshMorphFixture.Color(.5f, 1, 1, 1);
                    case "screen" -> added.screen = new EditorArtMeshMorphFixture.Color(0, .5f, 0, 1);
                    case "coordinate" -> added.coordinate = "DeformerLocal";
                    default -> throw new AssertionError(fault);
                }
            };
            assertEquals(ROLLED_BACK, f.transaction(() -> f.create(.5f)).outcome());
            assertTrue(before.sameValues(f.state())); assertEquals(0, f.position);
        });
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void nativeCancellationAfterPreflightLeavesNoNewMemberOrHistory(final boolean nativeAbort) throws Exception {
        onEdt(f -> {
            f.nativeAbort = nativeAbort;
            final var before = f.state(); final var history = f.history();
            assertEquals(ROLLED_BACK, f.transaction(() -> {
                f.create(.5f).setPositions(U);
                f.forceNativeNull = true;
                f.create(1f);
            }).outcome());
            assertEquals(3, f.mutations);
            assertTrue(before.sameValues(f.state())); assertEquals(history, f.history());
        });
    }

    @Test void drawOrderWriteCommitsOneEntryAndUndoRedoRestoresAbsoluteValue() throws Exception {
        onEdt(f -> {
            f.create(.5f); final var before = f.state();
            final var result = f.transaction(() -> f.key(.5f).setDrawOrder(750));
            assertEquals(COMMITTED, result.outcome()); assertEquals(2, f.position); assertEquals(2, f.begins);
            final var after = f.state();
            assertEquals(750, f.key(.5f).snapshot().drawOrder());
            for (int i = 0; i < 3; i++) {
                f.undo(); assertTrue(before.sameValues(f.state()));
                assertEquals(500, f.key(.5f).snapshot().drawOrder());
                f.redo(); assertTrue(after.sameValues(f.state()));
                assertEquals(750, f.key(.5f).snapshot().drawOrder());
            }
            assertTrue(before.protectedUnchanged(f.state()));
        });
    }

    @Test void mixedCreateOrderCreatePositionsAndOrderCommitOneEntry() throws Exception {
        onEdt(f -> {
            final var before = f.state();
            final var result = f.transaction(() -> {
                f.create(1f).setDrawOrder(700);
                final var half = f.create(.5f);
                // The .5 seed interpolates the stored absolute orders 500 -> 700, not a delta.
                assertEquals(600, half.snapshot().drawOrder());
                half.setPositions(V);
                f.key(1f).setDrawOrder(900);
            });
            assertEquals(COMMITTED, result.outcome()); assertEquals(1, f.position); assertEquals(1, f.begins);
            assertEquals(5, f.mutations); assertEquals(5, f.snapshots);
            final var after = f.state();
            assertEquals(900, f.key(1f).snapshot().drawOrder());
            assertEquals(600, f.key(.5f).snapshot().drawOrder());
            assertEquals(V, f.key(.5f).snapshot().positions());
            for (int i = 0; i < 3; i++) {
                f.undo(); assertTrue(before.sameValues(f.state()));
                f.redo(); assertTrue(after.sameValues(f.state()));
            }
            assertTrue(before.protectedUnchanged(f.state()));
        });
    }

    @Test void sameDrawOrderIsNoChangeAndPureOrderUndoInvalidatesUnrelatedReferences() throws Exception {
        onEdt(f -> {
            f.create(.5f); f.key("n", 1f, true);
            final var target = f.key(.5f); final var unrelated = f.key("n", 1f, false);
            final var detached = unrelated.snapshot(); final var history = f.history(); final int snapshots = f.snapshots;
            assertEquals(NO_CHANGE, f.transaction(() -> target.setDrawOrder(target.snapshot().drawOrder())).outcome());
            assertEquals(history, f.history()); assertEquals(snapshots, f.snapshots);
            assertEquals(detached, unrelated.snapshot());
            target.setDrawOrder(700);
            assertDoesNotThrow(target::snapshot); assertDoesNotThrow(unrelated::snapshot);
            f.undo();
            assertThrows(IllegalStateException.class, target::snapshot);
            assertThrows(IllegalStateException.class, unrelated::snapshot);
            assertEquals(detached, f.key("n", 1f, false).snapshot());
            assertEquals(500, f.key(.5f).snapshot().drawOrder());
            f.redo(); assertEquals(700, f.key(.5f).snapshot().drawOrder());
            assertThrows(IllegalStateException.class, target::snapshot);
        });
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void orderAndPositionWritesRollBackTogetherOnCallbackFailure(final boolean nativeAbort) throws Exception {
        onEdt(f -> {
            f.nativeAbort = nativeAbort;
            f.create(.5f); f.key("n", 1f, true);
            final var before = f.state(); final var history = f.history(); final int mutations = f.mutations;
            final var target = f.key(.5f); final var other = f.key("n", 1f, false);
            final var result = f.transaction(() -> {
                target.setDrawOrder(700); target.setPositions(U);
                other.setPositions(V); other.setDrawOrder(300);
                throw new IllegalStateException("after four contributions");
            });
            assertEquals(ROLLED_BACK, result.outcome()); assertEquals(mutations + 4, f.mutations);
            assertTrue(before.sameValues(f.state())); assertEquals(history, f.history());
            assertEquals(500, f.key(.5f).snapshot().drawOrder());
            assertEquals(500, f.key("n", 1f, false).snapshot().drawOrder());
        });
    }

    /** Explicit 16 legs: write order, target selection, native abort model, failure leg. */
    @ParameterizedTest @ValueSource(ints = {0,1,2,3,4,5,6,7,8,9,10,11,12,13,14,15})
    void orderPositionPairMatrixRestoresRootUnderEveryAbortAndFailureLeg(final int combination) throws Exception {
        final boolean orderFirst = (combination & 1) != 0;
        final boolean sameTarget = (combination & 2) != 0;
        final boolean nativeAbort = (combination & 4) != 0;
        final boolean refreshFailure = (combination & 8) != 0;
        onEdt(f -> {
            f.nativeAbort = nativeAbort;
            f.create(.5f); f.key("n", 1f, true);
            final var before = f.state(); final var history = f.history();
            final int mutations = f.mutations, snapshots = f.snapshots, begins = f.begins;
            final var a = f.key(.5f);
            final var b = sameTarget ? a : f.key("n", 1f, false);
            final String firstLog = (orderFirst ? "order:" : "positions:") + a.snapshot().formGuid();
            final String secondLog = (orderFirst ? "positions:" : "order:") + b.snapshot().formGuid();
            final int secondIndex = sameTarget ? 9 : 10;
            final int[] observed = new int[2];
            final var result = f.transaction(() -> {
                if (orderFirst) {
                    a.setDrawOrder(700);
                    if (refreshFailure) f.afterRefresh = () -> { throw new IllegalStateException("second write refresh"); };
                    b.setPositions(U);
                } else {
                    a.setPositions(U);
                    if (refreshFailure) f.afterRefresh = () -> { throw new IllegalStateException("second write refresh"); };
                    b.setDrawOrder(300);
                }
                // Callback leg only: the second write already committed its native mutation.
                observed[0] = f.writeLog.size();
                observed[1] = orderFirst ? (int) f.source.forms.get(secondIndex).positions[0]
                    : f.source.forms.get(secondIndex).order;
                throw new IllegalStateException("sentinel after second write");
            });
            assertEquals(ROLLED_BACK, result.outcome());
            // Exactly two effective writes, in the declared order and on the declared targets.
            assertEquals(List.of(firstLog, secondLog), f.writeLog);
            assertEquals(mutations + 2, f.mutations); assertEquals(snapshots + 2, f.snapshots);
            assertEquals(begins + 1, f.begins);
            if (!refreshFailure) {
                assertEquals(2, observed[0]);
                assertEquals(orderFirst ? 40 : 300, observed[1]);
            }
            assertTrue(before.sameValues(f.state())); assertEquals(history, f.history());
            assertEquals(500, f.key(.5f).snapshot().drawOrder());
            assertEquals(f.key(.5f).baseSnapshot().positions(), f.key(.5f).snapshot().positions());
            assertEquals(500, f.key("n", 1f, false).snapshot().drawOrder());
            assertEquals(f.key("n", 1f, false).baseSnapshot().positions(), f.key("n", 1f, false).snapshot().positions());
        });
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void refreshFailureAfterOrderWriteRestoresRoot(final boolean nativeAbort) throws Exception {
        onEdt(f -> {
            f.nativeAbort = nativeAbort;
            f.create(.5f); final var before = f.state(); final int mutations = f.mutations;
            final var target = f.key(.5f);
            final var result = f.transaction(() -> {
                target.setDrawOrder(700);
                f.afterRefresh = () -> { throw new IllegalStateException("refresh failed after write"); };
                target.setPositions(U);
            });
            assertEquals(ROLLED_BACK, result.outcome()); assertEquals(mutations + 2, f.mutations);
            assertTrue(before.sameValues(f.state()));
            assertEquals(500, f.key(.5f).snapshot().drawOrder());
        });
    }

    @ParameterizedTest @ValueSource(strings = {"snapshot", "listener-false", "listener-throw", "root-false", "root-throw"})
    void failedAdmissionNeverRunsEagerOrderMutation(final String fault) throws Exception {
        onEdt(f -> {
            final var target = f.create(.5f);
            final var before = f.state(); final int mutations = f.mutations;
            switch (fault) {
                case "snapshot" -> f.failSnapshot = true;
                case "listener-false" -> f.rejectListener = true;
                case "listener-throw" -> f.throwListener = true;
                case "root-false" -> f.rejectRoot = true;
                case "root-throw" -> f.throwRoot = true;
                default -> throw new AssertionError(fault);
            }
            assertEquals(ROLLED_BACK, f.transaction(() -> target.setDrawOrder(700)).outcome());
            assertEquals(mutations, f.mutations);
            assertTrue(before.sameValues(f.state()));
        });
    }

    @ParameterizedTest @ValueSource(strings = {"target-positions", "target-opacity", "target-multiply", "target-screen",
        "other-morph-order", "other-morph-positions", "other-morph-opacity", "new-member"})
    void morphDomainDamageDuringOrderWriteIsRolledBackNotCommitted(final String fault) throws Exception {
        onEdt(f -> {
            f.create(.5f); f.key("n", 1f, true);
            final var before = f.state(); final var target = f.key(.5f);
            f.afterMutation = () -> {
                switch (fault) {
                    case "target-positions" -> f.source.forms.get(9).positions[0] += 10;
                    case "target-opacity" -> f.source.forms.get(9).opacity = .2f;
                    case "target-multiply" -> f.source.forms.get(9).multiply =
                        new EditorArtMeshMorphFixture.Color(.5f, 1, 1, 1);
                    case "target-screen" -> f.source.forms.get(9).screen =
                        new EditorArtMeshMorphFixture.Color(0, .5f, 0, 1);
                    case "other-morph-order" -> f.source.forms.get(10).order = 777;
                    case "other-morph-positions" -> f.source.forms.get(10).positions[0] += 10;
                    case "other-morph-opacity" -> f.source.forms.get(10).opacity = .3f;
                    case "new-member" -> f.source.forms.add(new EditorArtMeshMorphFixture.NativeForm(f.source, "extra", f.source.positions));
                    default -> throw new AssertionError(fault);
                }
            };
            assertEquals(ROLLED_BACK, f.transaction(() -> target.setDrawOrder(700)).outcome());
            assertTrue(before.sameValues(f.state()));
            assertEquals(500, f.key(.5f).snapshot().drawOrder());
            assertEquals(500, f.key("n", 1f, false).snapshot().drawOrder());
        });
    }

    @ParameterizedTest @ValueSource(strings = {"source", "ordinary-order", "ordinary-opacity", "parameter-value", "removed-member"})
    void protectedDamageDuringOrderWriteIsRecoveryFailed(final String fault) throws Exception {
        onEdt(f -> {
            f.create(.5f); f.key("n", 1f, true);
            final var target = f.key(.5f);
            f.afterMutation = () -> {
                switch (fault) {
                    case "source" -> f.source.positions[0] += 10;
                    case "ordinary-order" -> f.source.forms.get(0).order = 777;
                    case "ordinary-opacity" -> f.source.forms.get(0).opacity = .2f;
                    case "parameter-value" -> f.model.parameters.get(2).value = .5f;
                    // A dangling morph reference is structural corruption the snapshot contract
                    // fail-closes on; recovery is reported honestly, never silently repaired.
                    case "removed-member" -> f.source.forms.remove(9);
                    default -> throw new AssertionError(fault);
                }
            };
            assertEquals(RECOVERY_FAILED, f.transaction(() -> target.setDrawOrder(700)).outcome());
            if (fault.equals("source")) assertEquals(18f, f.source.positions[0]);
            if (fault.equals("ordinary-order")) assertEquals(777, f.source.forms.get(0).order);
            if (fault.equals("ordinary-opacity")) assertEquals(.2f, f.source.forms.get(0).opacity);
            if (fault.equals("parameter-value")) assertEquals(.5f, f.model.parameters.get(2).value);
            if (fault.equals("removed-member")) assertEquals(10, f.source.forms.size());
        });
    }

    static void onEdt(final Consumer<EditorArtMeshMorphFixture> action) throws Exception {
        SwingUtilities.invokeAndWait(() -> { try { action.accept(new EditorArtMeshMorphFixture()); }
            finally { EditorArtMeshMorphFixture.CURRENT.remove(); } });
    }
}
