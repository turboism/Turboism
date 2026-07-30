package dev.turboism.sdk.action;

import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.ui.context.ContextMenuSelection;

import java.util.Optional;
import java.util.function.Consumer;

/** Registry for actions that can be bound to menus or invoked by the framework. */
public interface ActionRegistry {

    Registration register(String id, Action action);

    interface Action {
        String id();

        String label();

        Consumer<ActionContext> handler();
    }

    interface ActionContext {
        /** Present when the action originated from an object context menu. */
        default Optional<ContextMenuSelection> contextMenuSelection() {
            return Optional.empty();
        }
    }
}
