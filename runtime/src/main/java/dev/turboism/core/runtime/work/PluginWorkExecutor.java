package dev.turboism.core.runtime.work;

import dev.turboism.core.diagnostics.PluginWorkBudgetEvent;
import dev.turboism.core.runtime.contract.PluginTask;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.timelimiter.TimeLimiter;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import java.time.Clock;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Runs one plugin's off-thread work under a per-plugin budget: a bounded thread-pool bulkhead, a
 * time limiter, and a circuit breaker.
 *
 * <p>Misbehaving plugin work is contained rather than propagated. Nothing thrown by the submitted
 * {@link Runnable} escapes to the caller: overruns, admission refusals and failures are converted
 * into a {@link PluginWorkResult} and reported to the diagnostic sink as a
 * {@link dev.turboism.core.diagnostics.PluginWorkBudgetEvent}. One instance serves one plugin id
 * and is safe for concurrent submission.
 */
public final class PluginWorkExecutor {

    private final String pluginId;
    private final PluginWorkExecutorConfiguration configuration;
    private final Consumer<PluginWorkBudgetEvent> diagnosticSink;
    private final ThreadPoolExecutor workerPool;
    /**
     * Settles completions on a lane of its own.
     *
     * <p>A completion unblocks the very work whose result it carries. Sharing the worker pool
     * deadlocked every plugin that joined a runtime future from its own worker: the storage thread
     * finished the read, queued the continuation behind the blocked worker, and the join waited for
     * a slot that could never free. The theme manager hung this way for whole sessions.</p>
     */
    private final ThreadPoolExecutor completionPool;

    private final TimeLimiter timeLimiter;
    private final CircuitBreaker circuitBreaker;
    private final ScheduledExecutorService timeoutScheduler;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    public PluginWorkExecutor(
            String pluginId,
            int workerCount,
            int queueCapacity,
            Consumer<PluginWorkBudgetEvent> diagnosticSink,
            Clock clock) {
        this(
                pluginId,
                PluginWorkExecutorConfiguration.of(500, workerCount, queueCapacity, 50.0f),
                diagnosticSink,
                clock);
    }

    PluginWorkExecutor(
            String pluginId,
            PluginWorkExecutorConfiguration configuration,
            Consumer<PluginWorkBudgetEvent> diagnosticSink,
            Clock clock) {
        this(pluginId, configuration, diagnosticSink, clock, createTimeoutScheduler(pluginId));
    }

    /** Creates a work executor that owns and shuts down the supplied timeout scheduler. */
    public PluginWorkExecutor(
            String pluginId,
            PluginWorkExecutorConfiguration configuration,
            Consumer<PluginWorkBudgetEvent> diagnosticSink,
            Clock clock,
            ScheduledExecutorService timeoutScheduler) {
        this.timeoutScheduler = Objects.requireNonNull(timeoutScheduler, "timeoutScheduler");
        this.pluginId = requireText(pluginId, "pluginId");
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        this.diagnosticSink = Objects.requireNonNull(diagnosticSink, "diagnosticSink");
        Objects.requireNonNull(clock, "clock");
        // A plain bounded pool replaces the resilience4j ThreadPoolBulkhead: identical
        // admission (fixed workers + bounded queue + abort) but its threads are daemon and
        // named after the plugin, so a live executor can neither pin the JVM nor leak
        // unattributed threads.
        this.workerPool = new ThreadPoolExecutor(
                configuration.bulkheadPoolSize(),
                configuration.bulkheadPoolSize(),
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(configuration.queueCapacity()),
                new PluginWorkThreadFactory(this.pluginId),
                new ThreadPoolExecutor.AbortPolicy());
        this.completionPool = new ThreadPoolExecutor(
                1,
                1,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(configuration.queueCapacity()),
                new PluginWorkThreadFactory(this.pluginId + "-completion"),
                new ThreadPoolExecutor.AbortPolicy());
        this.timeLimiter = TimeLimiter.of(
                this.pluginId,
                TimeLimiterConfig.custom()
                        .timeoutDuration(configuration.timeoutDuration())
                        .cancelRunningFuture(true)
                        .build());
        this.circuitBreaker = CircuitBreaker.of(
                this.pluginId,
                CircuitBreakerConfig.custom()
                        .failureRateThreshold(configuration.circuitBreakerFailureRateThreshold())
                        .minimumNumberOfCalls(4)
                        .slidingWindowSize(4)
                        .build());
    }

