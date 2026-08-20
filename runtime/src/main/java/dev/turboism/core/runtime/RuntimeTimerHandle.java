package dev.turboism.core.runtime;

/**
 * A cancellable reference to a delayed callback registered with {@link RuntimeScheduler}.
 *
 * <p>Closing is cancelling: the default {@link #close()} simply delegates, so a handle used in
 * try-with-resources releases the scheduler's timer permit on exit.
 */
public interface RuntimeTimerHandle extends AutoCloseable {

    /**
     * Cancels the pending callback and releases its timer permit.
     *
     * @return {@code true} if this call prevented the callback from running, {@code false} if it
     *     had already run, was already cancelled, or the submission was never accepted
     */
    boolean cancel();

    @Override
    default void close() {
        cancel();
    }
}
