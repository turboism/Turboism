package dev.turboism.plugin.mcp;

import java.util.concurrent.Semaphore;

/**
 * Bounded admission for transport threads that wait on host execution.
 *
 * <p>A committed host write cannot be cancelled: once the host thread starts the operation
 * the serving thread keeps waiting for the definitive result. The lane therefore caps how
 * many transport threads may block on host work at once and leaves the remaining HTTP
 * threads for lifecycle, discovery, and session requests.</p>
 *
 * <p>Admission is non-blocking. A request beyond the bound is rejected explicitly instead
 * of queueing without a limit behind a stalled host.</p>
 */
final class McpHostWorkLane {

    private final int slots;
    private final Semaphore permits;

    McpHostWorkLane(final int slots) {
        if (slots < 1) {
            throw new IllegalArgumentException("slots must be positive");
        }
        this.slots = slots;
        this.permits = new Semaphore(slots);
    }

    /** @return {@code true} when the caller may wait on host execution; it must then {@link #exit()} */
    boolean tryEnter() {
        return permits.tryAcquire();
    }

    void exit() {
        permits.release();
    }

    int bound() {
        return slots;
    }

    int inFlight() {
        return slots - permits.availablePermits();
    }
}
