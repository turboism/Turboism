package dev.turboism.ui.action;

import dev.turboism.sdk.action.ActionRegistry;
import dev.turboism.sdk.action.UiActionEvent;

import java.util.Objects;
import java.util.Optional;

/** Runtime-owned route from native Editor UI callbacks to plugin action registries. */
@FunctionalInterface
public interface EditorUiActionRouter {

    /**
     * Delivers one native UI action callback to the owning plugin's registry.
     *
     * @param pluginId the plugin that registered the action
     * @param actionId the action id within that plugin
     */
    void invoke(String pluginId, String actionId);

    /**
     * Delivers a callback carrying the originating UI event.
     *
     * <p>This default discards the event — implementations that do not support action
     * contexts still receive the invocation.</p>
     *
     * @param pluginId the plugin that registered the action
     * @param actionId the action id within that plugin
     * @param event the UI event that produced the callback
     */
    default void invoke(
        final String pluginId,
        final String actionId,
        final Optional<UiActionEvent> event
    ) {
        invoke(pluginId, actionId);
    }

    /**
     * Delivers a callback carrying an action context.
     *
     * <p>This default requires a non-null context but discards it — implementations that do
     * not support contexts still receive the invocation.</p>
     *
     * @param pluginId the plugin that registered the action
     * @param actionId the action id within that plugin
     * @param context the action context to deliver
     * @throws NullPointerException if {@code context} is null
     */
    default void invoke(
        final String pluginId,
        final String actionId,
        final ActionRegistry.ActionContext context
    ) {
        Objects.requireNonNull(context, "context");
        invoke(pluginId, actionId);
    }

    /**
     * @return a router that silently drops every invocation
     */
    static EditorUiActionRouter unavailable() {
        return (pluginId, actionId) -> { };
    }

    /**
     * @return an action context carrying no UI event
     */
    static ActionRegistry.ActionContext emptyContext() {
        return context(Optional.empty());
    }

    /**
     * @param event the UI event to expose, non-null (may be {@link Optional#empty()})
     * @return an action context whose {@code uiEvent()} returns {@code event}
     * @throws NullPointerException if {@code event} is null
     */
    static ActionRegistry.ActionContext context(final Optional<UiActionEvent> event) {
        final Optional<UiActionEvent> snapshot = Objects.requireNonNull(event, "event");
        return new ActionRegistry.ActionContext() {
            @Override
            public Optional<UiActionEvent> uiEvent() {
                return snapshot;
            }
        };
    }
}
