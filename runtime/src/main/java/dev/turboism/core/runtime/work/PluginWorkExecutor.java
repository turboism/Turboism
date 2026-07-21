package dev.turboism.core.runtime.work;

import dev.turboism.core.runtime.PluginTask;
import dev.turboism.core.diagnostics.PluginWorkBudgetEvent;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.bulkhead.ThreadPoolBulkhead;
import io.github.resilience4j.bulkhead.ThreadPoolBulkheadConfig;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.timelimiter.TimeLimiter;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import java.time.Clock;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;

public final class PluginWorkExecutor {

    private static final long SHUTDOWN_TIMEOUT_SECONDS = 5L;

    private final String pluginId;
    private final PluginWorkExecutorConfiguration configuration;
    private final Consumer<PluginWorkBudgetEvent> diagnosticSink;
    private final ThreadPoolBulkhead bulkhead;
    private final TimeLimiter timeLimiter;
    private final CircuitBreaker circuitBreaker;
    private final ScheduledExecutorService timeoutScheduler;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    public PluginWorkExecutor(
        String pluginId,
        int workerCount,
        int queueCapacity,
        Consumer<PluginWorkBudgetEvent> diagnosticSink,
        Clock clock
    ) {
        this(
            pluginId,
            PluginWorkExecutorConfiguration.of(500, workerCount, queueCapacity, 50.0f),
            diagnosticSink,
            clock
        );
    }

    PluginWorkExecutor(
        String pluginId,
        PluginWorkExecutorConfiguration configuration,
        Consumer<PluginWorkBudgetEvent> diagnosticSink,
        Clock clock
    ) {
        this.pluginId = requireText(pluginId, "pluginId");
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        this.diagnosticSink = Objects.requireNonNull(diagnosticSink, "diagnosticSink");
        Objects.requireNonNull(clock, "clock");
        this.bulkhead = ThreadPoolBulkhead.of(
            this.pluginId,
            ThreadPoolBulkheadConfig.custom()
                .coreThreadPoolSize(configuration.bulkheadPoolSize())
                .maxThreadPoolSize(configuration.bulkheadPoolSize())
                .queueCapacity(configuration.queueCapacity())
                .build()
        );
        this.timeLimiter = TimeLimiter.of(
            this.pluginId,
            TimeLimiterConfig.custom()
                .timeoutDuration(configuration.timeoutDuration())
                .cancelRunningFuture(true)
                .build()
        );
        this.circuitBreaker = CircuitBreaker.of(
            this.pluginId,
            CircuitBreakerConfig.custom()
                .failureRateThreshold(configuration.circuitBreakerFailureRateThreshold())
                .minimumNumberOfCalls(4)
                .slidingWindowSize(4)
                .build()
        );
        this.timeoutScheduler = new ScheduledThreadPoolExecutor(
            1,
            new PluginWorkThreadFactory(this.pluginId + "-timeout")
        );
    }

    public void execute(PluginTask task, Runnable work) {
        submit(task, work);
    }

    public PluginWorkSubmission submit(PluginTask task, Runnable work) {
        return submitDecorated(task, work, true);
    }

    public PluginWorkSubmission submitCompletion(PluginTask task, Runnable work) {
        return submitDecorated(task, work, false);
    }

