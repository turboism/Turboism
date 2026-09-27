package dev.turboism.sdk.cubism.mesh;

import dev.turboism.sdk.plugin.Registration;

/** Enables plugin policy that mirrors host-initiated selected-point movement. */
public interface MeshMirrorMoveParticipation {

    /**
     * Activates this plugin's mirroring of host-initiated selected-point movement.
     *
     * @return the participation registration; closing it stops the mirroring
     */
    Registration participate();

    /**
     * Reports whether a live runtime surface backs this instance.
     *
     * @return {@code false} only for the {@link #unavailable()} sentinel
     */
    default boolean isAvailable() {
        return true;
    }

    /**
     * Returns this service's fail-closed {@code Unavailable} sentinel.
     *
     * @return the shared singleton; {@link #isAvailable()} is {@code false} only for it
     */
    static MeshMirrorMoveParticipation unavailable() {
        return Unavailable.INSTANCE;
    }

    /** Sentinel returned by {@link #unavailable()}: unsupported calls throw a stable {@link UnsupportedOperationException}. */
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
