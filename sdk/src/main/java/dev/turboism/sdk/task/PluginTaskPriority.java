package dev.turboism.sdk.task;

/**
 * Relative ordering hint applied among tasks competing for the same lane.
 */
public enum PluginTaskPriority {
    /** Default priority. */
    NORMAL,
    /** Yields to {@link #NORMAL} work; suitable for background refresh. */
    LOW
}
