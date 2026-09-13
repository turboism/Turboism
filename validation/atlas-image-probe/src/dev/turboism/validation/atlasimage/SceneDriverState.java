package dev.turboism.validation.atlasimage;

/** Small fail-closed state machine for the fixed Circle100 menu observation. */
final class SceneDriverState {
    enum Stage {
        INITIAL,
        MAIN_IDENTIFIED,
        EDIT_REQUESTED,
        EDITOR_CONFIRMED,
        CANCELLED,
        FINISH_REQUESTED,
        OBSERVER_PERSISTED,
        EXIT_REQUESTED,
        RESULT_WRITTEN,
        FAILED
    }

    private Stage stage = Stage.INITIAL;

    synchronized Stage stage() {
        return stage;
    }

    synchronized void transition(final Stage expected, final Stage next) {
        if (stage != expected) {
            throw new IllegalStateException("expected " + expected + " but was " + stage);
        }
        if (!allowed(expected, next)) {
            throw new IllegalStateException("invalid fixed-driver transition: " + expected + " -> " + next);
        }
        stage = next;
    }

    private static boolean allowed(final Stage expected, final Stage next) {
        return switch (expected) {
            case INITIAL -> next == Stage.MAIN_IDENTIFIED;
            case MAIN_IDENTIFIED -> next == Stage.EDIT_REQUESTED;
            case EDIT_REQUESTED -> next == Stage.EDITOR_CONFIRMED;
            case EDITOR_CONFIRMED -> next == Stage.CANCELLED;
            case CANCELLED -> next == Stage.FINISH_REQUESTED;
            case FINISH_REQUESTED -> next == Stage.OBSERVER_PERSISTED;
            case OBSERVER_PERSISTED -> next == Stage.EXIT_REQUESTED;
            case EXIT_REQUESTED -> next == Stage.RESULT_WRITTEN;
            case RESULT_WRITTEN, FAILED -> false;
        };
    }

    synchronized void fail() {
        stage = Stage.FAILED;
    }
}
