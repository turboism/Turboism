package dev.turboism.sdk.task;

import java.util.Objects;
import java.util.Optional;

public record TaskOutcome(
    TaskId id,
    TaskOutcomeStatus status,
    long runCount,
    Optional<TaskRunOutcome> lastRunOutcome,
    Optional<TaskFailure> failure
) {
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
