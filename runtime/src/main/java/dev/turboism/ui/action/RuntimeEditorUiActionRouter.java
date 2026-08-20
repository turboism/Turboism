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
        final java.util.concurrent.CopyOnWriteArrayList<ActionRegistry> owners = registries.computeIfAbsent(
            owner,
            ignored -> new java.util.concurrent.CopyOnWriteArrayList<>()
        );
        owners.add(requested);
        return () -> {
            owners.remove(requested);
            if (owners.isEmpty()) {
                registries.remove(owner, owners);
            }
        };
    }

    @Override
    public void invoke(final String pluginId, final String actionId) {
        invoke(pluginId, actionId, EditorUiActionRouter.emptyContext());
    }

    @Override
    public void invoke(
        final String pluginId,
        final String actionId,
        final Optional<UiActionEvent> event
    ) {
        invoke(pluginId, actionId, EditorUiActionRouter.context(Objects.requireNonNull(event, "event")));
    }

    /** Routes an action with a typed runtime-owned invocation context. */
    public void invoke(
        final String pluginId,
        final String actionId,
        final ActionRegistry.ActionContext context
    ) {
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
        runtime.execute(
            requireText(actionId, "actionId"),
            Objects.requireNonNull(context, "context")
        );
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
