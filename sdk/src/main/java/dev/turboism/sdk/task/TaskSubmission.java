package dev.turboism.sdk.task;

import java.util.Objects;
import java.util.Optional;

public record TaskSubmission(
    TaskSubmissionStatus status,
    TaskHandle handle,
    Optional<TaskRejectionReason> rejectionReason
) {
    public TaskSubmission {
        status = Objects.requireNonNull(status, "status");
        handle = Objects.requireNonNull(handle, "handle");
        rejectionReason = TaskContracts.requireOptional(rejectionReason, "rejectionReason");
        final boolean rejected = status == TaskSubmissionStatus.REJECTED;
        if (rejectionReason.isPresent() != rejected) {
            throw new IllegalArgumentException(
                "rejectionReason presence does not match submission status " + status
            );
        }
    }

    public boolean accepted() {
        return status == TaskSubmissionStatus.ACCEPTED;
    }
}
