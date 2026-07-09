package dev.turboism.sdk.cubism.event;

import dev.turboism.sdk.event.EventBus;

public record RenderStatusChangedEvent(
    String eventId,
    boolean rendering,
    double framesPerSecond
) implements EventBus.TurboismEvent {

    public RenderStatusChangedEvent {
        if (eventId == null || eventId.isBlank()) {
            throw new IllegalArgumentException("eventId must not be null or blank");
        }
        if (framesPerSecond < 0.0) {
            throw new IllegalArgumentException("framesPerSecond must not be negative");
        }
    }
}
