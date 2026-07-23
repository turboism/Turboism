package dev.turboism.sdk.appearance;

import dev.turboism.sdk.PreviewApi;
import dev.turboism.sdk.event.EventBus;

import java.util.Objects;

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
