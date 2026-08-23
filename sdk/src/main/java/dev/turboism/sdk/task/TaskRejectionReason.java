package dev.turboism.sdk.task;

/**
 * Why the scheduler refused a submission. Present exactly on a
 * {@link TaskSubmissionStatus#REJECTED} submission.
 */
public enum TaskRejectionReason {
    /** A task with the same {@link TaskId} is already active. */
    DUPLICATE_ACTIVE_ID,
    /** The submitting plugin is not currently enabled. */
    PLUGIN_INACTIVE,
    /** The plugin's queue or concurrency allowance is already saturated. */
    BACKPRESSURE,
    /** Submissions from this plugin are suspended after repeated failures. */
    CIRCUIT_OPEN,
    /** The runtime scheduler is shutting down or already closed. */
    RUNTIME_UNAVAILABLE,
    /** A host policy disallowed the request independently of load. */
    POLICY_REJECTED
}
