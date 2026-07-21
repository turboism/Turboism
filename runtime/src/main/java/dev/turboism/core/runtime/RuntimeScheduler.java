package dev.turboism.core.runtime;

import dev.turboism.core.diagnostics.PluginWorkBudgetEvent;
import dev.turboism.core.runtime.work.PluginWorkExecutorRegistry;
import dev.turboism.core.runtime.work.PluginWorkResult;
import dev.turboism.core.runtime.work.PluginWorkStatus;
import dev.turboism.core.runtime.work.PluginWorkSubmission;
import dev.turboism.core.runtime.sidecar.SidecarDispatcher;
import dev.turboism.core.runtime.sidecar.SidecarResult;
import dev.turboism.sdk.plugin.WorkBudget;

import java.time.Duration;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

public final class RuntimeScheduler {

    private static final String SIDECAR_COMPLETION_TASK_TYPE = "sidecar.complete";
    private static final int GLOBAL_TIMER_LIMIT = 1024;

    private final WorkBudgetPolicy policy;
    private final PluginWorkExecutorRegistry executorRegistry;
    private final SidecarDispatcher sidecarDispatcher;
    private final Consumer<PluginWorkBudgetEvent> diagnosticSink;
    private final ScheduledThreadPoolExecutor timer;
    private final Semaphore timerPermits = new Semaphore(GLOBAL_TIMER_LIMIT);
    private final Set<RuntimeTimerToken> activeTimers = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final Object lifecycleLock = new Object();
    private int pluginTaskSchedulerLeases;

    public RuntimeScheduler(
        WorkBudgetPolicy policy,
        PluginWorkExecutorRegistry executorRegistry,
        SidecarDispatcher sidecarDispatcher,
        Consumer<PluginWorkBudgetEvent> diagnosticSink
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

    public boolean dispatch(PluginTask task, Runnable callback) {
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(callback, "callback");
        if (closed.get()) {
            emitRejected(task);
            return false;
        }
        WorkBudget budget = policy.classify(task);
        return switch (budget) {
            case LIGHTWEIGHT -> executorRegistry.get(task.pluginId()).submit(
                task,
                bindCancellation(callback)
            ).accepted();
            case HEAVY, SIDECAR -> dispatchSidecar(task, callback);
            case REJECTED -> {
                emitRejected(task);
                yield false;
            }
        };
    }

    public PluginWorkSubmission submitLightweight(
        PluginTask task,
        RuntimeCancellationToken token,
        Runnable callback
    ) {
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(token, "token");
        Objects.requireNonNull(callback, "callback");
        if (closed.get()) {
            return rejected(PluginWorkStatus.RUNTIME_UNAVAILABLE, "RUNTIME_UNAVAILABLE");
        }
        if (policy.classify(task) != WorkBudget.LIGHTWEIGHT) {
            emitRejected(task);
            return rejected(PluginWorkStatus.POLICY_REJECTED, "POLICY_REJECTED");
        }
        return executorRegistry.get(task.pluginId()).submit(
            task,
            bindCancellation(token, callback)
        );
    }

    public PluginWorkSubmission submitCompletion(
        String pluginId,
        Runnable callback
    ) {
        requireText(pluginId, "pluginId");
        Objects.requireNonNull(callback, "callback");
        if (closed.get()) {
            return rejected(PluginWorkStatus.RUNTIME_UNAVAILABLE, "RUNTIME_UNAVAILABLE");
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
        final RuntimeTimerToken token;
        synchronized (lifecycleLock) {
            if (closed.get() || !timerPermits.tryAcquire()) {
                return new RuntimeTimerSubmission(false, () -> false);
            }
            token = new RuntimeTimerToken(released -> {
                activeTimers.remove(released);
                timerPermits.release();
            });
            activeTimers.add(token);
        }
        try {
            token.bind(timer.schedule(
                () -> {
                    try {
                        callback.run();
                    } finally {
                        token.executed();
                    }
                },
                delay.toNanos(),
                TimeUnit.NANOSECONDS
            ));
            return new RuntimeTimerSubmission(true, token);
        } catch (RuntimeException exception) {
            token.rejected();
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

    int activeTimerCount() {
        return activeTimers.size();
    }

    int availableTimerPermits() {
        return timerPermits.availablePermits();
    }

    public void shutdown() {
        final RuntimeTimerToken[] timersToCancel;
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
            timersToCancel = activeTimers.toArray(RuntimeTimerToken[]::new);
        }
        for (RuntimeTimerToken token : timersToCancel) {
            token.cancel();
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

    private boolean dispatchSidecar(PluginTask task, Runnable callback) {
        if (!sidecarDispatcher.isAvailable()) {
            emitRejected(task);
            return false;
        }
        try {
            CompletionStage<SidecarResult> stage = sidecarDispatcher.dispatch(
                task,
                () -> enqueueSidecarCompletion(task, callback)
            );
            if (stage == null) {
                emitRejected(task);
                return false;
            }
            stage.whenComplete((result, failure) -> emitSidecarResult(task, result, failure));
            return true;
        } catch (RuntimeException exception) {
            emit(task, PluginWorkBudgetEvent.Phase.FAILED, PluginWorkBudgetEvent.Decision.SIDECAR, PluginWorkBudgetEvent.Severity.ERROR);
            return false;
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
            emit(task, PluginWorkBudgetEvent.Phase.FAILED, PluginWorkBudgetEvent.Decision.SIDECAR, PluginWorkBudgetEvent.Severity.ERROR);
            return;
        }
        if (result == null || result.kind() == SidecarResult.Kind.SUCCESS) {
            return;
        }
        emit(task, PluginWorkBudgetEvent.Phase.FAILED, PluginWorkBudgetEvent.Decision.SIDECAR, PluginWorkBudgetEvent.Severity.ERROR);
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

    private static PluginWorkSubmission rejected(
        PluginWorkStatus status,
        String failureCode
    ) {
        PluginWorkResult result = new PluginWorkResult(status, failureCode);
        return new PluginWorkSubmission(false, status, CompletableFuture.completedFuture(result));
    }

    private void emitRejected(PluginTask task) {
        emit(task, PluginWorkBudgetEvent.Phase.REJECTED, PluginWorkBudgetEvent.Decision.REJECTED, PluginWorkBudgetEvent.Severity.WARNING);
    }

    private void emit(
        PluginTask task,
        PluginWorkBudgetEvent.Phase phase,
        PluginWorkBudgetEvent.Decision decision,
        PluginWorkBudgetEvent.Severity severity
    ) {
        diagnosticSink.accept(new PluginWorkBudgetEvent(
            task.pluginId(),
            task.taskType(),
            phase,
            decision,
            severity
        ));
    }
}
