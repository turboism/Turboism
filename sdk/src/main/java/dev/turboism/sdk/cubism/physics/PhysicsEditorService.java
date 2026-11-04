package dev.turboism.sdk.cubism.physics;

import dev.turboism.sdk.CubismEditor;
import dev.turboism.sdk.plugin.Registration;

/** Preview registration seam for bounded Physics Settings workflows. */
@CubismEditor
@FunctionalInterface
public interface PhysicsEditorService {

    /**
     * Registers a Physics Settings contribution. Closing the returned {@link Registration}
     * withdraws the contribution.
     */
    Registration contribute(PhysicsEditorContribution contribution);

    /** Recording commands for the focused native Physics Settings preview. */
    enum GifCommand {
        /** Starts recording, pauses it, or resumes a paused recording. */
        RECORD_PAUSE,
        /** Pauses an active recording. */
        PAUSE,
        /** Finishes the recording and opens export settings. */
        STOP,
        /** Discards the recording without exporting. */
        DISCARD
    }

    /** Dispatches to the focused physics preview; does nothing when none is focused. */
    default void gifCommand(final GifCommand command) {}

    /**
     * Reports whether a live runtime surface backs this instance.
     *
     * @return {@code false} when the backend backing this instance is
     *         unavailable, including the {@link #unavailable()} sentinel
     */
    default boolean isAvailable() {
        return true;
    }

    /** Safe-mode instance: every contribution is refused (fail closed). */
    static PhysicsEditorService unavailable() {
        return Unavailable.INSTANCE;
    }

    /** Sentinel returned by {@link #unavailable()}: unsupported calls throw a stable {@link UnsupportedOperationException}. */
    enum Unavailable implements PhysicsEditorService {
        INSTANCE;

        @Override
        public boolean isAvailable() {
            return false;
        }

        @Override
        public Registration contribute(final PhysicsEditorContribution contribution) {
            throw new UnsupportedOperationException("physics editor service is not available");
        }
    }
}
