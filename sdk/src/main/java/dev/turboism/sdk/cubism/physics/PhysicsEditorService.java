package dev.turboism.sdk.cubism.physics;

import dev.turboism.sdk.plugin.Registration;

/** Preview registration seam for bounded Physics Settings workflows. */
@FunctionalInterface
public interface PhysicsEditorService {

    /**
     * Registers a Physics Settings contribution. Closing the returned {@link Registration}
     * withdraws the contribution.
     */
    Registration contribute(PhysicsEditorContribution contribution);

    /**
     * Reports whether a live runtime surface backs this instance.
     *
     * @return {@code false} only for the {@link #unavailable()} sentinel
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

        @Override public boolean isAvailable() {
            return false;
        }

        @Override public Registration contribute(final PhysicsEditorContribution contribution) {
            throw new UnsupportedOperationException("physics editor service is not available");
        }
    }
}
