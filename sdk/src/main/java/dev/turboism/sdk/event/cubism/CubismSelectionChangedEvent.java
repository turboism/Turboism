package dev.turboism.sdk.event.cubism;

import dev.turboism.sdk.cubism.service.query.SelectionSummary;
import java.util.Objects;

public record CubismSelectionChangedEvent(
    SelectionSummary previousSelection,
    SelectionSummary currentSelection
) {
    public CubismSelectionChangedEvent {
        previousSelection = Objects.requireNonNull(previousSelection, "previousSelection");
        currentSelection = Objects.requireNonNull(currentSelection, "currentSelection");
    }
}
