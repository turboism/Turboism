package dev.turboism.sdk.task;

import java.util.Objects;
import java.util.Optional;

/**
 * The immediate answer to a submission: accepted with a live handle, or rejected with a reason.
 *
 * <p>A handle is always present, even on rejection, so callers need not branch before observing
 * the outcome; a rejected submission's handle is already terminal.
 *
 * @param status whether the scheduler took the task
 * @param handle handle for the submitted task; never {@code null}
 * @param rejectionReason why the task was refused; present exactly when {@code status} is
 *     {@link TaskSubmissionStatus#REJECTED}
 */
public record TaskSubmission(
    TaskSubmissionStatus status,
    TaskHandle handle,
    Optional<TaskRejectionReason> rejectionReason
) {
    /**
     * Validates the record components.
     *
     * @throws NullPointerException if any component is {@code null}
     * @throws IllegalArgumentException if the presence of {@code rejectionReason} does not match
     *     {@code status}
     */
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

    /**
     * @return {@code true} when the scheduler took the task, so the handle will eventually reach
     *     a terminal outcome from real execution
     */
    public boolean accepted() {
        return status == TaskSubmissionStatus.ACCEPTED;
    }
}
