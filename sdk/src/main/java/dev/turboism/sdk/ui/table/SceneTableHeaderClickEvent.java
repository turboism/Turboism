package dev.turboism.sdk.ui.table;

import dev.turboism.sdk.PreviewApi;
import dev.turboism.sdk.event.TurboismEvent;

import java.util.Objects;

/** Runtime-owned observation of a native Scene-table header click. */
@PreviewApi
public record SceneTableHeaderClickEvent(
    SceneTableService.HeaderClick click
) implements TurboismEvent {
    public SceneTableHeaderClickEvent {
        click = Objects.requireNonNull(click, "click");
    }
}
