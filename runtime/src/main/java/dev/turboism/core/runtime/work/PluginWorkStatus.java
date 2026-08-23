package dev.turboism.core.runtime.work;

/**
 * How a unit of plugin work ended, or why it was never admitted.
 *
 * <p>{@code REJECTED_BACKPRESSURE}, {@code REJECTED_CIRCUIT_OPEN}, {@code POLICY_REJECTED} and
 * {@code RUNTIME_UNAVAILABLE} are admission refusals: the work never ran. {@code FAILED} and
 * {@code TIMED_OUT} mean it ran and did not finish cleanly.
 */
public enum PluginWorkStatus {
    SUCCEEDED,
    FAILED,
    TIMED_OUT,
    REJECTED_BACKPRESSURE,
    REJECTED_CIRCUIT_OPEN,
    POLICY_REJECTED,
    RUNTIME_UNAVAILABLE
}
