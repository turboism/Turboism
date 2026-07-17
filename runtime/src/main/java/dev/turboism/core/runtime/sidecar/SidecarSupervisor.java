package dev.turboism.core.runtime.sidecar;

import dev.turboism.core.diagnostics.CallbackBudgetEvent;
import dev.turboism.core.runtime.PluginTask;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

public final class SidecarSupervisor implements SidecarDispatcher {

    private static final String UNAVAILABLE_CODE = "SIDECAR_UNAVAILABLE";

    private final SidecarDispatcher dispatcher;
    private final int maxRestartCount;
    private final Consumer<CallbackBudgetEvent> diagnosticSink;
    private final AtomicInteger crashCount = new AtomicInteger();
    private final AtomicReference<SidecarHealth> health = new AtomicReference<>(SidecarHealth.HEALTHY);

    public SidecarSupervisor(
        final SidecarDispatcher dispatcher,
        final int maxRestartCount,
        final Consumer<CallbackBudgetEvent> diagnosticSink
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

    public SidecarHealth health() {
        return health.get();
    }

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
        emit(task, CallbackBudgetEvent.Phase.FAILED, CallbackBudgetEvent.Severity.WARNING);
        if (crashes > maxRestartCount) {
            health.set(SidecarHealth.UNAVAILABLE);
            emit(task, CallbackBudgetEvent.Phase.CIRCUIT_OPEN, CallbackBudgetEvent.Severity.ERROR);
            supervised.complete(SidecarResult.error(UNAVAILABLE_CODE, crash.errorMessage()));
            return;
        }
        health.set(SidecarHealth.RESTARTING);
        emit(task, CallbackBudgetEvent.Phase.QUEUED, CallbackBudgetEvent.Severity.INFO);
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
        final CallbackBudgetEvent.Phase phase,
        final CallbackBudgetEvent.Severity severity
    ) {
        diagnosticSink.accept(new CallbackBudgetEvent(
            task.pluginId(),
            task.taskType(),
            phase,
            CallbackBudgetEvent.Decision.SIDECAR,
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