    private PluginWorkSubmission submitDecorated(
        PluginTask task,
        Runnable work,
        boolean circuitProtected
    ) {
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(work, "work");
        if (closed.get()) {
            return rejected(PluginWorkStatus.RUNTIME_UNAVAILABLE, "RUNTIME_UNAVAILABLE");
        }
        PluginWorkItem workItem = new PluginWorkItem(task, work);
        Supplier<CompletionStage<Void>> decorated = circuitProtected
            ? decorate(workItem)
            : decorateCompletion(workItem);
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
                return new PluginWorkSubmission(
                    true,
                    PluginWorkStatus.SUCCEEDED,
                    completion
                );
            }
            stage.whenComplete((result, failure) ->
                completion.complete(executionResult(workItem, failure))
            );
            return new PluginWorkSubmission(
                true,
                PluginWorkStatus.SUCCEEDED,
                completion
            );
        } catch (CallNotPermittedException exception) {
            emit(task, PluginWorkBudgetEvent.Phase.CIRCUIT_OPEN, PluginWorkBudgetEvent.Decision.REJECTED, PluginWorkBudgetEvent.Severity.WARNING);
            return rejected(PluginWorkStatus.REJECTED_CIRCUIT_OPEN, "CIRCUIT_OPEN");
        } catch (BulkheadFullException exception) {
            reject(task);
            return rejected(PluginWorkStatus.REJECTED_BACKPRESSURE, "BACKPRESSURE");
        } catch (RuntimeException exception) {
            emit(task, PluginWorkBudgetEvent.Phase.FAILED, PluginWorkBudgetEvent.Decision.LIGHTWEIGHT, PluginWorkBudgetEvent.Severity.ERROR);
            return rejected(PluginWorkStatus.RUNTIME_UNAVAILABLE, "RUNTIME_UNAVAILABLE");
        }
    }

    public void shutdown() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        closeBulkhead();
        timeoutScheduler.shutdown();
        try {
            if (!timeoutScheduler.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                timeoutScheduler.shutdownNow();
                timeoutScheduler.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            }
        } catch (InterruptedException exception) {
            timeoutScheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    boolean isTerminated() {
        return timeoutScheduler.isTerminated();
    }

    private void closeBulkhead() {
        try {
            bulkhead.close();
        } catch (Exception exception) {
            throw new IllegalStateException("Plugin work bulkhead failed to close for " + pluginId, exception);
        }
    }

    private Supplier<CompletionStage<Void>> decorate(PluginWorkItem workItem) {
        return CircuitBreaker.decorateCompletionStage(
            circuitBreaker,
            decorateCompletion(workItem)
        );
    }

    private Supplier<CompletionStage<Void>> decorateCompletion(PluginWorkItem workItem) {
        Supplier<CompletionStage<Void>> bulkheaded = ThreadPoolBulkhead.decorateRunnable(
            bulkhead,
            workItem
        );
        return TimeLimiter.decorateCompletionStage(
            timeLimiter,
            timeoutScheduler,
            bulkheaded
        );
    }

    private PluginWorkResult immediateFailure(
        PluginWorkItem workItem,
        CompletableFuture<Void> future
    ) {
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
            workItem.interruptRunningThread();
            emit(workItem.task(), PluginWorkBudgetEvent.Phase.TIMED_OUT, PluginWorkBudgetEvent.Decision.LIGHTWEIGHT, PluginWorkBudgetEvent.Severity.WARNING);
            return new PluginWorkResult(PluginWorkStatus.TIMED_OUT, "PLUGIN_WORK_TIMED_OUT");
        }
        if (cause instanceof CallNotPermittedException) {
            emit(workItem.task(), PluginWorkBudgetEvent.Phase.CIRCUIT_OPEN, PluginWorkBudgetEvent.Decision.REJECTED, PluginWorkBudgetEvent.Severity.WARNING);
            return new PluginWorkResult(PluginWorkStatus.REJECTED_CIRCUIT_OPEN, "CIRCUIT_OPEN");
        }
        if (cause instanceof BulkheadFullException) {
            emit(workItem.task(), PluginWorkBudgetEvent.Phase.REJECTED, PluginWorkBudgetEvent.Decision.REJECTED, PluginWorkBudgetEvent.Severity.WARNING);
            return new PluginWorkResult(PluginWorkStatus.REJECTED_BACKPRESSURE, "BACKPRESSURE");
        }
        emit(workItem.task(), PluginWorkBudgetEvent.Phase.FAILED, PluginWorkBudgetEvent.Decision.LIGHTWEIGHT, PluginWorkBudgetEvent.Severity.ERROR);
        return new PluginWorkResult(PluginWorkStatus.FAILED, "PLUGIN_WORK_FAILED");
    }

    private static boolean isAdmissionRejection(final PluginWorkStatus status) {
        return status == PluginWorkStatus.REJECTED_BACKPRESSURE
            || status == PluginWorkStatus.REJECTED_CIRCUIT_OPEN
            || status == PluginWorkStatus.POLICY_REJECTED
            || status == PluginWorkStatus.RUNTIME_UNAVAILABLE;
    }

    private static PluginWorkSubmission rejected(
        PluginWorkStatus status,
        String failureCode
    ) {
        PluginWorkResult result = new PluginWorkResult(status, failureCode);
        return new PluginWorkSubmission(false, status, CompletableFuture.completedFuture(result));
    }

    private void reject(PluginTask task) {
        emit(task, PluginWorkBudgetEvent.Phase.REJECTED, PluginWorkBudgetEvent.Decision.REJECTED, PluginWorkBudgetEvent.Severity.WARNING);
    }

    private void emit(
        PluginTask task,
        PluginWorkBudgetEvent.Phase phase,
        PluginWorkBudgetEvent.Decision decision,
        PluginWorkBudgetEvent.Severity severity
    ) {
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
