package dev.turboism.plugin.contextmenu.b1.domain;

/**
 * What a lifecycle transition request actually did.
 *
 * <p>{@link #UNCHANGED} means the requested state was already in effect, and
 * {@link #SHUTDOWN_REJECTED} means the request was refused because shutdown is terminal. Neither is
 * an error condition the caller must handle as a failure.
 */
public enum LifecycleOperationResult {
    CHANGED,
    UNCHANGED,
    SHUTDOWN_REJECTED
}
