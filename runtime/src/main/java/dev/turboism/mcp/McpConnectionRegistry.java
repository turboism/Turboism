package dev.turboism.mcp;

import dev.turboism.sdk.mcp.McpHttpConnection;
import dev.turboism.sdk.plugin.Registration;

import java.util.Optional;

/** Process-owned, non-persistent authenticated MCP connection slot. */
public final class McpConnectionRegistry implements AutoCloseable {

    private long generation;
    private boolean closed;
    private Published published;

    /**
     * Replaces the owner's prior publication and returns a generation-bound revocation handle.
     * A second publisher is rejected so a consumer can never be silently redirected to another
     * plugin's endpoint.
     */
    public synchronized Registration publish(
        final String ownerPluginId,
        final McpHttpConnection connection
    ) {
        if (closed) {
            throw new IllegalStateException("MCP connection registry is closed");
        }
        final String owner = requireOwner(ownerPluginId);
        if (published != null && !published.ownerPluginId().equals(owner)) {
            throw new IllegalStateException("An MCP connection is already published by another plugin");
        }
        final long publicationGeneration = ++generation;
        published = new Published(owner, connection, publicationGeneration);
        return new Registration() {
            private boolean closed;

            @Override
            public void close() {
                synchronized (McpConnectionRegistry.this) {
                    if (closed) return;
                    closed = true;
                    if (published != null && published.generation() == publicationGeneration) {
                        published = null;
                    }
                }
            }
        };
    }

    /** @return the current immutable connection snapshot */
    public synchronized Optional<McpHttpConnection> current() {
        return published == null ? Optional.empty() : Optional.of(published.connection());
    }

    /** Clears all process-local authorization material. */
    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        published = null;
        generation++;
    }

    private static String requireOwner(final String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("ownerPluginId must not be blank");
        }
        return value;
    }

    private record Published(
        String ownerPluginId,
        McpHttpConnection connection,
        long generation
    ) {
        private Published {
            java.util.Objects.requireNonNull(connection, "connection");
        }
    }
}
