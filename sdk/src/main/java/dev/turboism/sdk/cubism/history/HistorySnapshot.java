package dev.turboism.sdk.cubism.history;


import java.util.List;
import java.util.Objects;

/** Immutable state of the active document's native Undo history. */
public record HistorySnapshot(
    Availability availability,
    long generation,
    long revision,
    int position,
    List<HistoryEntry> entries,
    boolean canUndo,
    boolean canRedo,
    String documentBindingId,
    String managerBindingId
) {

    public HistorySnapshot(
        final Availability availability,
        final long generation,
        final long revision,
        final int position,
        final List<HistoryEntry> entries,
        final boolean canUndo,
        final boolean canRedo
    ) {
        this(availability, generation, revision, position, entries, canUndo, canRedo, "", "");
    }

    public HistorySnapshot {
        availability = Objects.requireNonNull(availability, "availability");
        if (generation < 0) throw new IllegalArgumentException("generation must not be negative");
        if (revision < 0) throw new IllegalArgumentException("revision must not be negative");
        entries = List.copyOf(Objects.requireNonNull(entries, "entries"));
        documentBindingId = normalizeBindingId(documentBindingId, "documentBindingId");
        managerBindingId = normalizeBindingId(managerBindingId, "managerBindingId");
        if ((documentBindingId.isEmpty()) != (managerBindingId.isEmpty())) {
            throw new IllegalArgumentException(
                "history binding identities must be both present or both absent"
            );
        }
        if (position < 0 || position > entries.size()) {
            throw new IllegalArgumentException("position must be within [0, entries.size]");
        }
        for (int index = 0; index < entries.size(); index++) {
            if (entries.get(index).index() != index) {
                throw new IllegalArgumentException("entry indexes must be contiguous from zero");
            }
        }
        if (availability == Availability.UNAVAILABLE
            && (!entries.isEmpty()
                || position != 0
                || canUndo
                || canRedo
                || !documentBindingId.isEmpty()
                || !managerBindingId.isEmpty())) {
            throw new IllegalArgumentException("unavailable history must be empty");
        }
    }

    private static String normalizeBindingId(final String value, final String name) {
        Objects.requireNonNull(value, name);
        return value.strip();
    }

    /**
     * The fail-closed snapshot used when the host's Undo history cannot be read — no active document,
     * or an unverified host. Generation, revision, and position are zero, there are no entries, and
     * neither undo nor redo is offered.
     *
     * @return the canonical unavailable snapshot
     */
    public static HistorySnapshot unavailable() {
        return new HistorySnapshot(Availability.UNAVAILABLE, 0, 0, 0, List.of(), false, false);
    }

    public enum Availability {
        AVAILABLE,
        UNAVAILABLE
    }
}
