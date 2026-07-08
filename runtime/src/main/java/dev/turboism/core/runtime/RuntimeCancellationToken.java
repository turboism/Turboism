package dev.turboism.core.runtime;

import dev.turboism.sdk.plugin.CancellationToken;
import dev.turboism.sdk.plugin.TaskCanceledException;

/**
 * Runtime-side cooperative cancellation token.
 *
 * <p>Thread-safe: cancellation may be requested from any thread, and the
 * cancellation check may be performed from plugin callback threads.
 */
public final class RuntimeCancellationToken implements CancellationToken {

    private volatile boolean cancelled;

    @Override
    public boolean isCancellationRequested() {
        return cancelled;
    }

    @Override
    public void checkCanceled() throws TaskCanceledException {
        if (cancelled) {
            throw new TaskCanceledException("Operation was cancelled.");
        }
    }

    /**
     * Requests cancellation. Idempotent and safe to call from any thread.
     */
    public void cancel() {
        cancelled = true;
    }
}
