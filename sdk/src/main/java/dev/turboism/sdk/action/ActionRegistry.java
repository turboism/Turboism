package dev.turboism.sdk.action;

import dev.turboism.sdk.plugin.Registration;

import java.util.Optional;

import java.util.function.Consumer;

/**
 * Registry for actions that can be bound to menus or invoked by the framework.
 */
public interface ActionRegistry {

    Registration register(String id, Action action);

    interface Action {
        String id();

        String label();

        Consumer<ActionContext> handler();
    }

    interface ActionContext {
        /** UI control event for panel-originated actions, if this invocation has one. */
        default Optional<UiActionEvent> uiEvent() {
            return Optional.empty();
        }
    }
}
