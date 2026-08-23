package dev.turboism.sdk.cubism.history;


import java.util.Objects;
import java.util.Optional;

/** Immutable plugin-facing projection of one native Cubism Undo entry. */
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

    /**
     * @return how much is known about this entry: the structured action's detail level when one was
     *     resolved, otherwise {@code LABEL_ONLY}, meaning only the host's display label is trustworthy
     */
    public HistoryAction.DetailLevel detailLevel() {
        return action.map(HistoryAction::detailLevel)
            .orElse(HistoryAction.DetailLevel.LABEL_ONLY);
    }
}
