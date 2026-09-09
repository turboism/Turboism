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
    Optional<String> transactionId,
    HistoryEntryDetail detail
) {

    public HistoryEntry(final int index, final String label, final boolean significant) {
        this(
            index,
            label,
            significant,
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            HistoryEntryDetail.labelOnly(label)
        );
    }

    public HistoryEntry(
        final int index,
        final String label,
        final boolean significant,
        final Optional<HistoryAction> action
    ) {
        this(
            index,
            label,
            significant,
            action,
            Optional.empty(),
            Optional.empty(),
            defaultDetail(label, action)
        );
    }

    public HistoryEntry(
        final int index,
        final String label,
        final boolean significant,
        final Optional<HistoryAction> action,
        final Optional<HistoryEntryId> entryId,
        final Optional<String> transactionId
    ) {
        this(
            index,
            label,
            significant,
            action,
            entryId,
            transactionId,
            defaultDetail(label, action)
        );
    }

    public HistoryEntry {
        if (index < 0) throw new IllegalArgumentException("index must not be negative");
        label = Objects.requireNonNull(label, "label");
        action = Objects.requireNonNull(action, "action");
        entryId = Objects.requireNonNull(entryId, "entryId");
        transactionId = Objects.requireNonNull(transactionId, "transactionId")
            .map(HistoryEntry::normalizedTransactionId);
        detail = Objects.requireNonNull(detail, "detail");
        if (transactionId.isPresent() && entryId.isEmpty()) {
            throw new IllegalArgumentException(
                "transactionId requires a stable history entry identity"
            );
        }
        if (action.isPresent() && !detail.isCompatibleWith(action.orElseThrow())) {
            throw new IllegalArgumentException("action must not conflict with semantic detail");
        }
    }

    /** @return the trusted semantic detail level for this entry. */
    public HistoryAction.DetailLevel detailLevel() {
        return detail.detailLevel();
    }

    private static HistoryEntryDetail defaultDetail(
        final String label,
        final Optional<HistoryAction> action
    ) {
        final Optional<HistoryAction> trustedAction = Objects.requireNonNull(action, "action");
        return trustedAction
            .map(value -> HistoryEntryDetail.fromAction(
                label,
                value,
                HistoryOrigin.hostUnattributed()
            ))
            .orElseGet(() -> HistoryEntryDetail.labelOnly(label));
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
