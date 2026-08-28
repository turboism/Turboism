package dev.turboism.sdk.mcp;

import dev.turboism.sdk.plugin.Registration;

import java.util.Optional;

/**
 * Process-local publication boundary for the current authenticated Turboism MCP endpoint.
 *
 * <p>The runtime supplies a permission-scoped view to each plugin. A server plugin publishes one
 * connection for the lifetime of its returned registration; an automation plugin reads a detached
 * immutable snapshot. The service never persists authorization material.</p>
 */
public interface McpConnectionService {

    /**
     * Returns the currently published connection when the caller has read permission.
     *
     * @return the current authenticated connection, or empty while no MCP server is enabled
     */
    Optional<McpHttpConnection> current();

    /**
     * Publishes a connection until the returned registration is closed.
     *
     * @param connection validated connection snapshot
     * @return idempotent revocation handle
     */
    Registration publish(McpHttpConnection connection);

    /** @return a fail-closed service used when runtime composition does not provide this capability */
    static McpConnectionService unavailable() {
        return Unavailable.INSTANCE;
    }

    enum Unavailable implements McpConnectionService {
        INSTANCE;

        @Override
        public Optional<McpHttpConnection> current() {
            return Optional.empty();
        }

        @Override
        public Registration publish(final McpHttpConnection connection) {
            throw new UnsupportedOperationException("MCP connection service is not available");
        }
    }
}
