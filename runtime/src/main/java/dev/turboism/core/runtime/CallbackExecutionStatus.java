package dev.turboism.core.runtime;

public enum CallbackExecutionStatus {
    SUCCEEDED,
    FAILED,
    TIMED_OUT,
    REJECTED_BACKPRESSURE,
    REJECTED_CIRCUIT_OPEN,
    POLICY_REJECTED,
    RUNTIME_UNAVAILABLE
}
