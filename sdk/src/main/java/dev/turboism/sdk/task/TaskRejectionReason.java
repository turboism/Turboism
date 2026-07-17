package dev.turboism.sdk.task;

public enum TaskRejectionReason {
    DUPLICATE_ACTIVE_ID,
    PLUGIN_INACTIVE,
    BACKPRESSURE,
    CIRCUIT_OPEN,
    RUNTIME_UNAVAILABLE,
    POLICY_REJECTED
}
