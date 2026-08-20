package dev.turboism.sdk.task;

import java.util.Objects;
import java.util.Optional;

/**
 * The frozen, terminal result of a submitted task.
 *
 * <p>The compact constructor enforces the invariants that make the record self-consistent: a
 * failure detail is present exactly when the status is {@code FAILED}, {@code TIMED_OUT} or
 * {@code REJECTED}; a rejected task shows no progress at all; and a succeeded task shows exactly
 * one run, itself successful. A repeating task therefore never reports {@code SUCCEEDED}.
 *
 * @param id identity the task was submitted under
 * @param status how the task ended
 * @param runCount number of runs that completed before the task became terminal; never negative
 * @param lastRunOutcome outcome of the most recent run, empty when no run ever completed; its
 *     run number may not exceed {@code runCount}
 * @param failure failure detail, present exactly for the failing statuses above
 */
public record TaskOutcome(
    TaskId id,
    TaskOutcomeStatus status,
    long runCount,
    Optional<TaskRunOutcome> lastRunOutcome,
    Optional<TaskFailure> failure
) {
    /**
     * Validates the record components.
     *
     * @throws NullPointerException if {@code id}, {@code status} or either {@link java.util.Optional}
     *     is {@code null}
     * @throws IllegalArgumentException if any of the invariants above is violated
     */
    public TaskOutcome {
        id = Objects.requireNonNull(id, "id");
        status = Objects.requireNonNull(status, "status");
        if (runCount < 0) {
            throw new IllegalArgumentException("runCount must not be negative");
        }
        lastRunOutcome = TaskContracts.requireOptional(lastRunOutcome, "lastRunOutcome");
        failure = TaskContracts.requireOptional(failure, "failure");
        if (lastRunOutcome.isPresent()
            && lastRunOutcome.orElseThrow().runNumber() > runCount) {
            throw new IllegalArgumentException("lastRunOutcome exceeds runCount");
        }
        TaskContracts.validateFailure(status, failure);
        if (status == TaskOutcomeStatus.REJECTED
            && (runCount != 0 || lastRunOutcome.isPresent())) {
            throw new IllegalArgumentException("rejected tasks must have zero progress");
        }
        if (status == TaskOutcomeStatus.SUCCEEDED
            && (runCount != 1
                || lastRunOutcome.isEmpty()
                || lastRunOutcome.orElseThrow().status() != TaskRunOutcomeStatus.SUCCEEDED)) {
            throw new IllegalArgumentException("successful task outcome must freeze one successful run");
        }
    }
}
