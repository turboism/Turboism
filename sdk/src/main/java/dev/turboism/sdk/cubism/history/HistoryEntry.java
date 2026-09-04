package dev.turboism.sdk.cubism.history;

import java.util.Objects;
import java.util.Optional;

/** Immutable plugin-facing projection of one native Cubism Undo entry. */
public record HistoryEntry(
    int index,
    String label,
    boolean significant,
    Optional<HistoryAction> action,
    Optional<HistoryEntryId> entryId,
    Optional<String> transactionId
) {

    public HistoryEntry(final int index, final String label, final boolean significant) {
        this(
            index,
            label,
            significant,
            Optional.empty(),
            Optional.empty(),
            Optional.empty()
        );
    }

    public HistoryEntry(
        final int index,
        final String label,
        final boolean significant,
        final Optional<HistoryAction> action
    ) {
        this(index, label, significant, action, Optional.empty(), Optional.empty());
    }

    public HistoryEntry {
        if (index < 0) throw new IllegalArgumentException("index must not be negative");
        label = Objects.requireNonNull(label, "label");
        action = Objects.requireNonNull(action, "action");
        entryId = Objects.requireNonNull(entryId, "entryId");
        transactionId = Objects.requireNonNull(transactionId, "transactionId")
            .map(HistoryEntry::normalizedTransactionId);
        if (transactionId.isPresent() && entryId.isEmpty()) {
            throw new IllegalArgumentException(
                "transactionId requires a stable history entry identity"
            );
        }
    }

    /**
     * @return how much is known about this entry: the structured action's detail level when one was
     *     resolved, otherwise {@code LABEL_ONLY}, meaning only the host's display label is trustworthy
     */
    public HistoryAction.DetailLevel detailLevel() {
        return action.map(HistoryAction::detailLevel)
            .orElse(HistoryAction.DetailLevel.LABEL_ONLY);
    }

    private static String normalizedTransactionId(final String value) {
        final String normalized = Objects.requireNonNull(value, "transactionId").strip();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("transactionId must not be blank");
        }
        if (normalized.length() > HistoryEntryId.MAX_LENGTH) {
            throw new IllegalArgumentException(
                "transactionId must not exceed " + HistoryEntryId.MAX_LENGTH + " characters"
            );
        }
        if (normalized.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("transactionId must not contain control characters");
        }
        return normalized;
    }
}
