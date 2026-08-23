package dev.turboism.sdk.ui.table;

import dev.turboism.sdk.PreviewApi;
import dev.turboism.sdk.event.TurboismEvent;

import java.util.Objects;

/** Runtime-owned latest Scene-table state observed from the native palette. */
@PreviewApi
public record SceneTableSnapshotEvent(
    SceneTableService.TableSnapshot snapshot
) implements TurboismEvent {
    public SceneTableSnapshotEvent {
        snapshot = Objects.requireNonNull(snapshot, "snapshot");
    }
}
