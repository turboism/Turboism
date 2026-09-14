package dev.turboism.sdk.action;

import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.ui.context.ContextMenuSelection;

import java.util.Optional;
import java.util.function.Consumer;

/** Registry for actions that can be bound to menus or invoked by the framework. */
public interface ActionRegistry {

    /**
     * Registers an action under {@code id} and returns the registration handle whose
     * {@code close()} removes it again.
     *
     * @param id framework-unique action id
     * @param action the action definition to publish
     * @return the registration; closing it unregisters the action
     */
    Registration register(String id, Action action);

    /** An invocable action shown under its {@link #label()} and dispatched to {@link #handler()}. */
    interface Action {
        /** Returns the framework-unique action id. */
        String id();

        /** Returns the user-facing action label. */
        String label();

        /** Returns the callback invoked with the invocation's {@link ActionContext}. */
        Consumer<ActionContext> handler();
    }

    /** Per-invocation context handed to an {@link Action} handler. */
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
