package dev.turboism.sdk.cubism.mesh;

import dev.turboism.sdk.plugin.Registration;

import java.util.Set;

/** Extends the native set of mesh subtools that may keep mirror editing active. */
public interface MeshMirrorToolEligibility {

    Registration extendEligibleTools(Set<MeshEditTool> tools);

    /**
     * Reports whether a live runtime surface backs this instance.
     *
     * @return {@code false} only for the {@link #unavailable()} sentinel
     */
    default boolean isAvailable() {
        return true;
    }

    static MeshMirrorToolEligibility unavailable() {
        return Unavailable.INSTANCE;
    }

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
