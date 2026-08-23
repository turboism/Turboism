package dev.turboism.core.runtime.sidecar;

/**
 * The supervisor’s current judgement of the sidecar.
 *
 * <p>{@code HEALTHY} after a clean run, {@code RESTARTING} while a crash is being
 * retried within the restart budget, and {@code UNAVAILABLE} once the budget is
 * exhausted. {@code UNAVAILABLE} is terminal: the supervisor refuses all further
 * dispatches.</p>
 */
public enum SidecarHealth {
    HEALTHY,
    RESTARTING,
    UNAVAILABLE
}
