package dev.turboism.core.runtime;

import dev.turboism.core.diagnostics.CallbackBudgetEvent;
import dev.turboism.core.runtime.sidecar.SidecarDispatcher;
import dev.turboism.core.runtime.sidecar.SidecarResult;
import dev.turboism.sdk.plugin.WorkBudget;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

public final class RuntimeScheduler {

    private static final String SIDECAR_COMPLETION_TASK_TYPE = "sidecar.complete";

    private final WorkBudgetPolicy policy;
    private final PluginExecutorRegistry executorRegistry;
    private final SidecarDispatcher sidecarDispatcher;
    private final Consumer<CallbackBudgetEvent> diagnosticSink;
    private final ScheduledThreadPoolExecutor timer;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final Object lifecycleLock = new Object();
    private int pluginTaskSchedulerLeases;

    public RuntimeScheduler(
        WorkBudgetPolicy policy,
        PluginExecutorRegistry executorRegistry,
        SidecarDispatcher sidecarDispatcher,
        Consumer<CallbackBudgetEvent> diagnosticSink
    ) {
        this.policy = Objects.requireNonNull(policy, "policy");
        this.executorRegistry = Objects.requireNonNull(executorRegistry, "executorRegistry");
        this.sidecarDispatcher = Objects.requireNonNull(sidecarDispatcher, "sidecarDispatcher");
        this.diagnosticSink = Objects.requireNonNull(diagnosticSink, "diagnosticSink");
        this.timer = new ScheduledThreadPoolExecutor(1, runnable -> {
            Thread thread = new Thread(runnable, "turboism-runtime-scheduler-timer");
            thread.setDaemon(true);
            return thread;
        });
        this.timer.setRemoveOnCancelPolicy(true);
    }

    public void dispatch(PluginTask task, Runnable callback) {
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(callback, "callback");
        if (closed.get()) {
            emitRejected(task);
            return;
        }
        WorkBudget budget = policy.classify(task);
        switch (budget) {
            case LIGHTWEIGHT, HEAVY -> executorRegistry.get(task.pluginId()).execute(task, bindCancellation(callback));
            case SIDECAR -> dispatchSidecar(task, callback);
            case REJECTED -> emitRejected(task);
        }
    }

    public CallbackSubmission submitLightweight(
        PluginTask task,
        RuntimeCancellationToken token,
        Runnable callback
    ) {
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(token, "token");
        Objects.requireNonNull(callback, "callback");
        if (closed.get()) {
            return rejected(CallbackExecutionStatus.RUNTIME_UNAVAILABLE, "RUNTIME_UNAVAILABLE");
        }
        if (policy.classify(task) != WorkBudget.LIGHTWEIGHT) {
            emitRejected(task);
            return rejected(CallbackExecutionStatus.POLICY_REJECTED, "POLICY_REJECTED");
        }
        return executorRegistry.get(task.pluginId()).submit(
            task,
            bindCancellation(token, callback)
        );
    }

    public CallbackSubmission submitCompletion(
        String pluginId,
        Runnable callback
    ) {
        requireText(pluginId, "pluginId");
        Objects.requireNonNull(callback, "callback");
        if (closed.get()) {
            return rejected(CallbackExecutionStatus.RUNTIME_UNAVAILABLE, "RUNTIME_UNAVAILABLE");
        }
        PluginTask task = new PluginTask(
            "sidecar.complete",
            pluginId,
            "plugin completion settlement",
            "none"
        );
        return executorRegistry.submitCompletion(
            pluginId,
            task,
            bindCancellation(callback)
        );
    }

    public RuntimeTimerSubmission schedule(Duration delay, Runnable callback) {
        Objects.requireNonNull(delay, "delay");
        Objects.requireNonNull(callback, "callback");
        if (delay.isNegative()) {
            throw new IllegalArgumentException("delay must not be negative");
        }
        if (closed.get()) {
            return new RuntimeTimerSubmission(false, () -> false);
        }
        try {
            final var future = timer.schedule(
                callback,
                delay.toNanos(),
                TimeUnit.NANOSECONDS
            );
            return new RuntimeTimerSubmission(true, () -> future.cancel(false));
        } catch (RuntimeException exception) {
            return new RuntimeTimerSubmission(false, () -> false);
        }
    }

