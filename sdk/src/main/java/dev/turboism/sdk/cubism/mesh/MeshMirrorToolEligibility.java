package dev.turboism.sdk.cubism.mesh;

import dev.turboism.sdk.plugin.Registration;

import java.util.Set;

/** Extends the native set of mesh subtools that may keep mirror editing active. */
public interface MeshMirrorToolEligibility {

    Registration extendEligibleTools(Set<MeshEditTool> tools);
}
