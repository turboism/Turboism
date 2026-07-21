package dev.turboism.core.runtime.work;

public enum PluginWorkStatus {
    SUCCEEDED,
    FAILED,
    TIMED_OUT,
    REJECTED_BACKPRESSURE,
    REJECTED_CIRCUIT_OPEN,
    POLICY_REJECTED,
    RUNTIME_UNAVAILABLE
}
