package dev.turboism.sdk.ui;

import dev.turboism.sdk.Incubating;
import dev.turboism.sdk.plugin.Registration;
import java.time.Duration;

/**
 * Scheduler for UI-thread work.
 */
public interface UiScheduler {

    /**
     * Schedules {@code work} on the UI thread. Closing the returned {@link Registration}
     * cancels the pending work when it has not run yet.
     */
    Registration runOnUiThread(Runnable work);

    /**
     * Schedules {@code work} on the UI thread after {@code delay}. Closing the returned
     * {@link Registration} cancels the pending work when it has not run yet.
     */
    Registration runOnUiThreadLater(Runnable work, Duration delay);

    /**
     * Runs idempotent UI cleanup on the UI thread and waits for it, bounded by {@code timeout}.
     *
     * <p>Intended for {@code disable()}, {@code shutdown()} and scope closers that must release
     * windows and other UI resources before the plugin is unloaded. The call stays usable after
     * the plugin generation has been fenced for unload, and it never deadlocks: on the UI thread
     * the cleanup runs inline.</p>
     *
     * <p>{@code timeout} bounds how long the UI thread may take to <em>start</em> the cleanup.
     * When it does not start in time, or the calling thread is interrupted while waiting, the
     * caller stops waiting and gets {@code false}; the cleanup stays queued and still runs
     * exactly once when the UI thread drains, so it must be safe to run late. An interrupt is
     * never swallowed: the thread's interrupt status is restored. A failure thrown by
     * {@code cleanup} while the caller is still waiting propagates unchanged.</p>
     *
     * <p>The default implementation only schedules {@code cleanup} through
     * {@link #runOnUiThread(Runnable)} and reports {@code false}.</p>
     *
     * @param cleanup idempotent UI cleanup; must not be null
     * @param timeout how long to wait for the UI thread to start the cleanup; must be positive
     * @return {@code true} when the cleanup finished before this method returned, {@code false}
     *     when it was deferred
     * @throws IllegalStateException when this scheduler has already been closed
     */
    @Incubating
    default boolean runUiTeardown(Runnable cleanup, Duration timeout) {
        runOnUiThread(cleanup);
        return false;
    }
}
