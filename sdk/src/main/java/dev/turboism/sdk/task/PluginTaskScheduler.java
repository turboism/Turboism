package dev.turboism.sdk.task;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

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

    /**
     * Reports whether a live runtime surface backs this instance.
     *
     * @return {@code false} only for the {@link #unavailable()} sentinel
     */
    default boolean isAvailable() {
        return true;
    }

    /**
     * Returns this service's fail-closed {@code Unavailable} sentinel.
     *
     * @return the shared singleton; {@link #isAvailable()} is {@code false} only for it
     */
    static PluginTaskScheduler unavailable() {
        return Unavailable.INSTANCE;
    }

    /** Sentinel returned by {@link #unavailable()}: calls that report outcomes complete with the structured unavailability result; and queries report empty results. */
    enum Unavailable implements PluginTaskScheduler {
        INSTANCE;

        @Override public boolean isAvailable() {
            return false;
        }

        @Override public TaskSubmission submit(final PluginTaskRequest request) {
            java.util.Objects.requireNonNull(request, "request");
            return rejected(request.id());
        }

        @Override public TaskSubmission scheduleWithFixedDelay(final FixedDelayTaskRequest request) {
            java.util.Objects.requireNonNull(request, "request");
            return rejected(request.id());
        }

        private static TaskSubmission rejected(final TaskId id) {
            return new TaskSubmission(
                TaskSubmissionStatus.REJECTED,
                new RejectedHandle(id),
                Optional.of(TaskRejectionReason.RUNTIME_UNAVAILABLE)
            );
        }

        private static final class RejectedHandle implements TaskHandle {

            private final TaskId id;
            private final TaskProgress progress = new TaskProgress(0, Optional.empty());
            private final CompletionStage<TaskOutcome> completion;

            private RejectedHandle(final TaskId id) {
                this.id = id;
                this.completion = CompletableFuture.completedFuture(new TaskOutcome(
                    id,
                    TaskOutcomeStatus.REJECTED,
                    0,
                    Optional.empty(),
                    Optional.of(new TaskFailure(
                        "TASK_REJECTED_RUNTIME_UNAVAILABLE",
                        "Task scheduler is not available."
                    ))
                ));
            }

            @Override public TaskId id() {
                return id;
            }

            @Override public TaskProgress progress() {
                return progress;
            }

            @Override public boolean cancel() {
                return false;
            }

            @Override public CompletionStage<TaskOutcome> completion() {
                return completion;
            }

            @Override public void close() {
            }
        }
    }
}
