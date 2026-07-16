package dev.turboism.task;

import dev.turboism.sdk.task.TaskFailure;
import dev.turboism.sdk.task.TaskHandle;
import dev.turboism.sdk.task.TaskId;
import dev.turboism.sdk.task.TaskOutcome;
import dev.turboism.sdk.task.TaskOutcomeStatus;
import dev.turboism.sdk.task.TaskProgress;
import dev.turboism.sdk.task.TaskRejectionReason;

import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

final class RejectedTaskHandle implements TaskHandle {

    private final TaskId id;
    private final TaskProgress progress = new TaskProgress(0, Optional.empty());
    private final CompletionStage<TaskOutcome> completion;

    RejectedTaskHandle(
        final TaskId id,
        final TaskRejectionReason reason,
        final Consumer<Runnable> completionDispatcher
    ) {
        this.id = java.util.Objects.requireNonNull(id, "id");
        final TaskFailure failure = new TaskFailure(
            "TASK_REJECTED_" + reason.name(),
            "Plugin task submission was rejected safely."
        );
        final PluginCompletionFuture<TaskOutcome> controlled = new PluginCompletionFuture<>(
            java.util.Objects.requireNonNull(completionDispatcher, "completionDispatcher")
        );
        controlled.settle(new TaskOutcome(
            id,
            TaskOutcomeStatus.REJECTED,
            0,
            Optional.empty(),
            Optional.of(failure)
        ));
        this.completion = controlled.stage();
    }

    @Override
    public TaskId id() {
        return id;
    }

    @Override
    public TaskProgress progress() {
        return progress;
    }

    @Override
    public boolean cancel() {
        return false;
    }

    @Override
    public CompletionStage<TaskOutcome> completion() {
        return completion;
    }

    @Override
    public void close() {
    }
}
