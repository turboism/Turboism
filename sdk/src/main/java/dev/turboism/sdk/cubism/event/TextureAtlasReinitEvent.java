package dev.turboism.sdk.cubism.event;

import dev.turboism.sdk.event.EventBus;

public record TextureAtlasReinitEvent(
    String eventId,
    String atlasId,
    String phase
) implements EventBus.TurboismEvent {

    public TextureAtlasReinitEvent {
        requireText(eventId, "eventId");
        requireText(atlasId, "atlasId");
        requireText(phase, "phase");
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be null or blank");
        }
    }
}
