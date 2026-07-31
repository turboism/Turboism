package dev.turboism.ui.panel;

import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.ui.EmbeddedPanelId;

import java.util.Objects;

/** Serial runtime owner for generation-bound embedded-panel activation. */
public final class RuntimeEmbeddedPanelActivationCoordinator implements AutoCloseable {

    private final Object monitor = new Object();
    private Binding binding;
    private boolean closed;

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
