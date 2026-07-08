package dev.turboism.sdk.plugin;

/**
 * Public classification of how much host-side execution budget a plugin task may consume.
 *
 * <p>The runtime owns the policy that maps a task to a budget; plugins see only the
 * classification result. A {@link #REJECTED} budget means the runtime refused to
 * schedule the task, usually because of backpressure, a missing permission, or a
 * circuit breaker that is open.
 */
public enum WorkBudget {
    LIGHTWEIGHT,
    HEAVY,
    SIDECAR,
    REJECTED
}
