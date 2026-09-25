package dev.turboism.core.runtime;

import dev.turboism.sdk.plugin.CancellationToken;
import dev.turboism.sdk.plugin.TaskCanceledException;

/**
 * Runtime-side cooperative cancellation token.
 *
 * <p>Thread-safe: cancellation may be requested from any thread, and the
 * cancellation check may be performed from plugin work threads.
 */
public final class RuntimeCancellationToken implements CancellationToken {

    private final java.util.List<Runnable> cancelHooks = new java.util.ArrayList<>();
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
     * Requests cancellation. Idempotent and safe to call from any thread; registered
     * {@link #onCancel} hooks run once, in registration order.
     */
    public void cancel() {
        final java.util.List<Runnable> hooks;
        synchronized (this) {
            if (cancelled) {
                return;
            }
            cancelled = true;
            hooks = java.util.List.copyOf(cancelHooks);
            cancelHooks.clear();
        }
        for (Runnable hook : hooks) {
            try {
                hook.run();
            } catch (RuntimeException ignored) {
                // One broken hook must not starve the remaining cancellation observers.
            }
        }
    }

    /**
     * Runs {@code hook} on cancellation. If the token is already cancelled the hook runs
     * immediately on the calling thread.
     */
    public void onCancel(final Runnable hook) {
        java.util.Objects.requireNonNull(hook, "hook");
        final boolean runNow;
        synchronized (this) {
            runNow = cancelled;
            if (!runNow) {
                cancelHooks.add(hook);
            }
        }
        if (runNow) {
            hook.run();
        }
    }
}
