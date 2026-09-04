package dev.turboism.sdk.cubism.transaction;

import dev.turboism.sdk.cubism.history.HistorySnapshot;

import java.util.Objects;
import java.util.Optional;

/**
 * Immutable receipt for one attempted synchronous authoring transaction.
 *
 * <p>The receipt exposes only Turboism-owned identities and immutable history snapshots. Native
 * Editor edit objects, history-manager objects, and transaction handles never cross the SDK
 * boundary.</p>
 *
 * @param transactionId opaque Turboism transaction identity
 * @param label user-visible transaction label
 * @param historyBefore native history snapshot captured before authoring work
 * @param historyAfter native history snapshot captured after commit or recovery
 * @param historyEntryId stable Turboism identity of the committed history entry, when one exists
 */
public record AuthoringTransactionReceipt(
    String transactionId,
    String label,
    HistorySnapshot historyBefore,
    HistorySnapshot historyAfter,
    Optional<String> historyEntryId
) {

    /** Maximum length of opaque transaction and history-entry identities. */
    public static final int MAX_ID_LENGTH = 128;

    /** Validates and defensively normalizes receipt values. */
    public AuthoringTransactionReceipt {
        transactionId = normalizedId(transactionId, "transactionId");
        label = AuthoringTransactionOptions.of(label).label();
        historyBefore = Objects.requireNonNull(historyBefore, "historyBefore");
        historyAfter = Objects.requireNonNull(historyAfter, "historyAfter");
        historyEntryId = Objects.requireNonNull(historyEntryId, "historyEntryId")
            .map(value -> normalizedId(value, "historyEntryId"));
    }

    private static String normalizedId(final String value, final String field) {
        final String normalized = Objects.requireNonNull(value, field).strip();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        if (normalized.length() > MAX_ID_LENGTH) {
            throw new IllegalArgumentException(
                field + " must not exceed " + MAX_ID_LENGTH + " characters"
            );
        }
        if (normalized.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(field + " must not contain control characters");
        }
        return normalized;
    }
}
