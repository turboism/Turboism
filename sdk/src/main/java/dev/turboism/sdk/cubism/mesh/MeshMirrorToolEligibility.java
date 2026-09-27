package dev.turboism.sdk.cubism.mesh;

import dev.turboism.sdk.plugin.Registration;

import java.util.Set;

/** Extends the native set of mesh subtools that may keep mirror editing active. */
public interface MeshMirrorToolEligibility {

    /**
     * Adds mesh subtools to the set that keeps mirror editing active.
     *
     * @param tools the additional tools mirror editing stays enabled for
     * @return the registration; closing it withdraws the extension
     */
    Registration extendEligibleTools(Set<MeshEditTool> tools);

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
    static MeshMirrorToolEligibility unavailable() {
        return Unavailable.INSTANCE;
    }

    /** Sentinel returned by {@link #unavailable()}: unsupported calls throw a stable {@link UnsupportedOperationException}. */
    enum Unavailable implements MeshMirrorToolEligibility {
        INSTANCE;

        @Override public boolean isAvailable() {
            return false;
        }

        @Override public Registration extendEligibleTools(final Set<MeshEditTool> tools) {
            throw new UnsupportedOperationException(
                "meshMirrorToolEligibility service is not available");
        }
    }
}
