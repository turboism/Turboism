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
}
