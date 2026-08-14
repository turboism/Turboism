package dev.turboism.sdk.cubism.history;

import dev.turboism.sdk.PreviewApi;

import java.util.Objects;
import java.util.Optional;

/** Immutable plugin-facing projection of one native Cubism Undo entry. */
@PreviewApi
public record HistoryEntry(
    int index,
    String label,
    boolean significant,
    Optional<HistoryAction> action
) {

    public HistoryEntry(final int index, final String label, final boolean significant) {
        this(index, label, significant, Optional.empty());
    }

    public HistoryEntry {
        if (index < 0) throw new IllegalArgumentException("index must not be negative");
        label = Objects.requireNonNull(label, "label");
        action = Objects.requireNonNull(action, "action");
    }

    public HistoryAction.DetailLevel detailLevel() {
        return action.map(HistoryAction::detailLevel)
            .orElse(HistoryAction.DetailLevel.LABEL_ONLY);
    }
}
