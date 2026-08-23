package dev.turboism.sdk.cubism.history;


import java.util.Optional;

/** Active-document access to Cubism's native Undo history. */
public interface CubismHistory {

    HistorySnapshot snapshot();

    HistoryMoveResult moveTo(long expectedGeneration, long expectedRevision, int position);

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
        return moveTo(
            snapshot.generation(),
            snapshot.revision(),
            Math.max(0, snapshot.position() - steps)
        );
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
            snapshot.generation(),
            snapshot.revision(),
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
