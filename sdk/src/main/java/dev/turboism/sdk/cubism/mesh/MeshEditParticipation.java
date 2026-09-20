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

    /**
     * Reports whether a live runtime surface backs this instance.
     *
     * @return {@code false} only for the {@link #unavailable()} sentinel
     */
    default boolean isAvailable() {
        return true;
    }

    static MeshEditParticipation unavailable() {
        return Unavailable.INSTANCE;
    }

    enum Unavailable implements MeshEditParticipation {
        INSTANCE;

        @Override public boolean isAvailable() {
            return false;
        }

        @Override public Registration participate(final MeshEditParticipant participant) {
            throw new UnsupportedOperationException(
                "meshEditParticipation service is not available");
        }
    }
}
