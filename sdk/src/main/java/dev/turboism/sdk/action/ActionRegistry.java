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
        /** UI control event for panel-originated actions, if this invocation has one. */
        default Optional<UiActionEvent> uiEvent() {
            return Optional.empty();
        }

        /** Object selection for context-menu-originated actions, if this invocation has one. */
        default Optional<ContextMenuSelection> contextMenuSelection() {
            return Optional.empty();
        }

        /** Panel-tab context for panel floating/docking actions, if present. */
        default Optional<dev.turboism.sdk.ui.context.PanelTabSelection> panelTabSelection() {
            return Optional.empty();
        }
    }
}
