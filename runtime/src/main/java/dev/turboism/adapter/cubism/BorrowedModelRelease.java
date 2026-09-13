package dev.turboism.adapter.cubism;

/**
 * Capability of a model access that may hold a borrowed Editor-owned Core model: releases it
 * once its binding is gone, without disturbing outstanding leases.
 */
public interface BorrowedModelRelease {

    /**
     * Best-effort release of the borrowed Core model when the current binding no longer
     * references it. Non-blocking, idempotent, and never throws; safe to call from
     * project-file lifecycle listeners.
     */
    void releaseUnboundBorrowedModel();
}
