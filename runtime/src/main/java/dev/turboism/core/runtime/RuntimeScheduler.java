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

/**
 * The runtime's single dispatch point for plugin work and delayed callbacks.
 *
 * <p>Every submission is first classified by the {@link WorkBudgetPolicy}: lightweight work goes
 * to the plugin's own executor, heavy or sidecar work is handed to the {@link SidecarDispatcher},
 * and rejected work is dropped after a diagnostic event. Rejection is reported as a return value,
 * never as an exception, so one misbehaving plugin cannot abort the caller.
 *
 * <p>Delayed callbacks share a single daemon timer thread and a global budget of 1024 concurrent
 * timers; a request beyond that budget is refused rather than queued.
 *
 * <p>Safe for concurrent use. Shutdown is guarded by outstanding plugin task scheduler leases: a
 * scheduler with live leases refuses to close.
 */
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

    /**
     * Classifies and dispatches a task, running {@code callback} on whichever lane the budget
     * policy selects, with a fresh cancellation token bound for its duration.
     *
     * @param task the work to classify and run
     * @param callback the body to execute once a lane accepts it
     * @return {@code true} if some lane accepted the work; {@code false} when the scheduler is
     *     closed, the policy rejected the task, no sidecar is available, or the sidecar dispatch
     *     threw — a diagnostic event is emitted in each of those cases
     * @throws NullPointerException if either argument is {@code null}
     */
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

    /**
     * Submits work that the caller already believes is lightweight, under a cancellation token
     * the caller owns and can trip later.
     *
     * <p>Unlike {@link #dispatch}, this does not fall back to the sidecar: a task the policy does
     * not classify as {@link WorkBudget#LIGHTWEIGHT} is refused outright.
     *
     * @param task the work to run
     * @param token cancellation token bound to the executing thread for the duration of the
     *     callback
     * @param callback the body to execute
     * @return the executor's submission; a rejected submission carrying
     *     {@code RUNTIME_UNAVAILABLE} when the scheduler is closed, or {@code POLICY_REJECTED}
     *     when the policy did not classify the task as lightweight
     * @throws NullPointerException if any argument is {@code null}
     */
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

    /**
     * Queues a settlement callback on a plugin's completion lane, kept separate from ordinary
     * work so a saturated plugin can still finish work already in flight.
     *
     * <p>The task is synthesised internally with task type {@code sidecar.complete} and no
     * declared capability.
     *
     * @param pluginId plugin whose completion lane should run the callback; non-blank
     * @param callback the settlement body
     * @return the executor's submission, or a rejected submission carrying
     *     {@code RUNTIME_UNAVAILABLE} when the scheduler is closed
     * @throws NullPointerException if either argument is {@code null}
     * @throws IllegalArgumentException if {@code pluginId} is blank
     */
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

    /**
     * Schedules a callback to run once after {@code delay} on the shared timer thread.
     *
     * <p>The callback runs off the Cubism host thread and outside any cancellation context. Its
     * timer permit is released once it has run, been cancelled, or failed to bind.
     *
     * @param delay how long to wait; zero is allowed, negative is not
     * @param callback the body to run
     * @return an accepted submission with a live handle, or a rejected submission with an inert
     *     handle when the scheduler is closed or the global 1024-timer budget is exhausted
     * @throws NullPointerException if either argument is {@code null}
     * @throws IllegalArgumentException if {@code delay} is negative
     */
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

    /**
     * Registers a plugin task scheduler as depending on this runtime scheduler.
     *
     * <p>While any lease is outstanding {@link #shutdown()} refuses to run, so a plugin scheduler
     * cannot be left holding a closed runtime. The caller must release the returned lease exactly
     * once; releasing more often than acquired is an error.
     *
     * @return the lease to release when the dependent scheduler is done
     * @throws IllegalStateException if this scheduler is already closed
     */
    public RuntimeSchedulerLease acquirePluginTaskSchedulerLease() {
        synchronized (lifecycleLock) {
            if (closed.get()) {
                throw new IllegalStateException("Runtime scheduler is already closed");
            }
            pluginTaskSchedulerLeases++;
            return new RuntimeSchedulerLease(this::releasePluginTaskSchedulerLease);
        }
    }

    /**
     * @return {@code true} once {@link #shutdown()} has completed its state transition, after
     *     which every submission is refused
     */
    public boolean isClosed() {
        return closed.get();
    }

    int activeTimerCount() {
        return activeTimers.size();
    }

    int availableTimerPermits() {
        return timerPermits.availablePermits();
    }

    /**
     * Closes the scheduler: refuses further submissions, cancels every pending timer, stops the
     * timer thread and shuts down all plugin executors.
     *
     * <p>Idempotent — a second call returns without effect. Does not wait for work already
     * running to finish.
     *
     * @throws IllegalStateException if any plugin task scheduler lease is still outstanding; the
     *     scheduler is left open in that case
     */
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