    static ScheduledExecutorService createTimeoutScheduler(String pluginId) {
        return new ScheduledThreadPoolExecutor(1, new PluginWorkThreadFactory(pluginId + "-timeout"));
    }

    /**
     * Submits work through the full budget and discards the submission handle.
     *
     * <p>Fire-and-forget: a rejection is still recorded as a diagnostic event but is invisible here.
     * Use {@link #submit} when the caller needs to observe admission or completion.
     *
     * @param task the task being run, used to attribute diagnostics
     * @param work the body to run on a bulkhead thread
     * @throws NullPointerException if either argument is {@code null}
     */
    public void execute(PluginTask task, Runnable work) {
        submit(task, work);
    }

    /**
     * Submits work under the full budget, including the circuit breaker.
     *
     * <p>Never throws for a budget outcome: a full queue, an open circuit, or a shut-down executor come
     * back as a non-accepted {@link PluginWorkSubmission} whose completion is already finished with the
     * matching {@link PluginWorkStatus}. A timeout interrupts the running thread and completes the
     * submission with {@link PluginWorkStatus#TIMED_OUT}.
     *
     * @param task the task being run, used to attribute diagnostics
     * @param work the body to run on a bulkhead thread
     * @return the admission decision plus a stage completing with the work's terminal result
     * @throws NullPointerException if either argument is {@code null}
     */
    public PluginWorkSubmission submit(PluginTask task, Runnable work) {
        return submit(task, work, () -> {});
    }

    /**
     * Submits work and runs the supplied timeout action before interrupting an over-budget worker.
     * The action must be idempotent because timeout settlement may race with worker completion.
     *
     * @param task the task being run, used to attribute diagnostics
     * @param work the body to run on a bulkhead thread
     * @param timeoutAction idempotent cancellation for downstream work queued by the worker
     * @return the admission decision plus a stage completing with the work's terminal result
     */
    public PluginWorkSubmission submit(PluginTask task, Runnable work, Runnable timeoutAction) {
        return submitDecorated(task, work, timeoutAction, true);
    }

    /**
     * Submits work to settle a completed runtime future, on the dedicated completion lane.
     *
     * <p>The settle body is the only thing that can release a plugin worker blocked on that future,
     * so it must never queue behind that worker. The lane is serial and untimed for the same
     * reason: a settlement that overran a wall-clock budget would be retried forever while the
     * caller stayed blocked, which is what flooded the scheduler log and stopped the theme manager
     * from opening. Shutdown still drains the lane with the configured timeout.</p>
     *
     * @param task the synthetic completion task, used to attribute diagnostics
     * @param work the settle body
     * @return the admission decision plus a stage completing with the work's terminal result
     * @throws NullPointerException if either argument is {@code null}
     */
    public PluginWorkSubmission submitCompletion(PluginTask task, Runnable work) {
        return submitDecorated(task, work, () -> {}, false, false, true);
    }

    /**
     * Submits EDT-bound work outside the work budget and the circuit breaker.
     *
     * <p>This work spends most of its wall time queued for the host EDT, which the plugin does not
     * own. Charging that queue latency to the plugin opened its breaker whenever a large model
     * stalled the host: on the 763 MB reference model one session logged thousands of
     * {@code CIRCUIT_OPEN} rejections and the plugin's UI never recovered. The bulkhead still
     * bounds concurrency and shutdown still waits for the work, so containment is unchanged; only
     * the timeout and the breaker accounting are skipped. The caller's own cancellation still
     * applies, so abandoned UI work is dropped before it touches the host.</p>
     *
     * @param task the task being run, used to attribute diagnostics
     * @param work the body to run on a bulkhead thread
     * @return the admission decision plus a stage completing with the work's terminal result
     * @throws NullPointerException if either argument is {@code null}
     */
    public PluginWorkSubmission submitUiBound(PluginTask task, Runnable work) {
        return submitDecorated(task, work, () -> {}, false, false);
    }

    private PluginWorkSubmission submitDecorated(
            PluginTask task, Runnable work, Runnable timeoutAction, boolean circuitProtected) {
        return submitDecorated(task, work, timeoutAction, circuitProtected, true, false);
    }

