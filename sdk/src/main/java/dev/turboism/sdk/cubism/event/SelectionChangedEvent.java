package dev.turboism.sdk.cubism.event;

import dev.turboism.sdk.cubism.service.query.SelectionSummary;
import dev.turboism.sdk.event.TurboismEvent;
import java.util.Objects;

/**
 * Runtime-owned selection transition detected while reading a fresh host snapshot.
 *
 * <p>Delivery is pull-based and asynchronous: the session-bounded observer and
 * {@code SelectionQueryService} queries share one snapshot baseline, and an event is
 * published only when a read detects an actual transition. Current verified host
 * integrations report a constant empty selection, so this event observes the shared
 * baseline rather than native object-selection pushes; see {@code sdk/event-coverage.md}
 * for the supported-origins statement.</p>
 */
public record SelectionChangedEvent(
    SelectionSummary previousSelection,
    SelectionSummary currentSelection
) implements TurboismEvent {
    public SelectionChangedEvent {
        previousSelection = Objects.requireNonNull(previousSelection, "previousSelection");
        currentSelection = Objects.requireNonNull(currentSelection, "currentSelection");
    }
}
