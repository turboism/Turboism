package dev.turboism.sdk.cubism.event;

import dev.turboism.sdk.event.EventBus;

public record ProjectLifecycleEvent(
    String eventId,
    String projectId,
    String phase
) implements EventBus.TurboismEvent {

    public ProjectLifecycleEvent {
        requireText(eventId, "eventId");
        requireText(projectId, "projectId");
        requireText(phase, "phase");
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be null or blank");
        }
    }
}