    private PluginWorkSubmission submitDecorated(
            PluginTask task, Runnable work, Runnable timeoutAction, boolean circuitProtected, boolean timeLimited) {
        return submitDecorated(task, work, timeoutAction, circuitProtected, timeLimited, false);
    }

    private PluginWorkSubmission submitDecorated(
            PluginTask task,
            Runnable work,
            Runnable timeoutAction,
            boolean circuitProtected,
            boolean timeLimited,
            boolean completionLane) {
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(work, "work");
        Objects.requireNonNull(timeoutAction, "timeoutAction");
        if (closed.get()) {
            return rejected(PluginWorkStatus.RUNTIME_UNAVAILABLE, "RUNTIME_UNAVAILABLE");
        }
        PluginWorkItem workItem = new PluginWorkItem(task, work, timeoutAction);
        final Supplier<CompletionStage<Void>> decorated;
        if (!timeLimited) {
            final ThreadPoolExecutor pool = completionLane ? completionPool : workerPool;
            decorated = () -> CompletableFuture.runAsync(workItem, pool);
        } else if (circuitProtected) {
            decorated = decorate(workItem);
        } else {
            decorated = decorateCompletion(workItem);
        }
        final CompletableFuture<PluginWorkResult> completion = new CompletableFuture<>();
        try {
            final CompletionStage<Void> stage = decorated.get();
            final CompletableFuture<Void> future = stage.toCompletableFuture();
            if (future.isCompletedExceptionally()) {
                final PluginWorkResult immediate = immediateFailure(workItem, future);
                if (isAdmissionRejection(immediate.status())) {
                    return rejected(immediate.status(), immediate.failureCode());
                }
                completion.complete(immediate);
                return new PluginWorkSubmission(true, PluginWorkStatus.SUCCEEDED, completion);
            }
            stage.whenComplete((result, failure) -> completion.complete(executionResult(workItem, failure)));
            return new PluginWorkSubmission(true, PluginWorkStatus.SUCCEEDED, completion);
        } catch (CallNotPermittedException exception) {
            emit(
                    task,
                    PluginWorkBudgetEvent.Phase.CIRCUIT_OPEN,
                    PluginWorkBudgetEvent.Decision.REJECTED,
                    PluginWorkBudgetEvent.Severity.WARNING);
            return rejected(PluginWorkStatus.REJECTED_CIRCUIT_OPEN, "CIRCUIT_OPEN");
        } catch (RejectedExecutionException exception) {
            reject(task);
            return rejected(PluginWorkStatus.REJECTED_BACKPRESSURE, "BACKPRESSURE");
        } catch (RuntimeException exception) {
            emit(
                    task,
                    PluginWorkBudgetEvent.Phase.FAILED,
                    PluginWorkBudgetEvent.Decision.LIGHTWEIGHT,
                    PluginWorkBudgetEvent.Severity.ERROR);
            return rejected(PluginWorkStatus.RUNTIME_UNAVAILABLE, "RUNTIME_UNAVAILABLE");
        }
    }

