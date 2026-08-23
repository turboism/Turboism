package dev.turboism.sdk.hostread;

/**
 * Lifecycle state of an accepted asynchronous host read.
 *
 * <p>{@code QUEUED} and {@code RUNNING} are transient and only ever observed through
 * {@link AsyncHostReadHandle#status()}; the remaining three are terminal and are the only values
 * an {@link AsyncHostReadResult} may carry.
 */
public enum AsyncHostReadStatus {
    QUEUED,
    RUNNING,
    SUCCEEDED,
    FAILED,
    CANCELED
}
