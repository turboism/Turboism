package dev.turboism.sdk.task;

import java.time.Duration;
import java.util.Objects;

public record FixedDelayTaskRequest(
    TaskId id,
    PluginTaskKind kind,
    PluginTaskPriority priority,
    Duration initialDelay,
    Duration delay,
    PluginTaskAction action
) {
    public FixedDelayTaskRequest {
        id = Objects.requireNonNull(id, "id");
        kind = Objects.requireNonNull(kind, "kind");
        priority = Objects.requireNonNull(priority, "priority");
        initialDelay = Objects.requireNonNull(initialDelay, "initialDelay");
        delay = Objects.requireNonNull(delay, "delay");
        action = Objects.requireNonNull(action, "action");
        if (initialDelay.isNegative()) {
            throw new IllegalArgumentException("initialDelay must not be negative");
        }
        if (delay.isZero() || delay.isNegative()) {
            throw new IllegalArgumentException("delay must be positive");
        }
    }
}