    /**
     * Closes the worker pool and drains the timeout scheduler, waiting up to five seconds, then a
     * further five after a forced shutdown.
     *
     * <p>Idempotent: only the first call does work. After it, every submission is refused with
     * {@link PluginWorkStatus#RUNTIME_UNAVAILABLE}. An interrupt during the wait forces shutdown and
     * restores the thread's interrupt flag rather than throwing.
     */
    public void shutdown() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        shutdownPool(workerPool);
        shutdownPool(completionPool);
        shutdownPool(timeoutScheduler);
    }

    boolean isTerminated() {
        return workerPool.isTerminated() && completionPool.isTerminated() && timeoutScheduler.isTerminated();
    }

    /**
     * Aborts both pools without waiting for running work.
     *
     * <p>Only for a generation that already failed and was fenced, where a graceful drain can
     * no longer accomplish anything and would only delay the caller. Queued work is discarded;
     * worker threads are daemon threads and exit once their current body returns. Idempotent.
     */
    public void discard() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        workerPool.shutdownNow();
        completionPool.shutdownNow();
        timeoutScheduler.shutdownNow();
    }

    private void shutdownPool(final java.util.concurrent.ExecutorService pool) {
        pool.shutdown();
        try {
            if (!pool.awaitTermination(configuration.shutdownTimeout().toNanos(), TimeUnit.NANOSECONDS)) {
                pool.shutdownNow();
                pool.awaitTermination(configuration.shutdownTimeout().toNanos(), TimeUnit.NANOSECONDS);
            }
        } catch (InterruptedException exception) {
            pool.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private Supplier<CompletionStage<Void>> decorate(PluginWorkItem workItem) {
        return CircuitBreaker.decorateCompletionStage(circuitBreaker, decorateCompletion(workItem));
    }

    private Supplier<CompletionStage<Void>> decorateCompletion(PluginWorkItem workItem) {
        Supplier<CompletionStage<Void>> pooled = () -> CompletableFuture.runAsync(workItem, workerPool);
        return TimeLimiter.decorateCompletionStage(timeLimiter, timeoutScheduler, pooled);
    }

    private PluginWorkResult immediateFailure(PluginWorkItem workItem, CompletableFuture<Void> future) {
        try {
            future.join();
            return PluginWorkResult.succeeded();
        } catch (CompletionException exception) {
            return executionResult(workItem, exception);
        }
    }

    private PluginWorkResult executionResult(PluginWorkItem workItem, Throwable failure) {
        if (failure == null) {
            return PluginWorkResult.succeeded();
        }
        Throwable cause = unwrap(failure);
        if (cause instanceof TimeoutException) {
            workItem.timeout();
            emit(
                    workItem.task(),
                    PluginWorkBudgetEvent.Phase.TIMED_OUT,
                    PluginWorkBudgetEvent.Decision.LIGHTWEIGHT,
                    PluginWorkBudgetEvent.Severity.WARNING);
            return new PluginWorkResult(PluginWorkStatus.TIMED_OUT, "PLUGIN_WORK_TIMED_OUT");
        }
        if (cause instanceof CallNotPermittedException) {
            emit(
                    workItem.task(),
                    PluginWorkBudgetEvent.Phase.CIRCUIT_OPEN,
                    PluginWorkBudgetEvent.Decision.REJECTED,
                    PluginWorkBudgetEvent.Severity.WARNING);
            return new PluginWorkResult(PluginWorkStatus.REJECTED_CIRCUIT_OPEN, "CIRCUIT_OPEN");
        }
        if (cause instanceof RejectedExecutionException) {
            emit(
                    workItem.task(),
                    PluginWorkBudgetEvent.Phase.REJECTED,
                    PluginWorkBudgetEvent.Decision.REJECTED,
                    PluginWorkBudgetEvent.Severity.WARNING);
            return new PluginWorkResult(PluginWorkStatus.REJECTED_BACKPRESSURE, "BACKPRESSURE");
        }
        emit(
                workItem.task(),
                PluginWorkBudgetEvent.Phase.FAILED,
                PluginWorkBudgetEvent.Decision.LIGHTWEIGHT,
                PluginWorkBudgetEvent.Severity.ERROR);
        return new PluginWorkResult(PluginWorkStatus.FAILED, "PLUGIN_WORK_FAILED");
    }

    private static boolean isAdmissionRejection(final PluginWorkStatus status) {
        return status == PluginWorkStatus.REJECTED_BACKPRESSURE
                || status == PluginWorkStatus.REJECTED_CIRCUIT_OPEN
                || status == PluginWorkStatus.POLICY_REJECTED
                || status == PluginWorkStatus.RUNTIME_UNAVAILABLE;
    }

    private static PluginWorkSubmission rejected(PluginWorkStatus status, String failureCode) {
        PluginWorkResult result = new PluginWorkResult(status, failureCode);
        return new PluginWorkSubmission(false, status, CompletableFuture.completedFuture(result));
    }

    private void reject(PluginTask task) {
        emit(
                task,
                PluginWorkBudgetEvent.Phase.REJECTED,
                PluginWorkBudgetEvent.Decision.REJECTED,
                PluginWorkBudgetEvent.Severity.WARNING);
    }

    private void emit(
            PluginTask task,
            PluginWorkBudgetEvent.Phase phase,
            PluginWorkBudgetEvent.Decision decision,
            PluginWorkBudgetEvent.Severity severity) {
        diagnosticSink.accept(new PluginWorkBudgetEvent(pluginId, task.taskType(), phase, decision, severity));
    }

    private static Throwable unwrap(Throwable failure) {
        if (failure instanceof CompletionException && failure.getCause() != null) {
            return failure.getCause();
        }
        return failure;
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
