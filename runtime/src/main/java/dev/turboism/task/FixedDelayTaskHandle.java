package dev.turboism.task;

import dev.turboism.core.runtime.CallbackExecutionResult;
import dev.turboism.core.runtime.CallbackSubmission;
import dev.turboism.core.runtime.PluginTask;
import dev.turboism.core.runtime.RuntimeCancellationToken;
import dev.turboism.core.runtime.RuntimeScheduler;
import dev.turboism.core.runtime.RuntimeTimerHandle;
import dev.turboism.core.runtime.RuntimeTimerSubmission;
import dev.turboism.sdk.plugin.TaskCanceledException;
import dev.turboism.sdk.task.PluginTaskAction;
import dev.turboism.sdk.task.TaskFailure;
import dev.turboism.sdk.task.TaskId;
import dev.turboism.sdk.task.TaskOutcome;
import dev.turboism.sdk.task.TaskOutcomeStatus;
import dev.turboism.sdk.task.TaskProgress;
import dev.turboism.sdk.task.TaskRunOutcome;
import dev.turboism.sdk.task.TaskRunOutcomeStatus;

import java.time.Duration;
import java.util.Optional;

final class FixedDelayTaskHandle extends AbstractRuntimeTaskHandle {

    private final Object lock = new Object();
    private final RuntimeScheduler runtimeScheduler;
    private final PluginTask runtimeTask;
    private final Duration delay;
    private PluginTaskAction action;
    private RuntimeTimerHandle timerHandle;
    private RuntimeCancellationToken runningToken;
    private long runCount;
    private Optional<TaskRunOutcome> lastRunOutcome = Optional.empty();
    private boolean running;
    private boolean cancellationRequested;

    FixedDelayTaskHandle(
        final TaskId id,
        final RuntimeScheduler runtimeScheduler,
        final PluginTask runtimeTask,
        final Duration delay,
        final PluginTaskAction action,
        final Runnable terminalCleanup,
        final java.util.function.Consumer<Runnable> settlementDispatcher,
        final java.util.function.Consumer<Runnable> continuationDispatcher
    ) {
        super(id, terminalCleanup, settlementDispatcher, continuationDispatcher);
        this.runtimeScheduler = java.util.Objects.requireNonNull(runtimeScheduler, "runtimeScheduler");
        this.runtimeTask = java.util.Objects.requireNonNull(runtimeTask, "runtimeTask");
        this.delay = java.util.Objects.requireNonNull(delay, "delay");
        this.action = java.util.Objects.requireNonNull(action, "action");
    }

    boolean start(final Duration initialDelay) {
        return scheduleNext(initialDelay);
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
            cancelRuntimeWork();
            if (running && runCount > 0) {
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
            cancelRuntimeWork();
            action = null;
        }
    }

    private boolean scheduleNext(final Duration requestedDelay) {
        synchronized (lock) {
            if (isTerminal()) {
                return false;
            }
        }
        final RuntimeTimerSubmission submission = runtimeScheduler.schedule(
            requestedDelay,
            this::submitIteration
        );
        if (!submission.accepted()) {
            failWithoutRun("RUNTIME_UNAVAILABLE", "Runtime timer is unavailable.");
            return false;
        }
        synchronized (lock) {
            if (isTerminal()) {
                submission.handle().cancel();
                return true;
            }
            timerHandle = submission.handle();
            return true;
        }
    }

    private void submitIteration() {
        final RuntimeCancellationToken token = new RuntimeCancellationToken();
        synchronized (lock) {
            timerHandle = null;
            if (isTerminal()) {
                return;
            }
            runningToken = token;
        }
        final CallbackSubmission submission = runtimeScheduler.submitLightweight(
            runtimeTask,
            token,
            () -> runIteration(token)
        );
        if (!submission.accepted()) {
            failWithoutRun(
                submission.rejectionStatus().name(),
                "Scheduled plugin iteration was rejected by the runtime."
            );
            return;
        }
        submission.completion().whenComplete((result, failure) -> {
            if (failure != null || result == null) {
                failWithoutRun("TASK_RUNTIME_FAILURE", "Scheduled plugin iteration failed in the runtime.");
            } else {
                observeExecution(result, token);
            }
        });
    }

