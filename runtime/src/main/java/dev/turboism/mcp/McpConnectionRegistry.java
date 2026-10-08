package dev.turboism.mcp;

import dev.turboism.sdk.mcp.McpHttpConnection;
import dev.turboism.sdk.plugin.Registration;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/** Process-owned, non-persistent loopback MCP connection slot. */
public final class McpConnectionRegistry implements AutoCloseable {

    private final CopyOnWriteArrayList<Consumer<Optional<McpHttpConnection>>> listeners = new CopyOnWriteArrayList<>();
    private long generation;
    private boolean closed;
    private Published published;

    /**
     * Replaces the owner's prior publication and returns a generation-bound revocation handle.
     * A second publisher is rejected so a consumer can never be silently redirected to another
     * plugin's endpoint.
     */
    public synchronized Registration publish(final String ownerPluginId, final McpHttpConnection connection) {
        if (closed) {
            throw new IllegalStateException("MCP connection registry is closed");
        }
        final String owner = requireOwner(ownerPluginId);
        if (published != null && !published.ownerPluginId().equals(owner)) {
            throw new IllegalStateException("An MCP connection is already published by another plugin");
        }
        final long publicationGeneration = ++generation;
        published = new Published(owner, connection, publicationGeneration);
        notifyListeners(Optional.of(connection));
        return new Registration() {
            private boolean closed;

            @Override
            public void close() {
                synchronized (McpConnectionRegistry.this) {
                    if (closed) return;
                    closed = true;
                    if (published != null && published.generation() == publicationGeneration) {
                        published = null;
                        notifyListeners(Optional.empty());
                    }
                }
            }
        };
    }

    /**
     * Registers a listener that first receives the current snapshot (possibly empty) inside the
     * registry monitor, then every subsequent publish and revoke in publication order. Listeners
     * run synchronously on the caller's thread and must return quickly without calling back into
     * this registry.
     */
    public synchronized Registration subscribe(final Consumer<Optional<McpHttpConnection>> listener) {
        java.util.Objects.requireNonNull(listener, "listener");
        if (closed) {
            try {
                listener.accept(Optional.empty());
            } catch (RuntimeException ignored) {
                // Same policy as change notifications.
            }
            return () -> {};
        }
        listeners.add(listener);
        try {
            listener.accept(published == null ? Optional.empty() : Optional.of(published.connection()));
        } catch (RuntimeException ignored) {
            // Keep the subscription: the initial replay failure must not hide future changes.
        }
        return new Registration() {
            private boolean closed;

            @Override
            public void close() {
                synchronized (McpConnectionRegistry.this) {
                    if (closed) return;
                    closed = true;
                    listeners.remove(listener);
                }
            }
        };
    }

    /** @return the current immutable connection snapshot */
    public synchronized Optional<McpHttpConnection> current() {
        return published == null ? Optional.empty() : Optional.of(published.connection());
    }

    /** Clears the current process-local endpoint publication. */
    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        if (published != null) {
            published = null;
            notifyListeners(Optional.empty());
        }
        generation++;
    }

    private void notifyListeners(final Optional<McpHttpConnection> snapshot) {
        for (final Consumer<Optional<McpHttpConnection>> listener : listeners) {
            try {
                listener.accept(snapshot);
            } catch (RuntimeException ignored) {
                // A failed consumer must not corrupt the registry or block other consumers.
            }
        }
    }

    private static String requireOwner(final String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("ownerPluginId must not be blank");
        }
        return value;
    }

    private record Published(String ownerPluginId, McpHttpConnection connection, long generation) {
        private Published {
            java.util.Objects.requireNonNull(connection, "connection");
        }
    }
}
