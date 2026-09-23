package dev.turboism.sdk.cubism.history;

import dev.turboism.sdk.CubismEditor;

import java.util.Objects;
import java.util.Optional;

/** Active-document access to Cubism's native Undo history. */
@CubismEditor({"5.2.03", "5.3.02", "5.3.03"})
public interface CubismHistory {

    /** Returns the current immutable state of the active document's native Undo history. */
    HistorySnapshot snapshot();

    /**
     * Moves the history cursor to {@code position} when the observed generation and revision
     * still match.
     *
     * @param expectedGeneration the {@link HistorySnapshot#generation()} the caller built against
     * @param expectedRevision the {@link HistorySnapshot#revision()} the caller built against
     * @param position target cursor position within {@code [0, entries.size]}
     * @return the move result; a stale expectation yields {@code REJECTED_STALE} and no change
     */
    HistoryMoveResult moveTo(long expectedGeneration, long expectedRevision, int position);

    /**
     * Moves to {@code position}, using {@code expected}'s generation and revision.
     * Implementations with native document/Undo-manager binding checks should override this method.
     */
    default HistoryMoveResult moveTo(
        final HistorySnapshot expected,
        final int position
    ) {
        Objects.requireNonNull(expected, "expected");
        return moveTo(expected.generation(), expected.revision(), position);
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

    /** Returns the fail-closed history whose snapshot and moves all report unavailable. */
    static CubismHistory unavailable() {
        return Unavailable.INSTANCE;
    }

    /** Singleton fail-closed implementation returned by {@link #unavailable()}. */
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
