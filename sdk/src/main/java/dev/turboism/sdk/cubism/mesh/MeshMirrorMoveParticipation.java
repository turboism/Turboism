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
}
