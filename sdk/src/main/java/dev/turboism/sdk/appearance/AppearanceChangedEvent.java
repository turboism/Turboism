package dev.turboism.sdk.appearance;

import dev.turboism.sdk.PreviewApi;
import dev.turboism.sdk.event.EventBus;

import java.util.Objects;

/**
 * Runtime-owned observation published after the effective appearance changes, carrying both sides
 * of the transition and the plugin that caused it.
 *
 * <p>The compact constructor rejects {@code null} components and a blank origin plugin id.
 *
 * @param previous the appearance status in force before the change
 * @param current the appearance status in force after the change
 * @param originPluginId the plugin whose request caused the change, never blank
 */
@PreviewApi
public record AppearanceChangedEvent(
    AppearanceStatus previous,
    AppearanceStatus current,
    String originPluginId
) implements EventBus.TurboismEvent {
    public AppearanceChangedEvent {
        previous = Objects.requireNonNull(previous, "previous");
        current = Objects.requireNonNull(current, "current");
        Objects.requireNonNull(originPluginId, "originPluginId");
        if (originPluginId.isBlank()) {
            throw new IllegalArgumentException("originPluginId must not be blank");
        }
    }
}
