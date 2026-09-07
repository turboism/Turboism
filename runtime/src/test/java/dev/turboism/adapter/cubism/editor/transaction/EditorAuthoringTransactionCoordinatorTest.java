package dev.turboism.adapter.cubism.editor.transaction;

import dev.turboism.sdk.cubism.history.HistoryAction;
import dev.turboism.sdk.cubism.history.HistoryEntryDetail;
import dev.turboism.sdk.cubism.history.HistoryEntry;
import dev.turboism.sdk.cubism.history.HistorySnapshot;
import dev.turboism.sdk.cubism.transaction.AuthoringTransactionOptions;
import dev.turboism.sdk.cubism.transaction.AuthoringTransactionOutcome;
import dev.turboism.sdk.cubism.transaction.AuthoringTransactionResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class EditorAuthoringTransactionCoordinatorTest {

    @Test
    void capturesEachActualAfterBeforeTheNextPrimitiveMutatesTheSameTarget() {
        final BindingFixture fixture = new BindingFixture();
        final AtomicInteger value = new AtomicInteger();
        final List<Integer> observed = new ArrayList<>();
        final var result = fixture.coordinator.execute(fixture.binding,
            AuthoringTransactionOptions.of("Captured group"), () -> {
                fixture.coordinator.mutate(fixture.binding, capturedContribution(value, 0, 1)
                    .withCaptureAfter(() -> { observed.add(value.get()); return captureDetail(0, value.get()); }));
                fixture.coordinator.mutate(fixture.binding, capturedContribution(value, 1, 2)
                    .withCaptureAfter(() -> { observed.add(value.get()); return captureDetail(1, value.get()); }));
                return null;
            });
        assertEquals(AuthoringTransactionOutcome.COMMITTED, result.outcome());
        assertEquals(List.of(1, 2), observed);
        assertEquals(fixture.host.lastSemanticDetail, fixture.host.detailObservedAtEnd);
        final var detail = fixture.host.lastSemanticDetail.orElseThrow();
        assertEquals(List.of("1", "2"), detail.group().orElseThrow().children().stream()
            .map(child -> child.changes().get(0).after().orElseThrow()).toList());
        value.set(3);
        assertEquals("1", detail.group().orElseThrow().children().get(0).changes().get(0).after().orElseThrow());
    }

    @Test
    void readbackFailureDegradesWithoutRollingBackValidMutation() {
        final BindingFixture fixture = new BindingFixture();
        final AtomicInteger value = new AtomicInteger();
        fixture.coordinator.mutate(fixture.binding, capturedContribution(value, 0, 1)
            .withCaptureAfter(() -> { throw new IllegalStateException("readback unavailable"); }));
        assertEquals(1, value.get());
        assertEquals(1, fixture.host.commitCount);
        final var detail = fixture.host.lastSemanticDetail.orElseThrow();
        assertEquals(HistoryAction.DetailLevel.PARTIAL, detail.detailLevel());
        assertEquals(Optional.empty(), detail.changes().get(0).after());
        assertEquals(Optional.of("history.capture.after-unavailable"), detail.degradationCode());
    }

    @Test
    void changedBeforeIdentityFromReadbackCannotReplaceCapturedBefore() {
        final BindingFixture fixture = new BindingFixture();
        final AtomicInteger value = new AtomicInteger();
        fixture.coordinator.mutate(fixture.binding, capturedContribution(value, 0, 1)
            .withCaptureAfter(() -> captureDetail(99, value.get())));
        final var detail = fixture.host.lastSemanticDetail.orElseThrow();
        assertEquals(HistoryAction.DetailLevel.PARTIAL, detail.detailLevel());
        assertEquals(Optional.of("0"), detail.changes().get(0).before());
        assertEquals(Optional.empty(), detail.changes().get(0).after());
    }

    @Test
    void preparedIdentityMustActuallyAppearInCommittedHistory() {
        final BindingFixture fixture = new BindingFixture();
        fixture.host.preparedId = Optional.of("not-in-history");
        final AtomicInteger value = new AtomicInteger();
        final var result = fixture.coordinator.execute(fixture.binding,
            AuthoringTransactionOptions.of("Root mismatch"), () -> {
                fixture.coordinator.mutate(fixture.binding, capturedContribution(value, 0, 1)
                    .withCaptureAfter(() -> captureDetail(0, value.get())));
                return null;
            });
        assertEquals(AuthoringTransactionOutcome.RECOVERY_FAILED, result.outcome());
        assertEquals(Optional.empty(), result.receipt().orElseThrow().historyEntryId());
        assertEquals(Optional.empty(), fixture.host.lastSemanticDetail);
    }

    private static EditorUndoContribution capturedContribution(final AtomicInteger value, final int before, final int actual) {
        return new EditorUndoContribution("test.capture", "target-1", "Capture",
            (edit, label) -> { }, () -> value.set(actual), () -> value.get() == actual,
            () -> value.set(before), () -> value.get() == before, Set.of(), captureDetail(before, 999));
    }

    private static HistoryEntryDetail captureDetail(final int before, final int after) {
        return new HistoryEntryDetail("Capture", HistoryAction.DetailLevel.FULL,
            dev.turboism.sdk.cubism.history.HistoryOrigin.turboism("plugin.test", "test.capture"),
            List.of(new dev.turboism.sdk.cubism.history.HistoryTarget("PARAMETER", Optional.of("ParamX"), Optional.empty())),
            List.of(dev.turboism.sdk.cubism.history.HistoryChange.set(0, "value", Integer.toString(before), Integer.toString(after))),
            Optional.empty(), Optional.empty());
    }

    @Test
    void readOnlyCallbackCreatesNoNativeEditAndNoHistoryEntry() {
        final BindingFixture fixture = new BindingFixture();

        final AuthoringTransactionResult<Integer> result = fixture.coordinator.execute(
            fixture.binding,
            AuthoringTransactionOptions.of("Read only"),
            () -> 7
        );

        assertEquals(AuthoringTransactionOutcome.NO_CHANGE, result.outcome());
        assertEquals(Optional.of(7), result.value());
        assertEquals(0, fixture.host.beginCount);
        assertEquals(0, fixture.host.commitCount);
        assertEquals(0, fixture.host.abortCount);
        assertEquals(0, fixture.host.refreshCount);
        assertEquals(
            result.receipt().orElseThrow().historyBefore(),
            result.receipt().orElseThrow().historyAfter()
        );
    }

    @Test
    void firstChangedContributionLazilyOpensOneEditAndLaterContributionsJoinIt() {
        final BindingFixture fixture = new BindingFixture();
        final AtomicInteger value = new AtomicInteger();
        final List<Object> admittedEdits = new ArrayList<>();

        final AuthoringTransactionResult<Integer> result = fixture.coordinator.execute(
            fixture.binding,
            AuthoringTransactionOptions.of("Adjust eye glues"),
            () -> {
                fixture.coordinator.mutate(
                    fixture.binding,
                    contribution("left", value, 0, 1, admittedEdits, true)
                );
                fixture.coordinator.mutate(
                    fixture.binding,
                    contribution("right", value, 1, 2, admittedEdits, true)
                );
                return value.get();
            }
        );

        assertEquals(AuthoringTransactionOutcome.COMMITTED, result.outcome());
        assertEquals(Optional.of(2), result.value());
        assertEquals(1, fixture.host.beginCount);
        assertEquals(1, fixture.host.commitCount);
        assertEquals(0, fixture.host.abortCount);
        assertEquals(1, fixture.host.refreshCount);
        assertEquals(2, admittedEdits.size());
        assertSame(admittedEdits.get(0), admittedEdits.get(1));
        assertEquals(
            EnumSet.of(
                EditorRefreshRequirement.MODEL_INSTANCES,
                EditorRefreshRequirement.MARK_DIRTY,
                EditorRefreshRequirement.CANVAS
            ),
            fixture.host.lastRefresh
        );
        assertTrue(result.receipt().orElseThrow().historyEntryId().isPresent());
        assertNotEquals(
            result.receipt().orElseThrow().historyBefore(),
            result.receipt().orElseThrow().historyAfter()
        );
        assertEquals(
            HistoryAction.DetailLevel.PARTIAL,
            fixture.host.lastSemanticDetail.orElseThrow().detailLevel()
        );
        assertEquals(2, fixture.host.lastSemanticDetail.orElseThrow().targets().size());
        assertEquals(2, fixture.host.lastSemanticDetail.orElseThrow()
            .group().orElseThrow().children().size());
    }

    @Test
    void standaloneContributionUsesTheSameRootScopeSemantics() {
        final BindingFixture fixture = new BindingFixture();
        final AtomicInteger value = new AtomicInteger();

        fixture.coordinator.mutate(
            fixture.binding,
            contribution("standalone", value, 0, 1, new ArrayList<>(), true)
        );

        assertEquals(1, value.get());
        assertEquals(1, fixture.host.beginCount);
        assertEquals(1, fixture.host.commitCount);
        assertEquals(1, fixture.host.history().entries().size());
    }

    @Test
    void nestedRootIsRejectedWithoutInvokingItsCallback() {
        final BindingFixture fixture = new BindingFixture();
        final AtomicBoolean nestedInvoked = new AtomicBoolean();

        final AuthoringTransactionResult<AuthoringTransactionResult<String>> outer =
            fixture.coordinator.execute(
                fixture.binding,
                AuthoringTransactionOptions.of("Outer"),
                () -> fixture.coordinator.execute(
                    fixture.binding,
                    AuthoringTransactionOptions.of("Nested"),
                    () -> {
                        nestedInvoked.set(true);
                        return "unexpected";
                    }
                )
            );

        assertEquals(AuthoringTransactionOutcome.NO_CHANGE, outer.outcome());
        assertEquals(
            AuthoringTransactionOutcome.REJECTED_SCOPE,
            outer.value().orElseThrow().outcome()
        );
        assertFalse(nestedInvoked.get());
        assertEquals(0, fixture.host.beginCount);
    }

    @Test
    void changedCallbackFailureAbortsAndCompensatesInReverseOrder() {
        final BindingFixture fixture = new BindingFixture();
        final AtomicInteger value = new AtomicInteger();
        final List<String> compensationOrder = new ArrayList<>();

        final AuthoringTransactionResult<Void> result = fixture.coordinator.execute(
            fixture.binding,
            AuthoringTransactionOptions.of("Fail and restore"),
            () -> {
                fixture.coordinator.mutate(
                    fixture.binding,
                    contribution(
                        "first", value, 0, 1, new ArrayList<>(), true,
                        compensationOrder
                    )
                );
                fixture.coordinator.mutate(
                    fixture.binding,
                    contribution(
                        "second", value, 1, 2, new ArrayList<>(), true,
                        compensationOrder
                    )
                );
                throw new IllegalStateException("callback failed");
            }
        );

        assertEquals(AuthoringTransactionOutcome.ROLLED_BACK, result.outcome());
        assertEquals(0, value.get());
        assertEquals(List.of("second", "first"), compensationOrder);
        assertEquals(1, fixture.host.abortCount);
        assertEquals(0, fixture.host.commitCount);
        assertEquals(0, fixture.host.history().entries().size());
    }

    @Test
    void unverifiableCompensationReturnsRecoveryFailed() {
        final BindingFixture fixture = new BindingFixture();
        final AtomicInteger value = new AtomicInteger();

        final AuthoringTransactionResult<Void> result = fixture.coordinator.execute(
            fixture.binding,
            AuthoringTransactionOptions.of("Failed recovery"),
            () -> {
                fixture.coordinator.mutate(
                    fixture.binding,
                    contribution("broken", value, 0, 1, new ArrayList<>(), false)
                );
                throw new IllegalStateException("callback failed");
            }
        );

        assertEquals(AuthoringTransactionOutcome.RECOVERY_FAILED, result.outcome());
        assertEquals(1, value.get());
        assertEquals(Optional.of("authoring.recovery-failed"), result.diagnosticId());
        assertEquals(1, fixture.host.abortCount);
    }

    @Test
    void providerParticipationMayUseAnInternalOwnerInsideThePluginRoot() {
        final BindingFixture fixture = new BindingFixture();
        final AtomicInteger value = new AtomicInteger();
        final EditorAuthoringTransactionCoordinator.Binding provider =
            new EditorAuthoringTransactionCoordinator.Binding(
                "runtime.editor-model",
                fixture.binding.documentIdentity(),
                fixture.binding.documentGeneration(),
                fixture.binding.modelIdentity(),
                fixture.binding.modelGeneration(),
                Thread.currentThread()
            );

        final AuthoringTransactionResult<Void> result = fixture.coordinator.execute(
            fixture.binding,
            AuthoringTransactionOptions.of("Provider child"),
            () -> {
                fixture.coordinator.mutate(
                    provider,
                    contribution("provider", value, 0, 1, new ArrayList<>(), true)
                );
                return null;
            }
        );

        assertEquals(AuthoringTransactionOutcome.COMMITTED, result.outcome());
        assertEquals(1, value.get());
        assertEquals(1, fixture.host.beginCount);
        assertEquals(1, fixture.host.commitCount);
    }

    @Test
    void mismatchedDocumentParticipationRejectsAndDoesNotOpenAnotherEdit() {
        final BindingFixture fixture = new BindingFixture();
        final AtomicInteger value = new AtomicInteger();
        final EditorAuthoringTransactionCoordinator.Binding other =
            new EditorAuthoringTransactionCoordinator.Binding(
                "runtime.editor-model",
                "document-2",
                fixture.binding.documentGeneration(),
                fixture.binding.modelIdentity(),
                fixture.binding.modelGeneration(),
                Thread.currentThread()
            );

        final AuthoringTransactionResult<Void> result = fixture.coordinator.execute(
            fixture.binding,
            AuthoringTransactionOptions.of("Mismatched child"),
            () -> {
                fixture.coordinator.mutate(
                    other,
                    contribution("wrong", value, 0, 1, new ArrayList<>(), true)
                );
                return null;
            }
        );

        assertEquals(AuthoringTransactionOutcome.REJECTED_SCOPE, result.outcome());
        assertEquals(0, value.get());
        assertEquals(0, fixture.host.beginCount);
    }

    @Test
    void uncertainNativeEndFailureIsNeverRetried() {
        final BindingFixture fixture = new BindingFixture();
        fixture.host.failEnd = true;
        final AtomicInteger value = new AtomicInteger();

        final AuthoringTransactionResult<Void> result = fixture.coordinator.execute(
            fixture.binding,
            AuthoringTransactionOptions.of("Uncertain native close"),
            () -> {
                fixture.coordinator.mutate(
                    fixture.binding,
                    contribution("changed", value, 0, 1, new ArrayList<>(), true)
                );
                return null;
            }
        );

        assertEquals(AuthoringTransactionOutcome.RECOVERY_FAILED, result.outcome());
        assertEquals(1, fixture.host.endAttempts);
    }

    @Test
    void unexpectedHistoryShapeAfterCommitReturnsRecoveryFailed() {
        final BindingFixture fixture = new BindingFixture();
        fixture.host.entriesPerCommit = 2;
        final AtomicInteger value = new AtomicInteger();

        final AuthoringTransactionResult<Void> result = fixture.coordinator.execute(
            fixture.binding,
            AuthoringTransactionOptions.of("Ambiguous history"),
            () -> {
                fixture.coordinator.mutate(
                    fixture.binding,
                    contribution("changed", value, 0, 1, new ArrayList<>(), true)
                );
                return null;
            }
        );

        assertEquals(AuthoringTransactionOutcome.RECOVERY_FAILED, result.outcome());
        assertEquals(Optional.of("authoring.history-unverified"), result.diagnosticId());
        assertEquals(1, fixture.host.commitCount);
    }

    private static EditorUndoContribution contribution(
        final String id,
        final AtomicInteger value,
        final int before,
        final int after,
        final List<Object> admittedEdits,
        final boolean recover
    ) {
        return contribution(id, value, before, after, admittedEdits, recover, new ArrayList<>());
    }

    private static EditorUndoContribution contribution(
        final String id,
        final AtomicInteger value,
        final int before,
        final int after,
        final List<Object> admittedEdits,
        final boolean recover,
        final List<String> compensationOrder
    ) {
        return new EditorUndoContribution(
            "test.write." + id,
            "target-" + id,
            "Set " + id,
            (edit, label) -> admittedEdits.add(edit),
            () -> value.set(after),
            () -> value.get() == after,
            () -> {
                compensationOrder.add(id);
                if (recover) value.set(before);
            },
            () -> value.get() == before,
            EnumSet.of(
                EditorRefreshRequirement.MODEL_INSTANCES,
                EditorRefreshRequirement.MARK_DIRTY,
                EditorRefreshRequirement.CANVAS
            )
        );
    }

    private static final class BindingFixture {
        final EditorAuthoringTransactionCoordinator.Binding binding =
            new EditorAuthoringTransactionCoordinator.Binding(
                "plugin.test",
                "document-1",
                1,
                "model-1",
                1,
                Thread.currentThread()
            );
        final FakeHost host = new FakeHost(binding);
        final EditorAuthoringTransactionCoordinator coordinator =
            new EditorAuthoringTransactionCoordinator(host);
    }

    private static final class FakeHost implements EditorAuthoringTransactionCoordinator.Host {
        private final EditorAuthoringTransactionCoordinator.Binding binding;
        private final List<HistoryEntry> entries = new ArrayList<>();
        private long revision;
        private String currentLabel = "";
        private int beginCount;
        private int commitCount;
        private int abortCount;
        private int endAttempts;
        private boolean failEnd;
        private int refreshCount;
        private Optional<HistoryEntryDetail> lastSemanticDetail = Optional.empty();
        private Optional<HistoryEntryDetail> preparedDetail = Optional.empty();
        private Optional<HistoryEntryDetail> detailObservedAtEnd = Optional.empty();
        private Optional<String> preparedId = Optional.empty();
        private Optional<HistoryAction> lastSemanticAction = Optional.empty();
        private int entriesPerCommit = 1;
        private Set<EditorRefreshRequirement> lastRefresh = Set.of();

        FakeHost(final EditorAuthoringTransactionCoordinator.Binding binding) {
            this.binding = binding;
        }

        @Override
        public boolean isCurrent(final EditorAuthoringTransactionCoordinator.Binding expected) {
            return binding.documentGeneration() == expected.documentGeneration()
                && binding.modelGeneration() == expected.modelGeneration()
                && binding.documentIdentity().equals(expected.documentIdentity())
                && binding.modelIdentity().equals(expected.modelIdentity())
                && binding.hostThread() == expected.hostThread();
        }

        @Override
        public HistorySnapshot history(
            final EditorAuthoringTransactionCoordinator.Binding expected
        ) {
            return history();
        }

        HistorySnapshot history() {
            return new HistorySnapshot(
                HistorySnapshot.Availability.AVAILABLE,
                1,
                revision,
                entries.size(),
                List.copyOf(entries),
                !entries.isEmpty(),
                false,
                "document-binding-1",
                "manager-binding-1"
            );
        }

        @Override
        public Object beginEdit(
            final EditorAuthoringTransactionCoordinator.Binding expected,
            final String label
        ) {
            beginCount++;
            currentLabel = label;
            return new Object();
        }

        @Override
        public Optional<String> prepareHistoryMetadata(
            final EditorAuthoringTransactionCoordinator.Binding expected,
            final Object edit, final String transactionId,
            final HistoryEntryDetail detail, final Optional<HistoryAction> action
        ) {
            preparedDetail = Optional.of(detail);
            return preparedId;
        }

        @Override
        public void endEdit(
            final EditorAuthoringTransactionCoordinator.Binding expected,
            final Object edit,
            final boolean abort
        ) {
            endAttempts++;
            detailObservedAtEnd = preparedDetail;
            if (failEnd) throw new IllegalStateException("native end uncertain");
            if (abort) {
                abortCount++;
                return;
            }
            commitCount++;
            for (int index = 0; index < entriesPerCommit; index++) {
                entries.add(new HistoryEntry(entries.size(), currentLabel, true));
            }
            revision++;
        }

        @Override
        public void refresh(
            final EditorAuthoringTransactionCoordinator.Binding expected,
            final Set<EditorRefreshRequirement> requirements
        ) {
            refreshCount++;
            lastRefresh = Set.copyOf(requirements);
        }

        @Override
        public Optional<String> committedHistoryEntryId(
            final EditorAuthoringTransactionCoordinator.Binding expected,
            final HistorySnapshot before,
            final HistorySnapshot after,
            final String transactionId,
            final String label
        ) {
            if (after.entries().size() != before.entries().size() + 1) return Optional.empty();
            return Optional.of("history-entry-" + after.entries().size());
        }

        @Override
        public Optional<String> committedHistoryEntryId(
            final EditorAuthoringTransactionCoordinator.Binding expected,
            final HistorySnapshot before,
            final HistorySnapshot after,
            final String transactionId,
            final String label,
            final HistoryEntryDetail detail,
            final Optional<HistoryAction> action
        ) {
            lastSemanticDetail = Optional.of(detail);
            lastSemanticAction = action;
            return committedHistoryEntryId(
                expected,
                before,
                after,
                transactionId,
                label
            );
        }

        @Override
        public String diagnosticId(final String code, final Throwable failure) {
            return code;
        }
    }
}
