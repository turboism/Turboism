package dev.turboism.adapter.cubism.integration;

import java.util.Optional;

/**
 * Reads the host's per-connection session record for a WebSocket.
 *
 * <p>The production implementation resolves the record exclusively through verified selectors
 * ({@code x.a} holder → {@code l.b(WebSocket)} session lookup → {@code ab} accessors); when the
 * verified member surface is absent the inspector reports empty and every editing request fails
 * closed as unregistered. Tests inject a fake keyed on their fake socket.</p>
 */
@FunctionalInterface
public interface EditConnectionInspector {

    /**
     * Looks up the connection record bound to {@code socket}.
     *
     * @param socket the host WebSocket handle (opaque to this layer)
     * @return the record snapshot, or empty when the socket has no session record or the
     *     inspector's verified bindings are unavailable
     */
    Optional<EditConnectionInfo> inspect(Object socket);

    /** {@return an inspector that reports no record for any socket} */
    static EditConnectionInspector unavailable() {
        return socket -> Optional.empty();
    }
}
