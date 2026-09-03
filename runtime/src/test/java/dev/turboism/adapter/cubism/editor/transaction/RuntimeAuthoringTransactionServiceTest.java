package dev.turboism.adapter.cubism.editor.transaction;

import dev.turboism.sdk.cubism.history.HistorySnapshot;
import dev.turboism.sdk.cubism.transaction.AuthoringTransactionOptions;
import dev.turboism.sdk.cubism.transaction.AuthoringTransactionOutcome;
import dev.turboism.sdk.cubism.transaction.AuthoringTransactionResult;
import org.junit.jupiter.api.Test;

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
        final EditorAuthoringTransactionCoordinator.Binding binding = binding();
        final EditorAuthoringTransactionCoordinator coordinator =
            new EditorAuthoringTransactionCoordinator(new ReadOnlyHost(binding));
        final AtomicInteger bindingReads = new AtomicInteger();
        final RuntimeAuthoringTransactionService service =
            new RuntimeAuthoringTransactionService(coordinator, () -> {
                bindingReads.incrementAndGet();
                return Optional.of(binding);
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
    void missingBindingFailsClosedWithoutInvokingTheCallback() {
        final EditorAuthoringTransactionCoordinator coordinator =
            new EditorAuthoringTransactionCoordinator(new ReadOnlyHost(binding()));
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
            new EditorAuthoringTransactionCoordinator(new ReadOnlyHost(binding()));
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

    private static EditorAuthoringTransactionCoordinator.Binding binding() {
        return new EditorAuthoringTransactionCoordinator.Binding(
            "plugin.test",
            "document-1",
            1,
            "model-1",
            1,
            Thread.currentThread()
        );
    }

    private static final class ReadOnlyHost implements EditorAuthoringTransactionCoordinator.Host {
        private final EditorAuthoringTransactionCoordinator.Binding binding;

        ReadOnlyHost(final EditorAuthoringTransactionCoordinator.Binding binding) {
            this.binding = binding;
        }

        @Override
        public boolean isCurrent(final EditorAuthoringTransactionCoordinator.Binding expected) {
            return binding.equals(expected);
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
}
