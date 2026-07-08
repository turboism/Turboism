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

    public void enable(String id) {
        PluginRuntime runtime = plugins.get(id);
        if (runtime == null) return;
        scheduler.dispatch(lifecycleTask(runtime, "lifecycle.enable"), () -> enableRuntime(runtime));
    }

    private void enableRuntime(PluginRuntime runtime) {
        try {
            runtime.instance().enable();
            runtime.transitionTo(PluginLifecycleState.ENABLED);
        } catch (Exception e) {
            closeDisposableScope(runtime, "ENABLE_FAILED");
            reportProblem(runtime, "ENABLE_FAILED", e);
            runtime.transitionTo(PluginLifecycleState.ENABLE_FAILED);
        }
    }

    public void disable(String id) {
        PluginRuntime runtime = plugins.get(id);
        if (runtime == null) return;
        if (runtime.state() != PluginLifecycleState.ENABLED) return;
        scheduler.dispatch(lifecycleTask(runtime, "lifecycle.disable"), () -> disableRuntime(runtime));
        if (!closeDisposableScope(runtime, "DISABLE_FAILED")) return;
        if (runtime.state() == PluginLifecycleState.ENABLED) {
            runtime.transitionTo(PluginLifecycleState.DISABLED);
        }
    }

    private void disableRuntime(PluginRuntime runtime) {
        try {
            runtime.instance().disable();
        } catch (Exception e) {
            reportProblem(runtime, "DISABLE_FAILED", e);
            runtime.transitionTo(PluginLifecycleState.DISABLE_FAILED);
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
            if (runtime.instance() != null) {
                runtime.instance().shutdown();
            }
            if (!closeDisposableScope(runtime, "SHUTDOWN_FAILED")) return;
            runtime.transitionTo(PluginLifecycleState.SHUTDOWN);
            runtime.transitionTo(PluginLifecycleState.UNLOADED);
        } catch (Exception e) {
            closeDisposableScope(runtime, "SHUTDOWN_FAILED");
            reportProblem(runtime, "SHUTDOWN_FAILED", e);
            runtime.transitionTo(PluginLifecycleState.SHUTDOWN_FAILED);
        }
    }

    private boolean closeDisposableScope(PluginRuntime runtime, String failureCode) {
        if (runtime.context() == null) {
            return true;
        }
        try {
            runtime.context().disposableScope().close();
            return true;
        } catch (Exception e) {
            reportProblem(runtime, failureCode, e);
            runtime.transitionTo(PluginLifecycleState.valueOf(failureCode));
            return false;
        }
    }

    private void reportProblem(PluginRuntime runtime, String code, Exception e) {
        if (runtime.context() != null) {
            runtime.context().logger().error(code + ": " + e.getMessage(), e);
        }
        report.addProblem(code, e.getMessage(), runtime.id(), StartupReport.Severity.ERROR);
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
