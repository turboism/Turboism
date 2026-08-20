package dev.turboism.sdk.task;

/**
 * Workload class of a plugin task, used by the scheduler to choose an execution lane.
 */
public enum PluginTaskKind {
    /** CPU-bound work that should run once and finish. */
    COMPUTE,
    /** Periodic housekeeping or refresh work that tolerates being run rarely and late. */
    LOW_FREQUENCY_REFRESH
}
