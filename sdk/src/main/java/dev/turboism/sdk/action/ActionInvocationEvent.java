package dev.turboism.sdk.action;

import dev.turboism.sdk.PreviewApi;
import dev.turboism.sdk.event.EventBus;

import java.util.Objects;
import java.util.Optional;

/** Runtime-owned observation that an action was accepted for asynchronous execution. */
@PreviewApi
public record ActionInvocationEvent(
    String pluginId,
    String actionId,
    Optional<UiActionEvent> uiEvent,
    boolean contextMenuInvocation,
    boolean panelTabInvocation
) implements EventBus.TurboismEvent {

    public ActionInvocationEvent {
        pluginId = requireText(pluginId, "pluginId");
        actionId = requireText(actionId, "actionId");
        uiEvent = Objects.requireNonNull(uiEvent, "uiEvent");
    }

    private static String requireText(final String value, final String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
