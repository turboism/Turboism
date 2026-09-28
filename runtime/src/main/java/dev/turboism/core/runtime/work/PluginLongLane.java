package dev.turboism.core.runtime.work;

import dev.turboism.core.runtime.PluginTask;
import dev.turboism.core.runtime.RuntimeCancellationToken;
import dev.turboism.core.diagnostics.PluginWorkBudgetEvent;
import dev.turboism.core.runtime.work.FatalErrors;
import dev.turboism.runtime.log.RuntimeDiagnostics;
import java.time.Duration;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Bounded long-running work lane for one plugin generation, kept separate from
 * {@link PluginWorkExecutor}: no wall-clock time limiter, no circuit breaker, and worker
 * threads are never interrupted — a long task that ignores cancellation is left running on its
 * daemon thread for the lifecycle layer (quiescence timeout and retention) to expose.
 *
 * <p>Cancellation is cooperative. A submission carries the caller-owned
 * {@link RuntimeCancellationToken}: while the item still waits in the bounded queue, cancelling
 * the token dequeues it and settles it {@link PluginWorkStatus#CANCELED}; once running, only the
 * flag is set and the plugin code is expected to observe it. {@link #shutdown()} requests
 * cancellation on every tracked submission, settles the queued ones, and waits a bounded time
 * for running work — it never calls {@code shutdownNow} on the plugin worker pool.
 *
 * <p>A task still running past the configured threshold is reported through
 * {@link RuntimeDiagnostics} as {@code LONG_TASK_RUNNING}, once at the threshold and then again
 * per report interval until it ends.
 */
public final class PluginLongLane {

    private static final long SHUTDOWN_TIMEOUT_SECONDS = 5L;
    private static final String DIAGNOSTIC_COMPONENT = "turboism.plugin.long-lane";

    private final String pluginId;
    private final Consumer<PluginWorkBudgetEvent> diagnosticSink;
    private final ThreadPoolExecutor worker;
    private final ScheduledThreadPoolExecutor monitor;
    private final Duration longRunningThreshold;
    private final Duration reportInterval;
    private final Set<LongWorkItem> active = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean closed = new AtomicBoolean(false);

    PluginLongLane(
        String pluginId,
        int concurrency,
        int queueCapacity,
        Duration longRunningThreshold,
        Duration reportInterval,
        Consumer<PluginWorkBudgetEvent> diagnosticSink
    ) {
        this.pluginId = requireText(pluginId, "pluginId");
        if (concurrency < 1) {
            throw new IllegalArgumentException("concurrency must be positive");
        }
        this.longRunningThreshold = Objects.requireNonNull(
            longRunningThreshold, "longRunningThreshold");
        this.reportInterval = Objects.requireNonNull(reportInterval, "reportInterval");
        this.diagnosticSink = Objects.requireNonNull(diagnosticSink, "diagnosticSink");
        this.worker = new ThreadPoolExecutor(
            concurrency,
            concurrency,
            0L,
            TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(queueCapacity),
            new PluginWorkThreadFactory(this.pluginId + "-long"),
            new ThreadPoolExecutor.AbortPolicy()
        );
        this.monitor = new ScheduledThreadPoolExecutor(
            1,
            new PluginWorkThreadFactory(this.pluginId + "-long-monitor")
        );
        this.monitor.setRemoveOnCancelPolicy(true);
    }

    /**
     * Admits one unit of long-running work. Never throws for a lane outcome: a full queue or a
     * closed lane comes back as a non-accepted {@link PluginWorkSubmission}; a token that is
     * already cancelled is admitted and immediately settled {@link PluginWorkStatus#CANCELED}.
     *
     * @param task the task being run, used to attribute diagnostics
     * @param token cancellation token bound to this work; cancelling it dequeues a still-queued
     *     item or flags a running one
     * @param work the body to run on a lane worker thread
     * @return the admission decision plus a stage completing with the work's terminal result
     */
    public PluginWorkSubmission submit(
        PluginTask task,
        RuntimeCancellationToken token,
        Runnable work
    ) {
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(token, "token");
        Objects.requireNonNull(work, "work");
        if (closed.get()) {
            return rejected(PluginWorkStatus.RUNTIME_UNAVAILABLE, "RUNTIME_UNAVAILABLE");
        }
        final LongWorkItem item = new LongWorkItem(task, token, work);
        token.onCancel(item::settleQueuedCancellation);
        if (item.settled.get()) {
            return accepted(item);
        }
        active.add(item);
        try {
            worker.execute(item);
        } catch (RejectedExecutionException exception) {
            active.remove(item);
            if (item.settled.get()) {
                return accepted(item);
            }
            if (closed.get()) {
                return rejected(PluginWorkStatus.RUNTIME_UNAVAILABLE, "RUNTIME_UNAVAILABLE");
            }
            emit(
                task,
                PluginWorkBudgetEvent.Phase.REJECTED,
                PluginWorkBudgetEvent.Decision.REJECTED,
                PluginWorkBudgetEvent.Severity.WARNING
            );
            return rejected(PluginWorkStatus.REJECTED_BACKPRESSURE, "BACKPRESSURE");
        }
        return accepted(item);
    }

    /**
     * Requests cooperative cancellation on every tracked submission — queued items settle
     * {@link PluginWorkStatus#CANCELED}, running ones get their token flag set — then stops
     * admitting and waits up to five seconds for running work to finish. Plugin worker threads
     * are never interrupted: a task still running after the wait leaves the lane unterminated
     * for the lifecycle layer to retain and report. Idempotent.
     */
    public void shutdown() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        for (LongWorkItem item : active) {
            item.token.cancel();
        }
        worker.shutdown();
        try {
            worker.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
        monitor.shutdownNow();
    }

    boolean isTerminated() {
        return worker.isTerminated();
    }

    private void emit(
        PluginTask task,
        PluginWorkBudgetEvent.Phase phase,
        PluginWorkBudgetEvent.Decision decision,
        PluginWorkBudgetEvent.Severity severity
    ) {
        diagnosticSink.accept(new PluginWorkBudgetEvent(
            pluginId,
            task.taskType(),
            phase,
            decision,
            severity
        ));
    }

    private static PluginWorkSubmission accepted(LongWorkItem item) {
        return new PluginWorkSubmission(
            true,
            PluginWorkStatus.SUCCEEDED,
            item.completion
        );
    }

    private static PluginWorkSubmission rejected(
        PluginWorkStatus status,
        String failureCode
    ) {
        PluginWorkResult result = new PluginWorkResult(status, failureCode);
        return new PluginWorkSubmission(false, status, CompletableFuture.completedFuture(result));
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private final class LongWorkItem implements Runnable {

        private final PluginTask task;
        private final RuntimeCancellationToken token;
        private final Runnable work;
        private final CompletableFuture<PluginWorkResult> completion = new CompletableFuture<>();
        private final AtomicBoolean settled = new AtomicBoolean(false);
        private volatile long startedNanos;

        private LongWorkItem(PluginTask task, RuntimeCancellationToken token, Runnable work) {
            this.task = task;
            this.token = token;
            this.work = work;
        }

        @Override
        public void run() {
            if (!settled.compareAndSet(false, true)) {
                active.remove(this);
                return;
            }
            startedNanos = System.nanoTime();
            ScheduledFuture<?> report = null;
            try {
                report = monitor.scheduleWithFixedDelay(
                    this::reportLongRunning,
                    longRunningThreshold.toMillis(),
                    reportInterval.toMillis(),
                    TimeUnit.MILLISECONDS
                );
            } catch (RuntimeException ignored) {
                // A shut-down monitor loses only the slow-work diagnostic, never the work itself.
            }
            try {
                work.run();
                completion.complete(PluginWorkResult.succeeded());
            } catch (Throwable failure) {
                FatalErrors.rethrowIfFatal(failure);
                emit(
                    task,
                    PluginWorkBudgetEvent.Phase.FAILED,
                    PluginWorkBudgetEvent.Decision.LIGHTWEIGHT,
                    PluginWorkBudgetEvent.Severity.ERROR
                );
                completion.complete(new PluginWorkResult(
                    PluginWorkStatus.FAILED,
                    "PLUGIN_WORK_FAILED"
                ));
            } finally {
                if (report != null) {
                    report.cancel(false);
                }
                active.remove(this);
            }
        }

        /**
         * Settles this item when its token is cancelled while it still waits in the queue.
         * A running item has already claimed {@code settled}, so cancellation only leaves the
         * token flag set for the plugin code to observe.
         */
        private void settleQueuedCancellation() {
            if (settled.compareAndSet(false, true)) {
                worker.remove(this);
                active.remove(this);
                completion.complete(new PluginWorkResult(
                    PluginWorkStatus.CANCELED,
                    "TASK_CANCELED"
                ));
            }
        }

        private void reportLongRunning() {
            final long elapsedMillis =
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
            RuntimeDiagnostics.warn(
                DIAGNOSTIC_COMPONENT,
                "LONG_TASK_RUNNING plugin=" + pluginId
                    + " task=" + task.taskType()
                    + " elapsedMillis=" + elapsedMillis
            );
        }
    }
}
