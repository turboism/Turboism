package dev.turboism.sdk.mcp;

import dev.turboism.sdk.Incubating;
import dev.turboism.sdk.plugin.Registration;
import java.util.Optional;

/**
 * Process-local publication boundary for the current loopback Turboism MCP endpoint.
 *
 * <p>The runtime supplies a permission-scoped view to each plugin. A server plugin publishes one
 * connection for the lifetime of its returned registration; an automation plugin reads a detached
 * immutable snapshot.</p>
 */
@Incubating
public interface McpConnectionService {

    /**
     * Returns the currently published connection when the caller has read permission.
     *
     * @return the current loopback connection, or empty while no MCP server is enabled
     */
    Optional<McpHttpConnection> current();

    /**
     * Publishes a connection until the returned registration is closed.
     *
     * @param connection validated connection snapshot
     * @return idempotent revocation handle
     */
    Registration publish(McpHttpConnection connection);

    /**
     * Subscribes to connection changes under the same read permission as {@link #current()}.
     * The listener first receives the current snapshot (possibly empty) before this method
     * returns, then every subsequent publish and revoke. Listeners are invoked synchronously on
     * the publisher's thread and must return quickly without calling back into this service.
     *
     * @param listener receives the detached connection snapshots; empty signals revocation
     * @return idempotent unsubscription handle
     */
    default Registration subscribe(
            final java.util.function.Consumer<Optional<McpHttpConnection>> listener) {
        java.util.Objects.requireNonNull(listener, "listener");
        listener.accept(current());
        return () -> {};
    }

    /**
     * Reports whether a live runtime surface backs this instance.
     *
     * @return {@code false} only for the {@link #unavailable()} sentinel
     */
    default boolean isAvailable() {
        return true;
    }

    /** @return a fail-closed service used when runtime composition does not provide this capability */
    static McpConnectionService unavailable() {
        return Unavailable.INSTANCE;
    }

    /** Fail-closed implementation returned by {@link #unavailable()}. */
    enum Unavailable implements McpConnectionService {
        INSTANCE;

        @Override
        public boolean isAvailable() {
            return false;
        }

        @Override
        public Optional<McpHttpConnection> current() {
            return Optional.empty();
        }

        @Override
        public Registration publish(final McpHttpConnection connection) {
            throw new UnsupportedOperationException("MCP connection service is not available");
        }

        @Override
        public Registration subscribe(
                final java.util.function.Consumer<Optional<McpHttpConnection>> listener) {
            java.util.Objects.requireNonNull(listener, "listener").accept(Optional.empty());
            return () -> {};
        }
    }
}
