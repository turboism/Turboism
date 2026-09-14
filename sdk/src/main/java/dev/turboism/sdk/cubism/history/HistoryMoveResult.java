package dev.turboism.sdk.cubism.history;


import java.util.Objects;
import java.util.Optional;

/** Result of attempting to move the active document's native history cursor. */
public record HistoryMoveResult(
    Outcome outcome,
    HistorySnapshot snapshot,
    Optional<String> diagnosticId
) {

    public HistoryMoveResult {
        outcome = Objects.requireNonNull(outcome, "outcome");
        snapshot = Objects.requireNonNull(snapshot, "snapshot");
        diagnosticId = Objects.requireNonNull(diagnosticId, "diagnosticId")
            .map(String::strip)
            .filter(value -> !value.isEmpty());
    }

    /**
     * What happened to a history-cursor move attempt. {@code MOVED} means the cursor
     * read back at the requested position. {@code PARTIAL_MOVE} means the move threw
     * or read back at a different position, so the cursor may have changed;
     * {@code FAILED_UNKNOWN_POSITION} means the resulting position could not be
     * determined — whether or not the move threw — and the returned snapshot may
     * itself report {@code UNAVAILABLE}.
     */
    public enum Outcome {
        MOVED,
        NO_CHANGE,
        REJECTED_STALE,
        INVALID_POSITION,
        PARTIAL_MOVE,
        UNAVAILABLE,
        FAILED_UNKNOWN_POSITION
    }
}
