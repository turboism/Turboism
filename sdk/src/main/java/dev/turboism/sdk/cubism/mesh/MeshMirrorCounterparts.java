package dev.turboism.sdk.cubism.mesh;

import dev.turboism.sdk.plugin.Registration;

/**
 * Mirror counterpart resolution, defaulted by the framework and overridable by a plugin.
 */
public interface MeshMirrorCounterparts {

    /**
     * The counterparts of everything in {@code deletion}, using whichever resolver applies.
     *
     * <p>With the framework default this runs entirely inside the runtime against live host
     * geometry: nothing is copied across the boundary per point.</p>
     */
    MeshEditContribution mirrorOf(MeshDeletion deletion);

    /**
     * Replaces the default rule for this plugin. See
     * {@link MeshMirrorCounterpartResolver} for the cost this incurs.
     */
    Registration overrideResolver(MeshMirrorCounterpartResolver resolver);

    /**
     * Reports whether a live runtime surface backs this instance.
     *
     * @return {@code false} only for the {@link #unavailable()} sentinel
     */
    default boolean isAvailable() {
        return true;
    }

    static MeshMirrorCounterparts unavailable() {
        return Unavailable.INSTANCE;
    }

    enum Unavailable implements MeshMirrorCounterparts {
        INSTANCE;

        @Override public boolean isAvailable() {
            return false;
        }

        @Override public MeshEditContribution mirrorOf(final MeshDeletion deletion) {
            throw unavailable();
        }

        @Override public Registration overrideResolver(final MeshMirrorCounterpartResolver resolver) {
            throw unavailable();
        }

        private static UnsupportedOperationException unavailable() {
            return new UnsupportedOperationException(
                "meshMirrorCounterparts service is not available");
        }
    }
}
