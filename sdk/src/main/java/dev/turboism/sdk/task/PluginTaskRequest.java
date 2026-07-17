package dev.turboism.sdk.task;

import java.util.Objects;

public record PluginTaskRequest(
    TaskId id,
    PluginTaskKind kind,
    PluginTaskPriority priority,
    PluginTaskAction action
) {
    public PluginTaskRequest {
        id = Objects.requireNonNull(id, "id");
        kind = Objects.requireNonNull(kind, "kind");
        priority = Objects.requireNonNull(priority, "priority");
        action = Objects.requireNonNull(action, "action");
    }
}
