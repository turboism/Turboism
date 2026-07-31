package dev.turboism.sdk.cubism.history;

import dev.turboism.sdk.PreviewApi;

import java.util.Optional;

/** Active-document access to Cubism's native Undo history. */
@PreviewApi
public interface CubismHistory {

    HistorySnapshot snapshot();

    HistoryMoveResult moveTo(long expectedGeneration, long expectedRevision, int position);

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
