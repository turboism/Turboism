package dev.turboism.sdk.task;

import java.util.concurrent.CompletionStage;

/**
 * A live reference to a submitted task, returned inside every {@link TaskSubmission} — including
 * a rejected one, where the handle is already terminal.
 *
 * <p>Closing the handle releases the caller's interest in the task; for a repeating task that
 * also stops further runs.
 */
public interface TaskHandle extends AutoCloseable {

    /**
     * @return the identity the task was submitted under
     */
    TaskId id();

    /**
     * @return a snapshot of how many runs have completed and how the last one ended; taken at
     *     call time and not updated afterwards
     */
    TaskProgress progress();

    /**
     * Requests cancellation. The running action is not interrupted; it stops only when it
     * observes its {@link dev.turboism.sdk.plugin.CancellationToken}.
     *
     * @return {@code true} if this call moved the task towards cancellation, {@code false} if it
     *     had already reached a terminal state
     */
    boolean cancel();

    /**
     * @return a stage that completes when the task reaches a terminal state, carrying the final
     *     {@link TaskOutcome}; a failed task completes the stage normally with a failed outcome
     *     rather than completing it exceptionally
     */
    CompletionStage<TaskOutcome> completion();

    @Override
    void close();
}
