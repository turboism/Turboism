package dev.turboism.sdk.task;

import java.time.Duration;
import java.util.Objects;

/**
 * A request to run a plugin action repeatedly, each run starting {@code delay} after the
 * previous run finished.
 *
 * <p>The compact constructor rejects null components, a negative {@code initialDelay} and a
 * {@code delay} that is not strictly positive, so a constructed request always describes a
 * repetition that can make progress.
 *
 * @param id caller-chosen identity of the repeating task; the scheduler rejects a submission
 *     whose id is already active
 * @param kind workload class the scheduler uses to pick an execution lane
 * @param priority relative ordering hint within that lane
 * @param initialDelay wait before the first run; may be {@link java.time.Duration#ZERO}
 * @param delay wait between the end of one run and the start of the next; must be positive
 * @param action the work to run on each repetition
 */
public record FixedDelayTaskRequest(
    TaskId id,
    PluginTaskKind kind,
    PluginTaskPriority priority,
    Duration initialDelay,
    Duration delay,
    PluginTaskAction action
) {
    /**
     * Validates the record components.
     *
     * @throws NullPointerException if any component is {@code null}
     * @throws IllegalArgumentException if {@code initialDelay} is negative or {@code delay} is not
     *     positive
     */
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
