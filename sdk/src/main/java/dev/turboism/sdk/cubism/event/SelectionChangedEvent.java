package dev.turboism.sdk.cubism.event;

import dev.turboism.sdk.cubism.service.query.SelectionSummary;
import java.util.Objects;

/** Selection transition observed by the Cubism query interface. */
public record SelectionChangedEvent(
    SelectionSummary previousSelection,
    SelectionSummary currentSelection
) {
    public SelectionChangedEvent {
        previousSelection = Objects.requireNonNull(previousSelection, "previousSelection");
        currentSelection = Objects.requireNonNull(currentSelection, "currentSelection");
    }
}
