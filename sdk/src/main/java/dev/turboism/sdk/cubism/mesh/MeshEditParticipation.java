package dev.turboism.sdk.cubism.mesh;

import dev.turboism.sdk.plugin.Registration;

/**
 * Lets a plugin take part in host-initiated mesh edits.
 *
 * <p>Participation only fires where the framework actually intercepts the host. On a host that
 * already performs the edit natively there is no interception and no callback, so behaviour
 * cannot be applied twice.</p>
 */
public interface MeshEditParticipation {

    /**
     * @throws SecurityException if the plugin does not hold the mesh write permission
     */
    Registration participate(MeshEditParticipant participant);
}
