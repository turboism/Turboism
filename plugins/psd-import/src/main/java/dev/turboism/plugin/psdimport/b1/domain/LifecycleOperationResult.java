package dev.turboism.plugin.psdimport.b1.domain;

/**
 * Outcome of a {@link PsdActionLifecycle} state transition, reported instead of thrown so callers
 * can distinguish a real transition from a no-op.
 *
 * <p>{@code CHANGED} means the lifecycle state actually moved; {@code UNCHANGED} means the request
 * was already satisfied; {@code SHUTDOWN_REJECTED} means the request was refused because the
 * lifecycle has already shut down and never re-opens.
 */
public enum LifecycleOperationResult {
    CHANGED,
    UNCHANGED,
    SHUTDOWN_REJECTED
}
