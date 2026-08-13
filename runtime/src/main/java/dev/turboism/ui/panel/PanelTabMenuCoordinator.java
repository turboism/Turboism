package dev.turboism.ui.panel;

import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.ui.context.ContextMenuRegistry;

import java.util.List;
import java.util.Objects;

/** Joins plugin-owned panel-tab menu policy with the current native panel host. */
public final class PanelTabMenuCoordinator implements AutoCloseable {

    private final Object monitor = new Object();
    private Host host;
    private List<PanelTabMenuContribution> contributions = List.of();
    private Registration nativeRegistration;
    private boolean closed;

    public Registration bindHost(final Host value) {
        final Host requested = Objects.requireNonNull(value, "host");
        synchronized (monitor) {
            requireOpen();
            // A later panel provider batch replaces the previous host binding.
            if (host != null && host != requested) {
                closeNative();
            }
            host = requested;
            reconcile();
        }
        return () -> unbindHost(requested);
    }

    public void update(final long hostGeneration, final List<PanelTabMenuContribution> values) {
        synchronized (monitor) {
            requireOpen();
            final List<PanelTabMenuContribution> requested = List.copyOf(
                Objects.requireNonNull(values, "contributions")
            );
            requested.forEach(value -> {
                if (value.hostGeneration() != hostGeneration) {
                    throw new IllegalArgumentException("panel-tab menu contribution generation mismatch");
                }
            });
            contributions = requested;
            reconcile();
        }
    }

    private void unbindHost(final Host requested) {
        synchronized (monitor) {
            if (host != requested) {
                return;
            }
            closeNative();
            host = null;
        }
    }

    private void reconcile() {
        closeNative();
        if (host == null || contributions.isEmpty()) {
            return;
        }
        nativeRegistration = Objects.requireNonNull(
            host.install(contributions),
            "host.install()"
        );
    }

    private void closeNative() {
        if (nativeRegistration == null) {
            return;
        }
        final Registration existing = nativeRegistration;
        nativeRegistration = null;
        existing.close();
    }

    @Override
    public void close() {
        synchronized (monitor) {
            if (closed) {
                return;
            }
            closed = true;
            closeNative();
            host = null;
            contributions = List.of();
        }
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("panel-tab menu coordinator is closed");
        }
    }

    @FunctionalInterface
    public interface Host {
        Registration install(List<PanelTabMenuContribution> contributions);
    }
}
