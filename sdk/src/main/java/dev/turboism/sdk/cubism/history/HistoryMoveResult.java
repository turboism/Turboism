package dev.turboism.sdk.cubism.history;

import dev.turboism.sdk.PreviewApi;

import java.util.Objects;
import java.util.Optional;

/** Result of attempting to move the active document's native history cursor. */
@PreviewApi
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
