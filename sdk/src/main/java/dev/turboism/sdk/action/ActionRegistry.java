package dev.turboism.sdk.action;

import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.ui.context.ContextMenuSelection;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/** Registry for actions that can be bound to menus or invoked by the framework. */
public interface ActionRegistry {

    Registration register(String id, Action action);

    interface Action {
        String id();

        String label();

        Consumer<ActionContext> handler();

        /**
         * Returns an action with the given identity, display label and handler.
         *
         * @param id action identity matching the {@code register} id
         * @param label display text
         * @param handler invoked with the invocation context on trigger
         */
        static Action of(final String id, final String label, final Consumer<ActionContext> handler) {
            return new SimpleAction(id, label, handler);
        }
    }

    /**
     * Value-style {@link Action} implementation for registrations that carry
     * no per-callback state.
     *
     * @param id action identity
     * @param label display text
     * @param handler invoked with the invocation context on trigger
     */
    record SimpleAction(String id, String label, Consumer<ActionContext> handler) implements Action {
        public SimpleAction {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(label, "label");
            Objects.requireNonNull(handler, "handler");
        }
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
