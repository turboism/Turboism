package dev.turboism.ui.action;

import dev.turboism.sdk.action.ActionRegistry;
import dev.turboism.sdk.action.UiActionEvent;

import java.util.Objects;
import java.util.Optional;

/** Runtime-owned route from native Editor UI callbacks to plugin action registries. */
@FunctionalInterface
public interface EditorUiActionRouter {

    void invoke(String pluginId, String actionId);

    default void invoke(
        final String pluginId,
        final String actionId,
        final Optional<UiActionEvent> event
    ) {
        invoke(pluginId, actionId);
    }

    static EditorUiActionRouter unavailable() {
        return (pluginId, actionId) -> { };
    }

    static ActionRegistry.ActionContext emptyContext() {
        return context(Optional.empty());
    }

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
