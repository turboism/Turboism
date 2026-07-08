package dev.turboism.core.runtime;

import dev.turboism.core.diagnostics.CallbackBudgetEvent;
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
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.Supplier;

public final class PluginCallbackExecutor {

    private static final long SHUTDOWN_TIMEOUT_SECONDS = 5L;

    private final String pluginId;
    private final PluginCallbackExecutorConfiguration configuration;
    private final Consumer<CallbackBudgetEvent> diagnosticSink;
    private final ThreadPoolBulkhead bulkhead;
    private final TimeLimiter timeLimiter;
    private final CircuitBreaker circuitBreaker;
    private final ScheduledExecutorService timeoutScheduler;

    public PluginCallbackExecutor(
        String pluginId,
        int workerCount,
        int queueCapacity,
        Consumer<CallbackBudgetEvent> diagnosticSink,
        Clock clock
    ) {
        this(
            pluginId,
            PluginCallbackExecutorConfiguration.of(500, workerCount, queueCapacity, 50.0f),
            diagnosticSink,
            clock
        );
    }

    PluginCallbackExecutor(
        String pluginId,
        PluginCallbackExecutorConfiguration configuration,
        Consumer<CallbackBudgetEvent> diagnosticSink,
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
            new PluginThreadFactory(this.pluginId + "-timeout")
        );
    }

    public void execute(PluginTask task, Runnable callback) {
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(callback, "callback");
        PluginCallback pluginCallback = new PluginCallback(task, callback);
        Supplier<CompletionStage<Void>> decorated = decorate(pluginCallback);
        try {
            decorated.get().whenComplete((result, failure) -> emitFailure(pluginCallback, failure));
        } catch (CallNotPermittedException exception) {
            emit(task, CallbackBudgetEvent.Phase.CIRCUIT_OPEN, CallbackBudgetEvent.Decision.REJECTED, CallbackBudgetEvent.Severity.WARNING);
        } catch (BulkheadFullException exception) {
            reject(task);
        }
    }

    public void shutdown() {
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
            throw new IllegalStateException("Plugin callback bulkhead failed to close for " + pluginId, exception);
        }
    }

    private Supplier<CompletionStage<Void>> decorate(PluginCallback callback) {
        Supplier<CompletionStage<Void>> bulkheaded = ThreadPoolBulkhead.decorateRunnable(bulkhead, callback);
        Supplier<CompletionStage<Void>> timeLimited = TimeLimiter.decorateCompletionStage(
            timeLimiter,
            timeoutScheduler,
            bulkheaded
        );
        return CircuitBreaker.decorateCompletionStage(circuitBreaker, timeLimited);
    }

    private void emitFailure(PluginCallback callback, Throwable failure) {
        if (failure == null) {
            return;
        }
        Throwable cause = unwrap(failure);
        if (cause instanceof TimeoutException) {
            callback.interruptRunningThread();
            emit(callback.task(), CallbackBudgetEvent.Phase.TIMED_OUT, CallbackBudgetEvent.Decision.LIGHTWEIGHT, CallbackBudgetEvent.Severity.WARNING);
        } else if (cause instanceof CallNotPermittedException) {
            emit(callback.task(), CallbackBudgetEvent.Phase.CIRCUIT_OPEN, CallbackBudgetEvent.Decision.REJECTED, CallbackBudgetEvent.Severity.WARNING);
        } else if (cause instanceof BulkheadFullException) {
            emit(callback.task(), CallbackBudgetEvent.Phase.REJECTED, CallbackBudgetEvent.Decision.REJECTED, CallbackBudgetEvent.Severity.WARNING);
        } else {
            emit(callback.task(), CallbackBudgetEvent.Phase.FAILED, CallbackBudgetEvent.Decision.LIGHTWEIGHT, CallbackBudgetEvent.Severity.ERROR);
        }
    }

    private void reject(PluginTask task) {
        emit(task, CallbackBudgetEvent.Phase.REJECTED, CallbackBudgetEvent.Decision.REJECTED, CallbackBudgetEvent.Severity.WARNING);
    }

    private void emit(
        PluginTask task,
        CallbackBudgetEvent.Phase phase,
        CallbackBudgetEvent.Decision decision,
        CallbackBudgetEvent.Severity severity
    ) {
        diagnosticSink.accept(new CallbackBudgetEvent(pluginId, task.taskType(), phase, decision, severity));
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
