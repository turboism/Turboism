package dev.turboism.sdk.task;

import java.util.Objects;
import java.util.Optional;

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
