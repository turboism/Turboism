package dev.turboism.adapter.cubism.integration;

import java.util.Objects;

/**
 * The host-side connection record snapshot the bridge reads for one WebSocket.
 *
 * <p>Mirrors the members the low-version dispatcher consults on its {@code ab} session record:
 * {@code registered} is the registration-complete flag the native precheck enforces
 * ({@code ab.h()}), {@code authorized} is the persisted plugin authorization
 * ({@code ab.g()} = {@code getAuth}), and {@code sessionKey} is the per-connection UUID the
 * official protocol binds edit-session ownership to ({@code ab.i()}).</p>
 *
 * @param registered whether the connection completed native {@code RegisterPlugin}
 * @param authorized whether the connection holds the persisted plugin authorization
 * @param sessionKey the host-generated per-connection session key, never {@code null}
 * @param pluginName the registered plugin name when the record exposes it, never {@code null}
 */
public record EditConnectionInfo(
    boolean registered,
    boolean authorized,
    String sessionKey,
    String pluginName
) {
    public EditConnectionInfo {
        sessionKey = Objects.requireNonNullElse(sessionKey, "");
        pluginName = Objects.requireNonNullElse(pluginName, "");
    }
}
