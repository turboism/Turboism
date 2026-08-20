package dev.turboism.sdk.task;

/**
 * How a single run ended. Has no {@code REJECTED} constant: rejection prevents any run from
 * happening and is reported only on {@link TaskOutcomeStatus}.
 */
public enum TaskRunOutcomeStatus {
    /** The action returned normally. */
    SUCCEEDED,
    /** The action threw; a {@link TaskFailure} is attached. */
    FAILED,
    /** The run exceeded its allowed time; a {@link TaskFailure} is attached. */
    TIMED_OUT,
    /** The run stopped because cancellation was observed. */
    CANCELED
}
