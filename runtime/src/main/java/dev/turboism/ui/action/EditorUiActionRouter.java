package dev.turboism.ui.action;

import dev.turboism.sdk.action.ActionRegistry;

/** Runtime-owned route from native Editor UI callbacks to plugin action registries. */
@FunctionalInterface
public interface EditorUiActionRouter {

    void invoke(String pluginId, String actionId);

    static EditorUiActionRouter unavailable() {
        return (pluginId, actionId) -> { };
    }

    static ActionRegistry.ActionContext emptyContext() {
        return new ActionRegistry.ActionContext() { };
    }
}
