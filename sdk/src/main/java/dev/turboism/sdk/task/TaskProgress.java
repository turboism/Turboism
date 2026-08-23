package dev.turboism.sdk.task;

import java.util.Optional;

/**
 * A point-in-time snapshot of how far a task has got. Not live: a later call to
 * {@link TaskHandle#progress()} returns a different instance.
 *
 * @param runCount number of completed runs; never negative
 * @param lastRunOutcome outcome of the most recent completed run, empty before the first one
 *     completes; its run number may not exceed {@code runCount}
 */
public record TaskProgress(
    long runCount,
    Optional<TaskRunOutcome> lastRunOutcome
) {
    /**
     * Validates the record components.
     *
     * @throws NullPointerException if {@code lastRunOutcome} is {@code null}
     * @throws IllegalArgumentException if {@code runCount} is negative or inconsistent with
     *     {@code lastRunOutcome}
     */
    public TaskProgress {
        if (runCount < 0) {
            throw new IllegalArgumentException("runCount must not be negative");
        }
        lastRunOutcome = TaskContracts.requireOptional(lastRunOutcome, "lastRunOutcome");
        if (lastRunOutcome.isPresent()
            && lastRunOutcome.orElseThrow().runNumber() > runCount) {
            throw new IllegalArgumentException("lastRunOutcome exceeds runCount");
        }
    }
}
