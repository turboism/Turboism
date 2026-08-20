package dev.turboism.sdk.task;

/**
 * Whether the scheduler took a submitted task.
 */
public enum TaskSubmissionStatus {
    /** The task was queued and will run. */
    ACCEPTED,
    /** The task will never run; a {@link TaskRejectionReason} explains why. */
    REJECTED
}
