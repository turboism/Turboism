package dev.turboism.sdk.cubism.event;

import dev.turboism.sdk.cubism.service.query.SelectionSummary;
import dev.turboism.sdk.event.TurboismEvent;
import java.util.Objects;

/** Runtime-owned selection transition detected while reading a fresh host snapshot. */
public record SelectionChangedEvent(
    SelectionSummary previousSelection,
    SelectionSummary currentSelection
) implements TurboismEvent {
    public SelectionChangedEvent {
        previousSelection = Objects.requireNonNull(previousSelection, "previousSelection");
        currentSelection = Objects.requireNonNull(currentSelection, "currentSelection");
    }
}
