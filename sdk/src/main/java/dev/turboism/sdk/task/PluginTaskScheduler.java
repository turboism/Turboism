package dev.turboism.sdk.task;

/**
 * The task-submission surface a plugin sees.
 *
 * <p>Submission never blocks on the work itself: both methods return immediately with a
 * {@link TaskSubmission} that either carries a live handle or reports why the task was refused.
 * Work runs on scheduler-owned threads, not the Cubism host thread.
 */
public interface PluginTaskScheduler {

    /**
     * Submits a one-shot task.
     *
     * @param request the work and its identity
     * @return an accepted submission carrying a handle, or a rejected one carrying a
     *     {@link TaskRejectionReason}; never {@code null}
     */
    TaskSubmission submit(PluginTaskRequest request);

    /**
     * Submits a task that repeats with a fixed gap between runs.
     *
     * <p>Repetition continues until the returned handle is cancelled or closed, or the plugin is
     * deactivated.
     *
     * @param request the work, its identity and its timing
     * @return an accepted submission carrying a handle, or a rejected one carrying a
     *     {@link TaskRejectionReason}; never {@code null}
     */
    TaskSubmission scheduleWithFixedDelay(FixedDelayTaskRequest request);
}