    public RuntimeSchedulerLease acquirePluginTaskSchedulerLease() {
        synchronized (lifecycleLock) {
            if (closed.get()) {
                throw new IllegalStateException("Runtime scheduler is already closed");
            }
            pluginTaskSchedulerLeases++;
            return new RuntimeSchedulerLease(this::releasePluginTaskSchedulerLease);
        }
    }

    public boolean isClosed() {
        return closed.get();
    }

    public void shutdown() {
        synchronized (lifecycleLock) {
            if (closed.get()) {
                return;
            }
            if (pluginTaskSchedulerLeases != 0) {
                throw new IllegalStateException(
                    "Runtime scheduler cannot close while plugin task schedulers are active"
                );
            }
            closed.set(true);
        }
        timer.shutdownNow();
        executorRegistry.shutdownAll();
    }

    private void releasePluginTaskSchedulerLease() {
        synchronized (lifecycleLock) {
            if (pluginTaskSchedulerLeases <= 0) {
                throw new IllegalStateException("Runtime scheduler lease accounting underflow");
            }
            pluginTaskSchedulerLeases--;
        }
    }

    private void dispatchSidecar(PluginTask task, Runnable callback) {
        try {
            CompletionStage<SidecarResult> stage = sidecarDispatcher.dispatch(
                task,
                () -> enqueueSidecarCompletion(task, callback)
            );
            stage.whenComplete((result, failure) -> emitSidecarResult(task, result, failure));
        } catch (RuntimeException exception) {
            emit(task, CallbackBudgetEvent.Phase.FAILED, CallbackBudgetEvent.Decision.SIDECAR, CallbackBudgetEvent.Severity.ERROR);
        }
    }

    private void enqueueSidecarCompletion(PluginTask originalTask, Runnable callback) {
        PluginTask completionTask = new PluginTask(
            SIDECAR_COMPLETION_TASK_TYPE,
            originalTask.pluginId(),
            originalTask.payloadDescription(),
            originalTask.declaredCapability()
        );
        executorRegistry.get(originalTask.pluginId()).execute(completionTask, bindCancellation(callback));
    }

    private void emitSidecarResult(PluginTask task, SidecarResult result, Throwable failure) {
        if (failure != null) {
            emit(task, CallbackBudgetEvent.Phase.FAILED, CallbackBudgetEvent.Decision.SIDECAR, CallbackBudgetEvent.Severity.ERROR);
            return;
        }
        if (result == null || result.kind() == SidecarResult.Kind.SUCCESS) {
            return;
        }
        emit(task, CallbackBudgetEvent.Phase.FAILED, CallbackBudgetEvent.Decision.SIDECAR, CallbackBudgetEvent.Severity.ERROR);
    }

    private Runnable bindCancellation(Runnable callback) {
        return bindCancellation(new RuntimeCancellationToken(), callback);
    }

    private Runnable bindCancellation(
        RuntimeCancellationToken token,
        Runnable callback
    ) {
        return () -> {
            CancellationContext.set(token);
            try {
                callback.run();
            } finally {
                CancellationContext.clear();
            }
        };
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private static CallbackSubmission rejected(
        CallbackExecutionStatus status,
        String failureCode
    ) {
        CallbackExecutionResult result = new CallbackExecutionResult(status, failureCode);
        return new CallbackSubmission(false, status, CompletableFuture.completedFuture(result));
    }

    private void emitRejected(PluginTask task) {
        emit(task, CallbackBudgetEvent.Phase.REJECTED, CallbackBudgetEvent.Decision.REJECTED, CallbackBudgetEvent.Severity.WARNING);
    }

    private void emit(
        PluginTask task,
        CallbackBudgetEvent.Phase phase,
        CallbackBudgetEvent.Decision decision,
        CallbackBudgetEvent.Severity severity
    ) {
        diagnosticSink.accept(new CallbackBudgetEvent(
            task.pluginId(),
            task.taskType(),
            phase,
            decision,
            severity
        ));
    }
}
