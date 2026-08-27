package dev.turboism.sdk.cubism.history;

import dev.turboism.sdk.CubismEditor;

import java.util.Objects;
import java.util.Optional;

/** Active-document access to Cubism's native Undo history. */
@CubismEditor({"5.3.02", "5.3.03"})
public interface CubismHistory {

    HistorySnapshot snapshot();

    HistoryMoveResult moveTo(long expectedGeneration, long expectedRevision, int position);

    /**
     * Moves only if the active native document and Undo manager still match {@code expected}.
     * Implementations that do not support atomic native-binding checks fail closed.
     */
    default HistoryMoveResult moveTo(
        final HistorySnapshot expected,
        final int position
    ) {
        Objects.requireNonNull(expected, "expected");
        return new HistoryMoveResult(
            HistoryMoveResult.Outcome.REJECTED_STALE,
            snapshot(),
            Optional.of("history.move.binding-unsupported")
        );
    }

    /**
     * Returns whether this history provider is still bound to the document and native Undo manager
     * identified by {@code snapshot}. Providers that cannot expose a native binding identity fail
     * closed and return {@code false}.
     */
    default boolean isCurrentBinding(final HistorySnapshot snapshot) {
        return false;
    }

    /**
     * Undoes {@code steps} entries in one call (PS-style multi-step undo).
     * The undone entries stay available for {@link #redo(int)} until a new
     * write forks the history. {@code steps <= 0} is a no-op.
     */
    default HistoryMoveResult undo(final int steps) {
        if (steps <= 0) {
            return noMove("history.move.no-op");
        }
        final HistorySnapshot snapshot = snapshot();
        if (snapshot.availability() != HistorySnapshot.Availability.AVAILABLE) {
            return noMove("history.move.unavailable");
        }
        return moveTo(snapshot, Math.max(0, snapshot.position() - steps));
    }

    /**
     * Redoes {@code steps} undone entries in one call. {@code steps <= 0}
     * is a no-op.
     */
    default HistoryMoveResult redo(final int steps) {
        if (steps <= 0) {
            return noMove("history.move.no-op");
        }
        final HistorySnapshot snapshot = snapshot();
        if (snapshot.availability() != HistorySnapshot.Availability.AVAILABLE) {
            return noMove("history.move.unavailable");
        }
        return moveTo(
            snapshot,
            Math.min(snapshot.entries().size(), snapshot.position() + steps)
        );
    }

    private static HistoryMoveResult noMove(final String diagnosticId) {
        return new HistoryMoveResult(
            HistoryMoveResult.Outcome.NO_CHANGE,
            HistorySnapshot.unavailable(),
            Optional.of(diagnosticId)
        );
    }

    static CubismHistory unavailable() {
        return Unavailable.INSTANCE;
    }

    enum Unavailable implements CubismHistory {
        INSTANCE;

        @Override
        public HistorySnapshot snapshot() {
            return HistorySnapshot.unavailable();
        }

        @Override
        public HistoryMoveResult moveTo(
            final long expectedGeneration,
            final long expectedRevision,
            final int position
        ) {
            return new HistoryMoveResult(
                HistoryMoveResult.Outcome.UNAVAILABLE,
                snapshot(),
                Optional.of("history.provider.unavailable")
            );
        }
    }
}
