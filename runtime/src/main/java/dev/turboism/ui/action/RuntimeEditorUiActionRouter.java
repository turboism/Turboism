package dev.turboism.ui.action;

import dev.turboism.core.action.RuntimeActionRegistry;
import dev.turboism.sdk.action.ActionRegistry;
import dev.turboism.sdk.action.UiActionEvent;
import dev.turboism.sdk.plugin.Registration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Shared action-router catalog keyed by the contribution owner's plugin ID. */
public final class RuntimeEditorUiActionRouter implements EditorUiActionRouter, AutoCloseable {

    private final ConcurrentHashMap<String, java.util.concurrent.CopyOnWriteArrayList<ActionRegistry>> registries =
            new ConcurrentHashMap<>();
    private final java.util.concurrent.CopyOnWriteArrayList<Runnable> changeListeners =
            new java.util.concurrent.CopyOnWriteArrayList<>();
    private volatile boolean closed;

    /**
     * Adds an action registry under the contributing plugin's ID. Several registries may be held
     * for one plugin; they are kept in registration order and all are consulted on invoke.
     *
     * @param pluginId owner of the contribution; must not be blank
     * @param registry registry to consult for this owner's actions
     * @return a registration that removes exactly this registry, dropping the owner's entry once
     *     its last registry is gone; safe to call after the router is closed
     * @throws IllegalStateException if the router is already closed
     * @throws NullPointerException if {@code registry} is null
     */
    public Registration register(final String pluginId, final ActionRegistry registry) {
        final String owner = requireText(pluginId, "pluginId");
        final ActionRegistry requested = Objects.requireNonNull(registry, "registry");
        if (closed) {
            throw new IllegalStateException("Editor UI action router is closed");
        }
        final java.util.concurrent.CopyOnWriteArrayList<ActionRegistry> owners =
                registries.computeIfAbsent(owner, ignored -> new java.util.concurrent.CopyOnWriteArrayList<>());
        owners.add(requested);
        notifyChanged();
        return () -> {
            owners.remove(requested);
            if (owners.isEmpty()) {
                registries.remove(owner, owners);
            }
            notifyChanged();
        };
    }

    /**
     * Adds a listener invoked inline whenever an owner's registry set changes (register and
     * unregister). The runtime keybinding service listens here so action bindings appear and
     * disappear with their registrations; listeners must be cheap and non-blocking.
     *
     * @return a registration that removes the listener
     */
    public Registration listen(final Runnable listener) {
        changeListeners.add(Objects.requireNonNull(listener, "listener"));
        return () -> changeListeners.remove(listener);
    }

    private void notifyChanged() {
        for (Runnable listener : changeListeners) {
            listener.run();
        }
    }

    @Override
    public void invoke(final String pluginId, final String actionId) {
        invoke(pluginId, actionId, EditorUiActionRouter.emptyContext());
    }

    @Override
    public void invoke(final String pluginId, final String actionId, final Optional<UiActionEvent> event) {
        invoke(pluginId, actionId, EditorUiActionRouter.context(Objects.requireNonNull(event, "event")));
    }

    /** Routes an action with a typed runtime-owned invocation context. */
    public void invoke(final String pluginId, final String actionId, final ActionRegistry.ActionContext context) {
        if (closed) {
            return;
        }
        final java.util.List<ActionRegistry> owners = registries.get(requireText(pluginId, "pluginId"));
        if (owners == null || owners.isEmpty()) {
            return;
        }
        final ActionRegistry registry = owners.get(owners.size() - 1);
        if (!(registry instanceof RuntimeActionRegistry runtime)) {
            throw new IllegalStateException("Editor UI actions require a runtime-owned action registry");
        }
        runtime.execute(requireText(actionId, "actionId"), Objects.requireNonNull(context, "context"));
    }

    /**
     * Returns a detached copy of the registered action registries keyed by owner plugin id,
     * in registration order. The runtime keybinding service enumerates this to expose every
     * registered action as a bindable row; as with {@link #invoke}, only the last registry
     * per owner is consulted.
     */
    public java.util.Map<String, java.util.List<ActionRegistry>> snapshot() {
        final java.util.Map<String, java.util.List<ActionRegistry>> copy = new java.util.LinkedHashMap<>();
        registries.forEach((owner, owners) -> copy.put(owner, java.util.List.copyOf(owners)));
        return java.util.Collections.unmodifiableMap(copy);
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
