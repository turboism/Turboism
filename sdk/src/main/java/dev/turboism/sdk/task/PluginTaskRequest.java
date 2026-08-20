package dev.turboism.sdk.task;

import java.util.Objects;

/**
 * A request to run a plugin action exactly once.
 *
 * @param id caller-chosen identity of the task; the scheduler rejects a submission whose id is
 *     already active
 * @param kind workload class the scheduler uses to pick an execution lane
 * @param priority relative ordering hint within that lane
 * @param action the work to run
 */
public record PluginTaskRequest(
    TaskId id,
    PluginTaskKind kind,
    PluginTaskPriority priority,
    PluginTaskAction action
) {
    /**
     * Validates the record components.
     *
     * @throws NullPointerException if any component is {@code null}
     */
    public PluginTaskRequest {
        id = Objects.requireNonNull(id, "id");
        kind = Objects.requireNonNull(kind, "kind");
        priority = Objects.requireNonNull(priority, "priority");
        action = Objects.requireNonNull(action, "action");
    }
}
