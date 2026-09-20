package dev.turboism.sdk.cubism.mesh;

import dev.turboism.sdk.plugin.Registration;

/** Enables plugin policy that mirrors host-initiated selected-point movement. */
public interface MeshMirrorMoveParticipation {

    Registration participate();

    /**
     * Reports whether a live runtime surface backs this instance.
     *
     * @return {@code false} only for the {@link #unavailable()} sentinel
     */
    default boolean isAvailable() {
        return true;
    }

    static MeshMirrorMoveParticipation unavailable() {
        return Unavailable.INSTANCE;
    }

    enum Unavailable implements MeshMirrorMoveParticipation {
        INSTANCE;

        @Override public boolean isAvailable() {
            return false;
        }

        @Override public Registration participate() {
            throw new UnsupportedOperationException(
                "meshMirrorMoveParticipation service is not available");
        }
    }
}
