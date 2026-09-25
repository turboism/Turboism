package dev.turboism.adapter.cubism.editor.transaction;

import dev.turboism.sdk.cubism.history.HistoryEntry;
import dev.turboism.sdk.cubism.history.HistorySnapshot;
import dev.turboism.sdk.cubism.transaction.AuthoringTransactionOptions;
import dev.turboism.sdk.cubism.transaction.AuthoringTransactionOutcome;
import dev.turboism.sdk.cubism.transaction.AuthoringTransactionResult;
import org.junit.jupiter.api.Test;

import javax.swing.SwingUtilities;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RuntimeAuthoringTransactionServiceTest {

    @Test
    void obtainsOneBindingAndDelegatesTheSynchronousCallback() {
        final EditorAuthoringTransactionCoordinator coordinator =
            new EditorAuthoringTransactionCoordinator(new ReadOnlyHost());
        final AtomicInteger bindingReads = new AtomicInteger();
        final RuntimeAuthoringTransactionService service =
            new RuntimeAuthoringTransactionService(coordinator, () -> {
                bindingReads.incrementAndGet();
                return Optional.of(bindingOnCurrentThread());
            });

        final AuthoringTransactionResult<String> result = service.execute(
            AuthoringTransactionOptions.of("Inspect transaction"),
            () -> "done"
        );

        assertEquals(1, bindingReads.get());
        assertEquals(AuthoringTransactionOutcome.NO_CHANGE, result.outcome());
        assertEquals(Optional.of("done"), result.value());
        assertTrue(result.successful());
    }

    @Test
    void executesBindingLookupCallbackAndHostCallsOnTheEventDispatchThread() {
        assertFalse(SwingUtilities.isEventDispatchThread());
        final RecordingHost host = new RecordingHost();
        final EditorAuthoringTransactionCoordinator coordinator =
            new EditorAuthoringTransactionCoordinator(host);
        final AtomicBoolean bindingOnEdt = new AtomicBoolean();
        final AtomicBoolean workOnEdt = new AtomicBoolean();
        final RuntimeAuthoringTransactionService service =
            new RuntimeAuthoringTransactionService(coordinator, () -> {
                bindingOnEdt.set(SwingUtilities.isEventDispatchThread());
                return Optional.of(bindingOnCurrentThread());
            });

        final AuthoringTransactionResult<Void> result = service.execute(
            AuthoringTransactionOptions.of("EDT transaction"),
            () -> {
                workOnEdt.set(SwingUtilities.isEventDispatchThread());
                coordinator.mutate(
                    bindingOnCurrentThread(),
                    contribution(new AtomicInteger())
                );
                return null;
            }
        );

        assertEquals(AuthoringTransactionOutcome.COMMITTED, result.outcome());
        assertTrue(bindingOnEdt.get(), "binding supplier must run on the Swing EDT");
        assertTrue(workOnEdt.get(), "transaction callback must run on the Swing EDT");
        assertTrue(host.offEdtCalls.get() == 0 && host.calls.get() > 0,
            "every host boundary call must run on the Swing EDT");
        assertEquals(1, host.beginCount);
        assertEquals(1, host.commitCount);
    }

    @Test
    void contributionInsideTheCallbackJoinsTheAmbientRoot() {
        final RecordingHost host = new RecordingHost();
        final EditorAuthoringTransactionCoordinator coordinator =
            new EditorAuthoringTransactionCoordinator(host);
        final RuntimeAuthoringTransactionService service =
            new RuntimeAuthoringTransactionService(
                coordinator,
                () -> Optional.of(bindingOnCurrentThread())
            );
        final AtomicInteger value = new AtomicInteger();

        final AuthoringTransactionResult<Void> result = service.execute(
            AuthoringTransactionOptions.of("Grouped write"),
            () -> {
                coordinator.mutate(
                    bindingOnCurrentThread(),
                    contribution(value)
                );
                coordinator.mutate(
                    bindingOnCurrentThread(),
                    contribution(value)
                );
                return null;
            }
        );

        assertEquals(AuthoringTransactionOutcome.COMMITTED, result.outcome());
        assertEquals(1, host.beginCount, "both contributions share one root edit");
        assertEquals(1, host.commitCount);
        assertEquals(0, host.abortCount);
        assertEquals(1, host.history().entries().size(), "one root Undo entry");
    }

    @Test
    void contributionInsideTheCallbackRollsBackWithTheRoot() {
        final RecordingHost host = new RecordingHost();
        final EditorAuthoringTransactionCoordinator coordinator =
            new EditorAuthoringTransactionCoordinator(host);
        final RuntimeAuthoringTransactionService service =
            new RuntimeAuthoringTransactionService(
                coordinator,
                () -> Optional.of(bindingOnCurrentThread())
            );
        final AtomicInteger value = new AtomicInteger();

        final AuthoringTransactionResult<Void> result = service.execute(
            AuthoringTransactionOptions.of("Failing write"),
            () -> {
                coordinator.mutate(
                    bindingOnCurrentThread(),
                    contribution(value)
                );
                throw new IllegalStateException("later step failed");
            }
        );

        assertEquals(AuthoringTransactionOutcome.ROLLED_BACK, result.outcome());
        assertEquals(0, value.get(), "compensation must restore the pre-transaction value");
        assertEquals(1, host.abortCount);
        assertEquals(0, host.commitCount);
        assertTrue(host.history().entries().isEmpty(), "rollback commits no Undo entry");
    }

    @Test
    void missingBindingFailsClosedWithoutInvokingTheCallback() {
        final EditorAuthoringTransactionCoordinator coordinator =
            new EditorAuthoringTransactionCoordinator(new ReadOnlyHost());
        final RuntimeAuthoringTransactionService service =
            new RuntimeAuthoringTransactionService(coordinator, Optional::empty);
        final AtomicBoolean invoked = new AtomicBoolean();

        final AuthoringTransactionResult<String> result = service.execute(
            AuthoringTransactionOptions.of("Unavailable transaction"),
            () -> {
                invoked.set(true);
                return "unexpected";
            }
        );

        assertFalse(invoked.get());
        assertEquals(AuthoringTransactionOutcome.UNAVAILABLE, result.outcome());
        assertEquals(
            Optional.of("cubism.authoring.transactions.binding-unavailable"),
            result.diagnosticId()
        );
    }

    @Test
    void bindingResolutionFailureFailsClosedWithoutInvokingTheCallback() {
        final EditorAuthoringTransactionCoordinator coordinator =
            new EditorAuthoringTransactionCoordinator(new ReadOnlyHost());
        final RuntimeAuthoringTransactionService service =
            new RuntimeAuthoringTransactionService(coordinator, () -> {
                throw new IllegalStateException("host session changed");
            });
        final AtomicBoolean invoked = new AtomicBoolean();

        final AuthoringTransactionResult<Void> result = service.execute(
            AuthoringTransactionOptions.of("Unavailable transaction"),
            () -> {
                invoked.set(true);
                return null;
            }
        );

        assertFalse(invoked.get());
        assertEquals(AuthoringTransactionOutcome.UNAVAILABLE, result.outcome());
        assertEquals(
            Optional.of("cubism.authoring.transactions.binding-unavailable"),
            result.diagnosticId()
        );
    }

    private static EditorAuthoringTransactionCoordinator.Binding bindingOnCurrentThread() {
        return new EditorAuthoringTransactionCoordinator.Binding(
            "plugin.test",
            "document-1",
            1,
            "model-1",
            1,
            Thread.currentThread()
        );
    }

    private static EditorUndoContribution contribution(final AtomicInteger value) {
        final int before = value.get();
        final int after = before + 1;
        return new EditorUndoContribution(
            "test.write",
            "target-1",
            "Set value",
            (edit, label) -> { },
            () -> value.set(after),
            () -> value.get() == after,
            () -> value.set(before),
            () -> value.get() == before,
            Set.of()
        );
    }

    private static boolean sameScope(
        final EditorAuthoringTransactionCoordinator.Binding expected
    ) {
        return expected.documentGeneration() == 1
            && expected.modelGeneration() == 1
            && expected.documentIdentity().equals("document-1")
            && expected.modelIdentity().equals("model-1")
            && expected.isCurrentThread();
    }

    private static final class ReadOnlyHost implements EditorAuthoringTransactionCoordinator.Host {
        @Override
        public boolean isCurrent(final EditorAuthoringTransactionCoordinator.Binding expected) {
            return sameScope(expected);
        }

        @Override
        public HistorySnapshot history(
            final EditorAuthoringTransactionCoordinator.Binding expected
        ) {
            return new HistorySnapshot(
                HistorySnapshot.Availability.AVAILABLE,
                1,
                1,
                0,
                java.util.List.of(),
                false,
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
            throw new AssertionError("read-only callback must not open an edit");
        }

        @Override
        public void endEdit(
            final EditorAuthoringTransactionCoordinator.Binding expected,
            final Object edit,
            final boolean abort
        ) {
            throw new AssertionError("read-only callback must not close an edit");
        }

        @Override
        public void undoEditGroup(
            final EditorAuthoringTransactionCoordinator.Binding expected,
            final Object edit
        ) {
            throw new AssertionError("read-only callback must not undo an edit group");
        }

        @Override
        public void refresh(
            final EditorAuthoringTransactionCoordinator.Binding expected,
            final Set<EditorRefreshRequirement> requirements
        ) {
            throw new AssertionError("read-only callback must not refresh");
        }

        @Override
        public Optional<String> committedHistoryEntryId(
            final EditorAuthoringTransactionCoordinator.Binding expected,
            final HistorySnapshot before,
            final HistorySnapshot after,
            final String transactionId,
            final String label
        ) {
            return Optional.empty();
        }

        @Override
        public String diagnosticId(final String code, final Throwable failure) {
            return code;
        }
    }

    private static final class RecordingHost implements EditorAuthoringTransactionCoordinator.Host {
        private final List<HistoryEntry> entries = new ArrayList<>();
        private final AtomicInteger calls = new AtomicInteger();
        private final AtomicInteger offEdtCalls = new AtomicInteger();
        private long revision;
        private String currentLabel = "";
        private int beginCount;
        private int commitCount;
        private int abortCount;
        private int groupUndoCount;

        private void recordThread() {
            calls.incrementAndGet();
            if (!SwingUtilities.isEventDispatchThread()) offEdtCalls.incrementAndGet();
        }

        @Override
        public boolean isCurrent(final EditorAuthoringTransactionCoordinator.Binding expected) {
            recordThread();
            return sameScope(expected);
        }

        @Override
        public HistorySnapshot history(
            final EditorAuthoringTransactionCoordinator.Binding expected
        ) {
            recordThread();
            return history();
        }

        private HistorySnapshot history() {
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
            recordThread();
            beginCount++;
            currentLabel = label;
            return new Object();
        }

        @Override
        public void endEdit(
            final EditorAuthoringTransactionCoordinator.Binding expected,
            final Object edit,
            final boolean abort
        ) {
            recordThread();
            if (abort) {
                abortCount++;
                return;
            }
            commitCount++;
            entries.add(new HistoryEntry(entries.size(), currentLabel, true));
            revision++;
        }

        @Override
        public void undoEditGroup(
            final EditorAuthoringTransactionCoordinator.Binding expected,
            final Object edit
        ) {
            recordThread();
            groupUndoCount++;
        }

        @Override
        public void refresh(
            final EditorAuthoringTransactionCoordinator.Binding expected,
            final Set<EditorRefreshRequirement> requirements
        ) {
            recordThread();
        }

        @Override
        public Optional<String> committedHistoryEntryId(
            final EditorAuthoringTransactionCoordinator.Binding expected,
            final HistorySnapshot before,
            final HistorySnapshot after,
            final String transactionId,
            final String label
        ) {
            recordThread();
            if (after.entries().size() != before.entries().size() + 1) return Optional.empty();
            return Optional.of("history-entry-" + after.entries().size());
        }

        @Override
        public String diagnosticId(final String code, final Throwable failure) {
            return code;
        }
    }
}
