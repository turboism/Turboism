package dev.turboism.sdk.cubism.transaction;

import dev.turboism.sdk.cubism.history.HistorySnapshot;

import java.util.Objects;
import java.util.Optional;

/**
 * Immutable evidence captured around one synchronous authoring transaction attempt.
 *
 * @param transactionId opaque Turboism transaction identity
 * @param label user-visible history label requested by the caller
 * @param historyBefore native history snapshot captured before the callback
 * @param historyAfter native history snapshot captured after commit or recovery
 * @param historyEntryId opaque Turboism identity associated with the committed native entry, when
 *     a changed transaction committed
 */
public record AuthoringTransactionReceipt(
    String transactionId,
    String label,
    HistorySnapshot historyBefore,
    HistorySnapshot historyAfter,
    Optional<String> historyEntryId
) {

    /** Validates and defensively normalizes the receipt. */
    public AuthoringTransactionReceipt {
        transactionId = requireText(transactionId, "transactionId", 128);
        label = new AuthoringTransactionOptions(label).label();
        historyBefore = Objects.requireNonNull(historyBefore, "historyBefore");
        historyAfter = Objects.requireNonNull(historyAfter, "historyAfter");
        historyEntryId = Objects.requireNonNull(historyEntryId, "historyEntryId")
            .map(value -> requireText(value, "historyEntryId", 128));
    }

    private static String requireText(
        final String value,
        final String name,
        final int maximumLength
    ) {
        final String checked = Objects.requireNonNull(value, name).strip();
        if (checked.isEmpty()) throw new IllegalArgumentException(name + " must not be blank");
        if (checked.length() > maximumLength) {
            throw new IllegalArgumentException(
                name + " must not exceed " + maximumLength + " characters"
            );
        }
        if (checked.chars().anyMatch(character -> Character.isISOControl(character))) {
            throw new IllegalArgumentException(name + " must not contain control characters");
        }
        return checked;
    }
}
