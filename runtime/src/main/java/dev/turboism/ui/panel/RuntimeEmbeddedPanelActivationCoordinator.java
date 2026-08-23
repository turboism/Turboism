package dev.turboism.ui.panel;

import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.ui.EmbeddedPanelId;

import java.util.Objects;

/** Serial runtime owner for generation-bound embedded-panel activation. */
public final class RuntimeEmbeddedPanelActivationCoordinator implements AutoCloseable {

    private final Object monitor = new Object();
    private Binding binding;
    private boolean closed;

    /**
     * Binds the activation target for a host generation, replacing any previous binding — for
     * example when a second panel plugin is enabled after the first.
     *
     * @param hostGeneration positive generation of the panel host this target belongs to
     * @param target the host-specific activation implementation
     * @return a registration that unbinds; a no-op once a later binding has replaced this one
     * @throws IllegalArgumentException if {@code hostGeneration} is not positive
     * @throws NullPointerException if {@code target} is {@code null}
     * @throws IllegalStateException if this coordinator is closed
     */
    public Registration bind(final long hostGeneration, final ActivationTarget target) {
        if (hostGeneration <= 0) {
            throw new IllegalArgumentException("hostGeneration must be positive");
        }
        final Binding requested = new Binding(
            hostGeneration,
            Objects.requireNonNull(target, "target")
        );
        synchronized (monitor) {
            requireOpen();
            // A new contribution batch replaces the previous activation target
            // (e.g. a second panel plugin enabling after the first).
            binding = requested;
        }
        return () -> unbind(requested);
    }

    /**
     * Brings a panel to the front in its current docked or floating position.
     *
     * <p>The target is read under the lock and invoked outside it, so host UI work does not hold
     * up a concurrent rebind.
     *
     * @param pluginId non-blank id of the plugin requesting activation
     * @param panelId the panel to activate
     * @throws NullPointerException if either argument is {@code null}
     * @throws IllegalArgumentException if {@code pluginId} is blank
     * @throws IllegalStateException if this coordinator is closed or no target is bound
     */
    public void activate(final String pluginId, final EmbeddedPanelId panelId) {
        final String owner = requireText(pluginId, "pluginId");
        final EmbeddedPanelId requested = Objects.requireNonNull(panelId, "panelId");
        final ActivationTarget target;
        synchronized (monitor) {
            requireOpen();
            if (binding == null) {
                throw new IllegalStateException("embedded-panel activation is unavailable");
            }
            target = binding.target();
        }
        target.activate(owner, requested);
    }

    /**
     * Activates a panel, asking for it to be shown floating.
     *
     * <p>A host that cannot float falls back to ordinary activation, since
     * {@code ActivationTarget.activateFloating} defaults to {@code activate}.
     *
     * @param pluginId non-blank id of the plugin requesting activation
     * @param panelId the panel to activate
     * @throws NullPointerException if either argument is {@code null}
     * @throws IllegalArgumentException if {@code pluginId} is blank
     * @throws IllegalStateException if this coordinator is closed or no target is bound
     */
    public void activateFloating(final String pluginId, final EmbeddedPanelId panelId) {
        final String owner = requireText(pluginId, "pluginId");
        final EmbeddedPanelId requested = Objects.requireNonNull(panelId, "panelId");
        final ActivationTarget target;
        synchronized (monitor) {
            requireOpen();
            if (binding == null) {
                throw new IllegalStateException("embedded-panel activation is unavailable");
            }
            target = binding.target();
        }
        target.activateFloating(owner, requested);
    }

    @Override
    public void close() {
        synchronized (monitor) {
            if (closed) {
                return;
            }
            closed = true;
            binding = null;
        }
    }

    private void unbind(final Binding requested) {
        synchronized (monitor) {
            if (binding == requested) {
                binding = null;
            }
        }
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("embedded-panel activation coordinator is closed");
        }
    }

    private static String requireText(final String value, final String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private record Binding(long hostGeneration, ActivationTarget target) {
    }

    @FunctionalInterface
    public interface ActivationTarget {
        void activate(String pluginId, EmbeddedPanelId panelId);

        default void activateFloating(final String pluginId, final EmbeddedPanelId panelId) {
            activate(pluginId, panelId);
        }
    }
}
