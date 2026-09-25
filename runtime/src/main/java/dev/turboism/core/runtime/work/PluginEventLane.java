package dev.turboism.core.runtime.work;

import dev.turboism.core.runtime.PluginTask;
import dev.turboism.core.diagnostics.PluginWorkBudgetEvent;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Serial delivery lane for one plugin's event drains, kept separate from
 * {@link PluginWorkExecutor} so subscriber callbacks neither share the task executor's worker
 * and queue nor live inside its wall-clock timeout or circuit-breaker window.
 *
 * <p>One daemon worker drains deliveries in order behind a bounded queue; a slow subscriber
 * blocks only this lane and is never interrupted — slowness is reported through the broker's
 * delivery diagnostics instead. A full queue refuses the drain with
 * {@link PluginWorkStatus#REJECTED_BACKPRESSURE}; a closed lane refuses with
 * {@link PluginWorkStatus#RUNTIME_UNAVAILABLE}.</p>
 */
public final class PluginEventLane {

    private static final long SHUTDOWN_TIMEOUT_SECONDS = 5L;

    private final String pluginId;
    private final Consumer<PluginWorkBudgetEvent> diagnosticSink;
    private final ThreadPoolExecutor worker;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    PluginEventLane(
        String pluginId,
        int queueCapacity,
        Consumer<PluginWorkBudgetEvent> diagnosticSink
    ) {
        this.pluginId = requireText(pluginId, "pluginId");
        this.diagnosticSink = Objects.requireNonNull(diagnosticSink, "diagnosticSink");
        this.worker = new ThreadPoolExecutor(
            1,
            1,
            0L,
            TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(queueCapacity),
            new PluginWorkThreadFactory(this.pluginId + "-event"),
            new ThreadPoolExecutor.AbortPolicy()
        );
    }

    /**
     * Submits one drain to the lane. Never throws for a lane outcome: a full queue or a closed
     * lane comes back as a non-accepted {@link PluginWorkSubmission}.
     *
     * @param task the drain task, used to attribute diagnostics
     * @param work the delivery body; its internal failures stay inside the drain
     * @return the admission decision plus a stage completing with the drain's terminal result
     */
    public PluginWorkSubmission submit(PluginTask task, Runnable work) {
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(work, "work");
        if (closed.get()) {
            return rejected(PluginWorkStatus.RUNTIME_UNAVAILABLE, "RUNTIME_UNAVAILABLE");
        }
        final CompletableFuture<PluginWorkResult> completion = new CompletableFuture<>();
        try {
            CompletableFuture.runAsync(() -> {
                try {
                    work.run();
                    completion.complete(PluginWorkResult.succeeded());
                } catch (Throwable failure) {
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
                }
            }, worker);
        } catch (RejectedExecutionException exception) {
            emit(
                task,
                PluginWorkBudgetEvent.Phase.REJECTED,
                PluginWorkBudgetEvent.Decision.REJECTED,
                PluginWorkBudgetEvent.Severity.WARNING
            );
            return rejected(PluginWorkStatus.REJECTED_BACKPRESSURE, "BACKPRESSURE");
        }
        return new PluginWorkSubmission(
            true,
            PluginWorkStatus.SUCCEEDED,
            completion
        );
    }

    /**
     * Stops the lane and drains its queue, waiting up to five seconds, then a further five after
     * a forced shutdown. Idempotent; later submissions are refused with
     * {@link PluginWorkStatus#RUNTIME_UNAVAILABLE}.
     */
    public void shutdown() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        worker.shutdown();
        try {
            if (!worker.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                worker.shutdownNow();
                worker.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            }
        } catch (InterruptedException exception) {
            worker.shutdownNow();
            Thread.currentThread().interrupt();
        }
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
}
