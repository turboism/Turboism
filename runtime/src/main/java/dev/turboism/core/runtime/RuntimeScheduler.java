package dev.turboism.core.runtime;

import dev.turboism.core.diagnostics.CallbackBudgetEvent;
import dev.turboism.core.runtime.sidecar.SidecarDispatcher;
import dev.turboism.sdk.plugin.WorkBudget;
import java.util.Objects;
import java.util.function.Consumer;

public final class RuntimeScheduler {

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
            case LIGHTWEIGHT, HEAVY -> executorRegistry.get(task.pluginId()).execute(task, bindCancellation(callback));
            case SIDECAR -> sidecarDispatcher.dispatch(task, bindCancellation(callback));
            case REJECTED -> emitRejected(task);
        }
    }

    public void shutdown() {
        executorRegistry.shutdownAll();
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
        diagnosticSink.accept(new CallbackBudgetEvent(
            task.pluginId(),
            task.taskType(),
            CallbackBudgetEvent.Phase.REJECTED,
            CallbackBudgetEvent.Decision.REJECTED,
            CallbackBudgetEvent.Severity.WARNING
        ));
    }
}
