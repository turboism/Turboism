package dev.turboism.sdk.cubism.event;

import dev.turboism.sdk.PreviewApi;
import dev.turboism.sdk.event.EventBus;

import java.util.List;

@PreviewApi
public record SelectionChangedEvent(
    String eventId,
    List<String> selectedObjectIds
) implements EventBus.TurboismEvent {

    public SelectionChangedEvent {
        if (eventId == null || eventId.isBlank()) {
            throw new IllegalArgumentException("eventId must not be null or blank");
        }
        selectedObjectIds = List.copyOf(selectedObjectIds);
    }
}
