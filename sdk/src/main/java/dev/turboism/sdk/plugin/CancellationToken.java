package dev.turboism.sdk.plugin;

/**
 * Cooperative cancellation signal handed to plugin background work.
 *
 * <p>Cancellation is advisory: nothing interrupts the running thread, so a task
 * only stops when it polls {@link #isCancellationRequested()} or calls
 * {@link #checkCanceled()}. The signal is one-way — once requested it never
 * clears.</p>
 */
public interface CancellationToken {

    /**
     * @return {@code true} once cancellation has been requested for this task;
     *     never returns to {@code false} afterwards
     */
    boolean isCancellationRequested();

    /**
     * Aborts the calling task at this point if cancellation has been requested.
     *
     * @throws TaskCanceledException when cancellation has been requested; returns
     *     normally otherwise
     */
    void checkCanceled() throws TaskCanceledException;
}
