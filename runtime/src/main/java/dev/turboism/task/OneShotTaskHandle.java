package dev.turboism.task;

import dev.turboism.core.runtime.work.PluginWorkResult;
import dev.turboism.core.runtime.work.PluginWorkStatus;
import dev.turboism.core.runtime.RuntimeCancellationToken;
import dev.turboism.sdk.plugin.TaskCanceledException;
import dev.turboism.sdk.task.PluginTaskAction;
import dev.turboism.sdk.task.TaskId;
import dev.turboism.sdk.task.TaskOutcome;
import dev.turboism.sdk.task.TaskOutcomeStatus;
import dev.turboism.sdk.task.TaskProgress;
import dev.turboism.sdk.task.TaskRunOutcome;
import dev.turboism.sdk.task.TaskRunOutcomeStatus;

import java.util.Optional;

final class OneShotTaskHandle extends AbstractRuntimeTaskHandle {

    private final Object lock = new Object();
    private final RuntimeCancellationToken token = new RuntimeCancellationToken();
    private PluginTaskAction action;
    private long runCount;
    private Optional<TaskRunOutcome> lastRunOutcome = Optional.empty();
    private boolean cancellationRequested;

    OneShotTaskHandle(
        final TaskId id,
        final PluginTaskAction action,
        final Runnable terminalCleanup,
        final java.util.function.Consumer<TaskOutcome> terminalObserver,
        final java.util.function.Consumer<Runnable> settlementDispatcher,
        final java.util.function.Consumer<Runnable> continuationDispatcher
    ) {
        super(id, terminalCleanup, terminalObserver, settlementDispatcher, continuationDispatcher);
        this.action = java.util.Objects.requireNonNull(action, "action");
    }

    RuntimeCancellationToken token() {
        return token;
    }

    void runAction() {
        final PluginTaskAction currentAction;
        synchronized (lock) {
            if (isTerminal()) {
                return;
            }
            runCount = 1;
            currentAction = action;
        }
        try {
            currentAction.run(token);
            synchronized (lock) {
                if (!isTerminal()) {
                    final TaskRunOutcome run = run(TaskRunOutcomeStatus.SUCCEEDED, Optional.empty());
                    lastRunOutcome = Optional.of(run);
                    complete(outcome(TaskOutcomeStatus.SUCCEEDED, Optional.empty()));
                }
            }
        } catch (TaskCanceledException exception) {
            cancelFromAction();
        } catch (Throwable throwable) {
            synchronized (lock) {
                if (!isTerminal()
                    && !(throwable instanceof InterruptedException && token.isCancellationRequested())) {
                    final var failure = Optional.of(failure(
                        "TASK_FAILED",
                        "Plugin task action failed safely."
                    ));
                    lastRunOutcome = Optional.of(run(TaskRunOutcomeStatus.FAILED, failure));
                    complete(outcome(TaskOutcomeStatus.FAILED, failure));
                }
            }
            throw new RuntimeException("Plugin task action failed.", throwable);
        }
    }

    void observeExecution(final PluginWorkResult result) {
        synchronized (lock) {
            if (isTerminal()) {
                return;
            }
            switch (result.status()) {
                case TIMED_OUT -> {
                    token.cancel();
                    final var failure = Optional.of(failure(
                        "TASK_TIMED_OUT",
                        "Plugin task exceeded the runtime work budget."
                    ));
                    if (runCount > 0) {
                        lastRunOutcome = Optional.of(run(TaskRunOutcomeStatus.TIMED_OUT, failure));
                    }
                    complete(outcome(TaskOutcomeStatus.TIMED_OUT, failure));
                }
                case FAILED, REJECTED_BACKPRESSURE, REJECTED_CIRCUIT_OPEN,
                     POLICY_REJECTED, RUNTIME_UNAVAILABLE -> {
                    final var failure = Optional.of(failure(
                        result.failureCode().isBlank() ? "TASK_FAILED" : result.failureCode(),
                        "Plugin task could not complete in the runtime scheduler."
                    ));
                    if (runCount > 0) {
                        lastRunOutcome = Optional.of(run(TaskRunOutcomeStatus.FAILED, failure));
                    }
                    complete(outcome(TaskOutcomeStatus.FAILED, failure));
                }
                case SUCCEEDED -> {
                    // The action wrapper owns the successful terminal transition.
                }
            }
        }
    }

    @Override
    public TaskProgress progress() {
        synchronized (lock) {
            return new TaskProgress(runCount, lastRunOutcome);
        }
    }

    @Override
    public boolean cancel() {
        synchronized (lock) {
            if (cancellationRequested || isTerminal()) {
                return false;
            }
            cancellationRequested = true;
            token.cancel();
            if (runCount > 0) {
                lastRunOutcome = Optional.of(run(TaskRunOutcomeStatus.CANCELED, Optional.empty()));
            }
            return complete(outcome(
                TaskOutcomeStatus.CANCELED,
                Optional.empty()
            ));
        }
    }

    @Override
    public void close() {
        cancel();
    }

    @Override
    void onTerminal() {
        synchronized (lock) {
            action = null;
        }
    }

    private void cancelFromAction() {
        synchronized (lock) {
            if (isTerminal()) {
                return;
            }
            cancellationRequested = true;
            token.cancel();
            if (runCount > 0) {
                lastRunOutcome = Optional.of(run(TaskRunOutcomeStatus.CANCELED, Optional.empty()));
            }
            complete(outcome(TaskOutcomeStatus.CANCELED, Optional.empty()));
        }
    }

    private TaskRunOutcome run(
        final TaskRunOutcomeStatus status,
        final Optional<dev.turboism.sdk.task.TaskFailure> failure
    ) {
        return new TaskRunOutcome(runCount, status, failure);
    }

    private TaskOutcome outcome(
        final TaskOutcomeStatus status,
        final Optional<dev.turboism.sdk.task.TaskFailure> failure
    ) {
        return new TaskOutcome(id(), status, runCount, lastRunOutcome, failure);
    }
}
