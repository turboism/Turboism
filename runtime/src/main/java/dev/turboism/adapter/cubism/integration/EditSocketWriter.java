package dev.turboism.adapter.cubism.integration;

/**
 * Sends a serialized response frame back over the host-owned WebSocket.
 *
 * <p>The socket object is a host-loaded {@code org.java_websocket.WebSocket}; this layer never
 * links against that type directly, so the bridge stays usable from the bootstrap-visible agent
 * classes and from unit tests with a plain fake.</p>
 */
@FunctionalInterface
public interface EditSocketWriter {

    /**
     * Sends one text frame.
     *
     * @param socket the host WebSocket handle (opaque to this layer)
     * @param text   the serialized envelope
     * @throws Exception when the frame cannot be written
     */
    void send(Object socket, String text) throws Exception;

    /**
     * {@return a writer that calls the socket's public {@code send(String)} via reflection}
     *
     * <p>{@code org.java_websocket.WebSocket.send(String)} is a public interface method, so a
     * reflective lookup on the concrete connection object resolves it without any host type
     * appearing in this layer's signatures.</p>
     */
    static EditSocketWriter reflective() {
        return (socket, text) -> socket.getClass()
            .getMethod("send", String.class)
            .invoke(socket, text);
    }
}
