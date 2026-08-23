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

    /**
     * Binds the current native panel host and immediately installs whatever contributions are
     * already held.
     *
     * <p>Binding is single-slot: a later panel-provider batch replaces the previous host, closing
     * its native registration first.
     *
     * @param value the host that can install panel-tab menu contributions
     * @return a registration that unbinds this host and closes its native registration; a no-op
     *     if a different host has since been bound
     * @throws NullPointerException if {@code value} is {@code null}
     * @throws IllegalStateException if this coordinator is closed
     */
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

    /**
     * Replaces the whole contribution set and reinstalls it against the bound host.
     *
     * <p>The list is defensively copied and every entry must belong to {@code hostGeneration}, so
     * a batch computed against a stale host cannot be installed. With no host bound, or an empty
     * list, the native registration is simply closed and nothing is installed.
     *
     * @param hostGeneration generation every contribution must declare
     * @param values the complete new contribution set
     * @throws NullPointerException if {@code values} is {@code null} or holds a {@code null}
     * @throws IllegalArgumentException if any contribution declares a different generation
     * @throws IllegalStateException if this coordinator is closed
     */
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
