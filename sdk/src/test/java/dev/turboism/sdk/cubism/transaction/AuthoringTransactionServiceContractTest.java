package dev.turboism.sdk.cubism.transaction;

import dev.turboism.sdk.cubism.CubismFacade;
import dev.turboism.sdk.cubism.history.HistoryEntry;
import dev.turboism.sdk.cubism.history.HistorySnapshot;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class AuthoringTransactionServiceContractTest {

    @Test
    void cubismFacadeExposesTheSynchronousAuthoringTransactionService() throws Exception {
        final Method method = CubismFacade.class.getMethod("authoringTransactions");

        assertEquals(AuthoringTransactionService.class, method.getReturnType());
        assertTrue(method.isDefault());
    }

    @Test
    void unavailableServiceReturnsTypedFailureWithoutRunningTheCallback() {
        final AtomicBoolean invoked = new AtomicBoolean();

        final AuthoringTransactionResult<String> result =
            AuthoringTransactionService.unavailable().execute(
                AuthoringTransactionOptions.of("Unavailable transaction"),
                () -> {
                    invoked.set(true);
                    return "must-not-run";
                }
            );

        assertFalse(invoked.get());
        assertEquals(AuthoringTransactionOutcome.UNAVAILABLE, result.outcome());
        assertTrue(result.value().isEmpty());
        assertTrue(result.receipt().isEmpty());
        assertEquals(
            Optional.of("cubism.authoring.transactions.unavailable"),
            result.diagnosticId()
        );
        assertFalse(result.successful());
        assertFalse(result.changed());
    }

    @Test
    void optionsNormalizeAndBoundTheHistoryLabel() {
        assertEquals("Adjust eye glues", AuthoringTransactionOptions.of(
            "  Adjust eye glues  "
        ).label());
        assertThrows(IllegalArgumentException.class,
            () -> AuthoringTransactionOptions.of("   "));
        assertThrows(IllegalArgumentException.class,
            () -> AuthoringTransactionOptions.of("x".repeat(
                AuthoringTransactionOptions.MAX_LABEL_LENGTH + 1
            )));
    }

    @Test
    void committedAndNoChangeResultsCarryReceiptsWithoutLeakingHandles() {
        final HistorySnapshot before = availableHistory(3, 8, 0, List.of());
        final HistorySnapshot committedAfter = availableHistory(
            3,
            9,
            1,
            List.of(new HistoryEntry(0, "Adjust eye glues", true))
        );
        final AuthoringTransactionReceipt committedReceipt = new AuthoringTransactionReceipt(
            "transaction-1",
            "Adjust eye glues",
            before,
            committedAfter,
            Optional.of("history-entry-1")
        );
        final AuthoringTransactionResult<String> committed =
            AuthoringTransactionResult.committed("done", committedReceipt);

        assertEquals(AuthoringTransactionOutcome.COMMITTED, committed.outcome());
        assertEquals(Optional.of("done"), committed.value());
        assertEquals(Optional.of(committedReceipt), committed.receipt());
        assertTrue(committed.successful());
        assertTrue(committed.changed());

        final AuthoringTransactionReceipt noChangeReceipt = new AuthoringTransactionReceipt(
            "transaction-2",
            "Read-only inspection",
            before,
            before,
            Optional.empty()
        );
        final AuthoringTransactionResult<Integer> noChange =
            AuthoringTransactionResult.noChange(7, noChangeReceipt);

        assertEquals(AuthoringTransactionOutcome.NO_CHANGE, noChange.outcome());
        assertEquals(Optional.of(7), noChange.value());
        assertTrue(noChange.successful());
        assertFalse(noChange.changed());
    }

    @Test
    void resultInvariantsRequireEntryIdentityOnlyForCommittedMutation() {
        final HistorySnapshot history = availableHistory(1, 1, 0, List.of());
        final AuthoringTransactionReceipt withoutEntry = new AuthoringTransactionReceipt(
            "transaction-1",
            "Mutation",
            history,
            history,
            Optional.empty()
        );
        final AuthoringTransactionReceipt withEntry = new AuthoringTransactionReceipt(
            "transaction-2",
            "No change",
            history,
            history,
            Optional.of("history-entry-1")
        );

        assertThrows(IllegalArgumentException.class,
            () -> AuthoringTransactionResult.committed("value", withoutEntry));
        assertThrows(IllegalArgumentException.class,
            () -> AuthoringTransactionResult.noChange("value", withEntry));
    }

    private static HistorySnapshot availableHistory(
        final long generation,
        final long revision,
        final int position,
        final List<HistoryEntry> entries
    ) {
        return new HistorySnapshot(
            HistorySnapshot.Availability.AVAILABLE,
            generation,
            revision,
            position,
            entries,
            position > 0,
            position < entries.size()
        );
    }
}
