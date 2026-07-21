package dev.turboism.core.plugin;

import dev.turboism.core.diagnostics.DisabledReason;
import dev.turboism.core.diagnostics.StartupReport;
import dev.turboism.core.lifecycle.PluginLifecycleState;
import dev.turboism.core.runtime.PluginTask;
import dev.turboism.core.runtime.RuntimeScheduler;
import dev.turboism.sdk.plugin.TurboismPlugin;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Manages JAR-level plugin lifecycle across ordered entrypoint instances. */
public final class PluginManager {

    private final Map<String, PluginRuntime> plugins = new HashMap<>();
    private final StartupReport report = new StartupReport();
    private final RuntimeScheduler scheduler;

    public PluginManager(final RuntimeScheduler scheduler) {
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
    }

    public PluginRuntime get(final String id) {
        return plugins.get(id);
    }

    public Map<String, PluginRuntime> plugins() {
        return Collections.unmodifiableMap(plugins);
    }

    public StartupReport report() {
        return report;
    }

    public PluginRuntime registerDescriptor(final PluginRuntime runtime) {
        plugins.put(runtime.id(), runtime);
        return runtime;
    }

    public void enable(final String id) {
        final PluginRuntime runtime = plugins.get(id);
        if (runtime == null) {
            return;
        }
        scheduler.dispatch(lifecycleTask(runtime, "lifecycle.enable"), () -> enableRuntime(runtime));
    }

    private void enableRuntime(final PluginRuntime runtime) {
        int enabled = 0;
        try {
            for (TurboismPlugin entrypoint : runtime.entrypoints()) {
                entrypoint.enable();
                enabled++;
            }
            runtime.transitionTo(PluginLifecycleState.ENABLED);
        } catch (Exception exception) {
            disablePrefixReverse(runtime.entrypoints(), enabled, runtime);
            closeDisposableScope(runtime, "ENABLE_FAILED");
            reportProblem(runtime, "ENABLE_FAILED", exception);
            runtime.transitionTo(PluginLifecycleState.ENABLE_FAILED);
        }
    }

    public void disable(final String id) {
        final PluginRuntime runtime = plugins.get(id);
        if (runtime == null || runtime.state() != PluginLifecycleState.ENABLED) {
            return;
        }
        scheduler.dispatch(lifecycleTask(runtime, "lifecycle.disable"), () -> disableRuntime(runtime));
        if (!closeDisposableScope(runtime, "DISABLE_FAILED")) {
            return;
        }
        if (runtime.state() == PluginLifecycleState.ENABLED) {
            runtime.transitionTo(PluginLifecycleState.DISABLED);
        }
    }

    private void disableRuntime(final PluginRuntime runtime) {
        boolean failed = false;
        final List<TurboismPlugin> entries = runtime.entrypoints();
        for (int index = entries.size() - 1; index >= 0; index--) {
            try {
                entries.get(index).disable();
            } catch (Exception exception) {
                failed = true;
                reportProblem(runtime, "DISABLE_FAILED", exception);
            }
        }
        if (failed) {
            runtime.transitionTo(PluginLifecycleState.DISABLE_FAILED);
        }
    }

    private static PluginTask lifecycleTask(
        final PluginRuntime runtime,
        final String taskType
    ) {
        return new PluginTask(taskType, runtime.id(), runtime.descriptor().name(), "none");
    }

    public void shutdown(final String id) {
        final PluginRuntime runtime = plugins.get(id);
        if (runtime == null) {
            return;
        }
        scheduler.dispatch(lifecycleTask(runtime, "lifecycle.shutdown"), () -> shutdownRuntime(runtime));
    }

    private void shutdownRuntime(final PluginRuntime runtime) {
        boolean failed = false;
        final List<TurboismPlugin> entries = runtime.entrypoints();
        for (int index = entries.size() - 1; index >= 0; index--) {
            try {
                entries.get(index).shutdown();
            } catch (Exception exception) {
                failed = true;
                reportProblem(runtime, "SHUTDOWN_FAILED", exception);
            }
        }
        if (!closeDisposableScope(runtime, "SHUTDOWN_FAILED")) {
            failed = true;
        }
        if (failed) {
            runtime.transitionTo(PluginLifecycleState.SHUTDOWN_FAILED);
            return;
        }
        runtime.transitionTo(PluginLifecycleState.SHUTDOWN);
        runtime.transitionTo(PluginLifecycleState.UNLOADED);
    }

    private void disablePrefixReverse(
        final List<TurboismPlugin> entries,
        final int count,
        final PluginRuntime runtime
    ) {
        for (int index = count - 1; index >= 0; index--) {
            try {
                entries.get(index).disable();
            } catch (Exception exception) {
                reportProblem(runtime, "ENABLE_ROLLBACK_FAILED", exception);
            }
        }
    }

    private boolean closeDisposableScope(
        final PluginRuntime runtime,
        final String failureCode
    ) {
        if (runtime.context() == null) {
            return true;
        }
        try {
            runtime.context().disposableScope().close();
            return true;
        } catch (Exception exception) {
            reportProblem(runtime, failureCode, exception);
            runtime.transitionTo(PluginLifecycleState.valueOf(failureCode));
            return false;
        }
    }

    private void reportProblem(
        final PluginRuntime runtime,
        final String code,
        final Exception exception
    ) {
        if (runtime.context() != null) {
            runtime.context().logger().error(code + ": " + exception.getMessage(), exception);
        }
        report.addProblem(
            code,
            exception.getMessage(),
            runtime.id(),
            StartupReport.Severity.ERROR
        );
    }

    public void shutdownAll() {
        for (String id : plugins.keySet()) {
            shutdown(id);
        }
    }

    public void markDisabled(final String id, final DisabledReason reason) {
        final PluginRuntime runtime = plugins.get(id);
        if (runtime == null) {
            return;
        }
        runtime.markDisabled(reason);
        report.addProblem(
            reason.name(),
            "Plugin disabled: " + reason,
            id,
            StartupReport.Severity.ERROR
        );
    }
}
