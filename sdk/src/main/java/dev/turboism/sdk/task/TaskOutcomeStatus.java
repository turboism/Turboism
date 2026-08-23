package dev.turboism.sdk.task;

/**
 * How a task ended, overall. Distinct from {@link TaskRunOutcomeStatus}, which describes a single
 * run of a possibly repeating task.
 */
public enum TaskOutcomeStatus {
    /** Ran once and completed normally; only a one-shot task can reach this. */
    SUCCEEDED,
    /** Ended because the action threw; a {@link TaskFailure} is attached. */
    FAILED,
    /** Ended because a run exceeded its allowed time; a {@link TaskFailure} is attached. */
    TIMED_OUT,
    /** Ended because cancellation was requested and observed. */
    CANCELED,
    /** Never ran: the scheduler refused the submission. Carries a {@link TaskFailure} and no progress. */
    REJECTED
}
