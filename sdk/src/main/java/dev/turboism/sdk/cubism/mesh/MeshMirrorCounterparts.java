package dev.turboism.sdk.cubism.mesh;

import dev.turboism.sdk.CubismEditor;
import dev.turboism.sdk.plugin.Registration;

/**
 * Mirror counterpart resolution, defaulted by the framework and overridable by a plugin.
 *
 * @deprecated use {@link MeshMirrorControl#mirrorOf(MeshDeletion)} and
 *     {@link MeshMirrorControl#overrideResolver(MeshMirrorCounterpartResolver)} instead: the
 *     mirror axis, the counterpart rule, movement participation and the eligible-tool set are
 *     one capability, handed out together by {@code PluginService.MESH_MIRROR_CONTROL}.
 *     Scheduled for removal; every released operation keeps working through this thin adapter
 *     until then.
 */
@Deprecated(forRemoval = true)
@CubismEditor(from = "5.0.00")
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
     * @return {@code false} when the backend backing this instance is
     *         unavailable, including the {@link #unavailable()} sentinel
     */
    default boolean isAvailable() {
        return true;
    }

    /**
     * Returns this service's fail-closed {@code Unavailable} sentinel.
     *
     * @return the shared singleton; {@link #isAvailable()} is {@code false} only for it
     */
    static MeshMirrorCounterparts unavailable() {
        return Unavailable.INSTANCE;
    }

    /** Sentinel returned by {@link #unavailable()}: unsupported calls throw a stable {@link UnsupportedOperationException}. */
    enum Unavailable implements MeshMirrorCounterparts {
        INSTANCE;

        @Override
        public boolean isAvailable() {
            return false;
        }

        @Override
        public MeshEditContribution mirrorOf(final MeshDeletion deletion) {
            throw unavailable();
        }

        @Override
        public Registration overrideResolver(final MeshMirrorCounterpartResolver resolver) {
            throw unavailable();
        }

        private static UnsupportedOperationException unavailable() {
            return new UnsupportedOperationException("meshMirrorCounterparts service is not available");
        }
    }
}