    private void runIteration(final RuntimeCancellationToken token) {
        final PluginTaskAction currentAction;
        final long runNumber;
        synchronized (lock) {
            if (isTerminal() || token != runningToken) {
                return;
            }
            running = true;
            runCount++;
            runNumber = runCount;
            currentAction = action;
        }
        try {
            currentAction.run(token);
            synchronized (lock) {
                if (isTerminal() || token != runningToken) {
                    return;
                }
                lastRunOutcome = Optional.of(new TaskRunOutcome(
                    runNumber,
                    TaskRunOutcomeStatus.SUCCEEDED,
                    Optional.empty()
                ));
                running = false;
                runningToken = null;
            }
            scheduleNext(delay);
        } catch (TaskCanceledException exception) {
            cancelFromAction(token, runNumber);
        } catch (Throwable throwable) {
            failRunning(
                token,
                runNumber,
                "TASK_FAILED",
                "Plugin scheduled task action failed safely."
            );
            throw new RuntimeException("Plugin scheduled task action failed.", throwable);
        }
    }

    private void observeExecution(
        final CallbackExecutionResult result,
        final RuntimeCancellationToken token
    ) {
        synchronized (lock) {
            if (isTerminal() || token != runningToken) {
                return;
            }
            switch (result.status()) {
                case TIMED_OUT -> {
                    token.cancel();
                    failRunningLocked(
                        runCount,
                        TaskRunOutcomeStatus.TIMED_OUT,
                        TaskOutcomeStatus.TIMED_OUT,
                        "TASK_TIMED_OUT",
                        "Scheduled plugin task exceeded the runtime work budget."
                    );
                }
                case FAILED, REJECTED_BACKPRESSURE, REJECTED_CIRCUIT_OPEN,
                     POLICY_REJECTED, RUNTIME_UNAVAILABLE -> failRunningLocked(
                    runCount,
                    TaskRunOutcomeStatus.FAILED,
                    TaskOutcomeStatus.FAILED,
                    result.failureCode().isBlank() ? "TASK_FAILED" : result.failureCode(),
                    "Scheduled plugin task could not complete in the runtime."
                );
                case SUCCEEDED -> {
                    // The action wrapper records the successful iteration and re-enters delay.
                }
            }
        }
    }

    private void cancelFromAction(
        final RuntimeCancellationToken token,
        final long runNumber
    ) {
        synchronized (lock) {
            if (isTerminal() || token != runningToken) {
                return;
            }
            cancellationRequested = true;
            token.cancel();
            lastRunOutcome = Optional.of(new TaskRunOutcome(
                runNumber,
                TaskRunOutcomeStatus.CANCELED,
                Optional.empty()
            ));
            complete(outcome(TaskOutcomeStatus.CANCELED, Optional.empty()));
        }
    }

    private void failRunning(
        final RuntimeCancellationToken token,
        final long runNumber,
        final String code,
        final String message
    ) {
        synchronized (lock) {
            if (isTerminal() || token != runningToken) {
                return;
            }
            failRunningLocked(
                runNumber,
                TaskRunOutcomeStatus.FAILED,
                TaskOutcomeStatus.FAILED,
                code,
                message
            );
        }
    }

    private void failRunningLocked(
        final long runNumber,
        final TaskRunOutcomeStatus runStatus,
        final TaskOutcomeStatus outcomeStatus,
        final String code,
        final String message
    ) {
        final Optional<TaskFailure> failure = Optional.of(failure(code, message));
        lastRunOutcome = Optional.of(new TaskRunOutcome(runNumber, runStatus, failure));
        complete(outcome(outcomeStatus, failure));
    }

    private void failWithoutRun(final String code, final String message) {
        synchronized (lock) {
            if (isTerminal()) {
                return;
            }
            complete(outcome(
                TaskOutcomeStatus.FAILED,
                Optional.of(failure(code, message))
            ));
        }
    }

    private void cancelRuntimeWork() {
        if (timerHandle != null) {
            timerHandle.cancel();
            timerHandle = null;
        }
        if (runningToken != null) {
            runningToken.cancel();
        }
    }

    private TaskRunOutcome run(
        final TaskRunOutcomeStatus status,
        final Optional<TaskFailure> failure
    ) {
        return new TaskRunOutcome(runCount, status, failure);
    }

    private TaskOutcome outcome(
        final TaskOutcomeStatus status,
        final Optional<TaskFailure> failure
    ) {
        return new TaskOutcome(id(), status, runCount, lastRunOutcome, failure);
    }
}
