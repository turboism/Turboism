package dev.turboism.sdk.task;

/**
 * Workload class of a plugin task, used by the scheduler to choose an execution lane.
 */
public enum PluginTaskKind {
    /** CPU-bound work that should run once and finish. */
    COMPUTE,
    /** Periodic housekeeping or refresh work that tolerates being run rarely and late. */
    LOW_FREQUENCY_REFRESH,
    /**
     * Long-running work dispatched to the plugin's dedicated long-task lane, which applies no
     * wall-clock time limit and never interrupts the worker thread. Cancellation is
     * cooperative: the runtime only sets the cancellation token, so the action must observe
     * {@link dev.turboism.sdk.plugin.CancellationToken#isCancellationRequested()} (or call
     * {@code checkCanceled()}) itself to stop.
     */
    LONG_RUNNING
}
