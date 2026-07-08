package dev.turboism.core.runtime;

import dev.turboism.core.diagnostics.CallbackBudgetEvent;
import dev.turboism.core.runtime.sidecar.SidecarDispatcher;
import dev.turboism.core.runtime.sidecar.SidecarResult;
import dev.turboism.sdk.plugin.WorkBudget;

import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

public final class RuntimeScheduler {

    private static final String SIDECAR_COMPLETION_TASK_TYPE = "sidecar.complete";

    private final WorkBudgetPolicy policy;
    private final PluginExecutorRegistry executorRegistry;
    private final SidecarDispatcher sidecarDispatcher;
    private final Consumer<CallbackBudgetEvent> diagnosticSink;

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
    }

    public void dispatch(PluginTask task, Runnable callback) {
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(callback, "callback");
        WorkBudget budget = policy.classify(task);
        switch (budget) {
            case LIGHTWEIGHT -> executorRegistry.get(task.pluginId()).execute(task, bindCancellation(callback));
            case SIDECAR -> dispatchSidecar(task, callback);
            case REJECTED -> emitRejected(task);
        }
    }

    public void shutdown() {
        executorRegistry.shutdownAll();
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
        if (result.kind() == SidecarResult.Kind.TIMEOUT) {
            emit(task, CallbackBudgetEvent.Phase.TIMED_OUT, CallbackBudgetEvent.Decision.SIDECAR, CallbackBudgetEvent.Severity.WARNING);
            return;
        }
        emit(task, CallbackBudgetEvent.Phase.FAILED, CallbackBudgetEvent.Decision.SIDECAR, CallbackBudgetEvent.Severity.ERROR);
    }

    private static Runnable bindCancellation(Runnable callback) {
        return () -> {
            RuntimeCancellationToken token = new RuntimeCancellationToken();
            CancellationContext.set(token);
            try {
                callback.run();
            } finally {
                CancellationContext.clear();
            }
        };
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
        diagnosticSink.accept(new CallbackBudgetEvent(task.pluginId(), task.taskType(), phase, decision, severity));
    }
}
