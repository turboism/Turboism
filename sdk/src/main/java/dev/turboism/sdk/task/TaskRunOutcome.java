package dev.turboism.sdk.task;

import java.util.Objects;
import java.util.Optional;

/**
 * How one individual run of a task ended. A repeating task produces one of these per repetition.
 *
 * @param runNumber one-based index of this run within the task
 * @param status how this run ended
 * @param failure failure detail, present exactly when {@code status} is {@code FAILED} or
 *     {@code TIMED_OUT}
 * @throws NullPointerException if {@code status} or {@code failure} is {@code null}
 * @throws IllegalArgumentException if {@code runNumber} is below one, or the presence of
 *     {@code failure} does not match {@code status}
 */
public record TaskRunOutcome(
    long runNumber,
    TaskRunOutcomeStatus status,
    Optional<TaskFailure> failure
) {
    public TaskRunOutcome {
        if (runNumber < 1) {
            throw new IllegalArgumentException("runNumber must be one-based");
        }
        status = Objects.requireNonNull(status, "status");
        failure = TaskContracts.requireOptional(failure, "failure");
        TaskContracts.validateFailure(status, failure);
    }
}
