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
}
