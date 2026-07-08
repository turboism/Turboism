package dev.turboism.core.plugin;

import dev.turboism.core.diagnostics.DisabledReason;
import dev.turboism.core.diagnostics.StartupReport;
import dev.turboism.core.lifecycle.PluginLifecycleState;
import dev.turboism.core.runtime.PluginTask;
import dev.turboism.core.runtime.RuntimeScheduler;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Skeleton plugin manager. Manages lifecycle state without real ClassLoader injection.
 */
public final class PluginManager {

    private final Map<String, PluginRuntime> plugins = new HashMap<>();
    private final StartupReport report = new StartupReport();
    private final RuntimeScheduler scheduler;

    public PluginManager(RuntimeScheduler scheduler) {
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
    }

    public PluginRuntime get(String id) {
        return plugins.get(id);
    }

    public Map<String, PluginRuntime> plugins() {
        return Collections.unmodifiableMap(plugins);
    }

    public StartupReport report() {
        return report;
    }

    public PluginRuntime registerDescriptor(PluginRuntime runtime) {
        plugins.put(runtime.id(), runtime);
        return runtime;
    }

    public void disable(String id) {
        PluginRuntime runtime = plugins.get(id);
        if (runtime == null) return;
        if (runtime.state() == PluginLifecycleState.ENABLED) {
            scheduler.dispatch(lifecycleTask(runtime, "lifecycle.disable"), () -> disableRuntime(runtime));
        }
        runtime.transitionTo(PluginLifecycleState.DISABLED);
    }

    private void disableRuntime(PluginRuntime runtime) {
        try {
            runtime.instance().disable();
        } catch (Exception e) {
            runtime.transitionTo(PluginLifecycleState.DISABLE_FAILED);
            report.addProblem("DISABLE_FAILED", e.getMessage(), runtime.id(), StartupReport.Severity.ERROR);
        }
    }

    private static PluginTask lifecycleTask(PluginRuntime runtime, String taskType) {
        return new PluginTask(taskType, runtime.id(), runtime.descriptor().name(), "none");
    }

    public void shutdown(String id) {
        PluginRuntime runtime = plugins.get(id);
        if (runtime == null) return;
        scheduler.dispatch(lifecycleTask(runtime, "lifecycle.shutdown"), () -> shutdownRuntime(runtime));
    }

    private void shutdownRuntime(PluginRuntime runtime) {
        try {
            runtime.instance().shutdown();
            if (runtime.context() != null) {
                runtime.context().disposableScope().close();
            }
            runtime.transitionTo(PluginLifecycleState.SHUTDOWN);
            runtime.transitionTo(PluginLifecycleState.UNLOADED);
        } catch (Exception e) {
            runtime.transitionTo(PluginLifecycleState.SHUTDOWN_FAILED);
            report.addProblem("SHUTDOWN_FAILED", e.getMessage(), runtime.id(), StartupReport.Severity.ERROR);
        }
    }

    public void shutdownAll() {
        for (String id : plugins.keySet()) {
            shutdown(id);
        }
    }

    public void markDisabled(String id, DisabledReason reason) {
        PluginRuntime runtime = plugins.get(id);
        if (runtime == null) return;
        runtime.markDisabled(reason);
        report.addProblem(reason.name(), "Plugin disabled: " + reason, id, StartupReport.Severity.ERROR);
    }
}
