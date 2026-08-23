package dev.turboism.sdk.ui.table;

import dev.turboism.sdk.PreviewApi;
import dev.turboism.sdk.event.TurboismEvent;

import java.util.Objects;

/** Runtime-owned observation of a native Scene-table manual reorder. */
@PreviewApi
public record SceneTableItemOrderEvent(
    SceneTableService.ItemOrderChanged change
) implements TurboismEvent {
    public SceneTableItemOrderEvent {
        change = Objects.requireNonNull(change, "change");
    }
}
