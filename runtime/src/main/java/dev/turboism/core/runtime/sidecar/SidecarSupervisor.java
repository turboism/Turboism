package dev.turboism.core.runtime.sidecar;

import dev.turboism.core.diagnostics.PluginWorkBudgetEvent;
import dev.turboism.core.runtime.PluginTask;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Wraps a {@link SidecarDispatcher} with crash counting, bounded retry, and a
 * circuit breaker.
 *
 * <p>Any error or timeout result — and any exceptional completion — counts as a
 * crash. While the cumulative crash count stays within {@code maxRestartCount} the
 * task is retried; the crash count is cumulative over the supervisor’s life and is
 * never reset by a successful run, so the breaker eventually opens. Once it opens,
 * health becomes {@link SidecarHealth#UNAVAILABLE} permanently and every later
 * dispatch fails immediately with a {@link SidecarDispatchException}.</p>
 *
 * <p>Each crash, retry, and breaker trip is reported to the diagnostic sink as a
 * {@link PluginWorkBudgetEvent}. Health and crash count are held in atomics, so
 * the supervisor is safe to share across threads.</p>
 */
public final class SidecarSupervisor implements SidecarDispatcher {

    private static final String UNAVAILABLE_CODE = "SIDECAR_UNAVAILABLE";

    private final SidecarDispatcher dispatcher;
    private final int maxRestartCount;
    private final Consumer<PluginWorkBudgetEvent> diagnosticSink;
    private final AtomicInteger crashCount = new AtomicInteger();
    private final AtomicReference<SidecarHealth> health = new AtomicReference<>(SidecarHealth.HEALTHY);

    public SidecarSupervisor(
        final SidecarDispatcher dispatcher,
        final int maxRestartCount,
        final Consumer<PluginWorkBudgetEvent> diagnosticSink
    ) {
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
        if (maxRestartCount < 0) {
            throw new IllegalArgumentException("maxRestartCount must not be negative");
        }
        this.maxRestartCount = maxRestartCount;
        this.diagnosticSink = Objects.requireNonNull(diagnosticSink, "diagnosticSink");
    }

    @Override
    public boolean isAvailable() {
        return health.get() != SidecarHealth.UNAVAILABLE && dispatcher.isAvailable();
    }

    @Override
    public CompletionStage<SidecarResult> dispatch(final PluginTask task, final Runnable callback) {
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(callback, "callback");
        if (health.get() == SidecarHealth.UNAVAILABLE) {
            return CompletableFuture.failedFuture(unavailable());
        }
        return dispatchAttempt(task, callback);
    }

    /**
     * @return the current health verdict; {@link SidecarHealth#UNAVAILABLE} is
     *     terminal and means all further dispatches are refused
     */
    public SidecarHealth health() {
        return health.get();
    }

    /**
     * @return how many crashes this supervisor has observed since construction;
     *     cumulative and never reset by a successful run
     */
    public int crashCount() {
        return crashCount.get();
    }

    private CompletionStage<SidecarResult> dispatchAttempt(final PluginTask task, final Runnable callback) {
        final CompletableFuture<SidecarResult> supervised = new CompletableFuture<>();
        dispatcher.dispatch(task, callback).whenComplete((result, failure) -> {
            if (failure != null) {
                handleCrash(task, callback, supervised, SidecarResult.error("SIDECAR_DISPATCH_FAILED", failure.getMessage()));
                return;
            }
            if (isCrash(result)) {
                handleCrash(task, callback, supervised, result);
                return;
            }
            health.set(SidecarHealth.HEALTHY);
            supervised.complete(result);
        });
        return supervised;
    }

    private void handleCrash(
        final PluginTask task,
        final Runnable callback,
        final CompletableFuture<SidecarResult> supervised,
        final SidecarResult crash
    ) {
        final int crashes = crashCount.incrementAndGet();
        emit(task, PluginWorkBudgetEvent.Phase.FAILED, PluginWorkBudgetEvent.Severity.WARNING);
        if (crashes > maxRestartCount) {
            health.set(SidecarHealth.UNAVAILABLE);
            emit(task, PluginWorkBudgetEvent.Phase.CIRCUIT_OPEN, PluginWorkBudgetEvent.Severity.ERROR);
            supervised.complete(SidecarResult.error(UNAVAILABLE_CODE, crash.errorMessage()));
            return;
        }
        health.set(SidecarHealth.RESTARTING);
        emit(task, PluginWorkBudgetEvent.Phase.QUEUED, PluginWorkBudgetEvent.Severity.INFO);
        dispatchAttempt(task, callback).whenComplete((result, failure) -> {
            if (failure != null) {
                supervised.completeExceptionally(failure);
                return;
            }
            supervised.complete(result);
        });
    }

    private void emit(
        final PluginTask task,
        final PluginWorkBudgetEvent.Phase phase,
        final PluginWorkBudgetEvent.Severity severity
    ) {
        diagnosticSink.accept(new PluginWorkBudgetEvent(
            task.pluginId(),
            task.taskType(),
            phase,
            PluginWorkBudgetEvent.Decision.SIDECAR,
            severity
        ));
    }

    private static boolean isCrash(final SidecarResult result) {
        return result.kind() == SidecarResult.Kind.ERROR || result.kind() == SidecarResult.Kind.TIMEOUT;
    }

    private static SidecarDispatchException unavailable() {
        return new SidecarDispatchException(UNAVAILABLE_CODE, "Sidecar is unavailable after repeated crashes");
    }
}
