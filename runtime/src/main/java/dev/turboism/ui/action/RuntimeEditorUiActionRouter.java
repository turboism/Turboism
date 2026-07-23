package dev.turboism.ui.action;

import dev.turboism.core.action.RuntimeActionRegistry;
import dev.turboism.sdk.action.ActionRegistry;
import dev.turboism.sdk.plugin.Registration;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/** Shared action-router catalog keyed by the contribution owner's plugin ID. */
public final class RuntimeEditorUiActionRouter implements EditorUiActionRouter, AutoCloseable {

    private final ConcurrentHashMap<String, ActionRegistry> registries = new ConcurrentHashMap<>();
    private volatile boolean closed;

    public Registration register(final String pluginId, final ActionRegistry registry) {
        final String owner = requireText(pluginId, "pluginId");
        final ActionRegistry requested = Objects.requireNonNull(registry, "registry");
        if (closed) {
            throw new IllegalStateException("Editor UI action router is closed");
        }
        final ActionRegistry previous = registries.putIfAbsent(owner, requested);
        if (previous != null && previous != requested) {
            throw new IllegalStateException("Editor UI action registry is already bound for " + owner);
        }
        return () -> registries.remove(owner, requested);
    }

    @Override
    public void invoke(final String pluginId, final String actionId) {
        if (closed) {
            return;
        }
        final ActionRegistry registry = registries.get(requireText(pluginId, "pluginId"));
        if (registry == null) {
            return;
        }
        if (!(registry instanceof RuntimeActionRegistry runtime)) {
            throw new IllegalStateException("Editor UI actions require a runtime-owned action registry");
        }
        runtime.execute(requireText(actionId, "actionId"), EditorUiActionRouter.emptyContext());
    }

    @Override
    public void close() {
        closed = true;
        registries.clear();
    }

    private static String requireText(final String value, final String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
