package dev.turboism.sdk.task;

import java.util.Optional;

public record TaskProgress(
    long runCount,
    Optional<TaskRunOutcome> lastRunOutcome
) {
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
