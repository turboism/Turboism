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

    /**
     * @param id the plugin id to look up
     * @return the registered runtime, or {@code null} if no plugin with that id has been registered
     */
    public PluginRuntime get(final String id) {
        return plugins.get(id);
    }

    /**
     * @return an unmodifiable live view of the registered plugins keyed by id. It reflects later
     *         registrations rather than being a snapshot, so it must not be iterated while
     *         registration can occur.
     */
    public Map<String, PluginRuntime> plugins() {
        return Collections.unmodifiableMap(plugins);
    }

    /**
     * @return the shared startup report this manager appends lifecycle problems to; the same mutable
     *         instance on every call, not a copy
     */
    public StartupReport report() {
        return report;
    }

    /**
     * Registers a plugin runtime under its own id, replacing any runtime already registered under
     * that id without disabling or shutting the old one down.
     *
     * @param runtime the runtime to register
     * @return the same {@code runtime}, for chaining
     */
    public PluginRuntime registerDescriptor(final PluginRuntime runtime) {
        plugins.put(runtime.id(), runtime);
        return runtime;
    }

    /**
     * Enables a plugin by calling {@code enable()} on each of its entrypoints in declaration order.
     *
     * <p>An unknown id is silently ignored. The work is dispatched through the runtime scheduler, so
     * it may not have completed when this returns. If any entrypoint throws, the already-enabled
     * prefix is disabled in reverse order, the plugin's disposable scope is closed, an
     * {@code ENABLE_FAILED} problem is recorded on the startup report, and the plugin ends in
     * {@link PluginLifecycleState#ENABLE_FAILED} - a partially enabled plugin is never left running.
     *
     * @param id the plugin to enable
     */
    public void enable(final String id) {
        final PluginRuntime runtime = plugins.get(id);
        if (runtime == null) {
            return;
        }
        scheduler.dispatch(lifecycleTask(runtime, "lifecycle.enable"), () -> enableRuntime(runtime));
    }

    private void enableRuntime(final PluginRuntime runtime) {
        logInfo(runtime, "Plugin lifecycle: enable started");
        int enabled = 0;
        try {
            for (TurboismPlugin entrypoint : runtime.entrypoints()) {
                entrypoint.enable();
                enabled++;
            }
            runtime.transitionTo(PluginLifecycleState.ENABLED);
            logInfo(runtime, "Plugin lifecycle: enable succeeded entrypoints=" + enabled);
        } catch (Exception exception) {
            disablePrefixReverse(runtime.entrypoints(), enabled, runtime);
            closeDisposableScope(runtime, "ENABLE_FAILED");
            reportProblem(runtime, "ENABLE_FAILED", exception);
            runtime.transitionTo(PluginLifecycleState.ENABLE_FAILED);
        }
    }

    /**
     * Disables a plugin by calling {@code disable()} on its entrypoints in reverse declaration order
     * and closing its disposable scope.
     *
     * <p>Ignores an unknown id and any plugin not currently in
     * {@link PluginLifecycleState#ENABLED}. Entrypoint failures do not abort the pass: every
     * entrypoint is still invoked, each failure is reported, and the plugin ends in
     * {@link PluginLifecycleState#DISABLE_FAILED} rather than {@code DISABLED}. The entrypoint calls
     * are dispatched through the runtime scheduler.
     *
     * @param id the plugin to disable
     */
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
        logInfo(runtime, "Plugin lifecycle: disable started");
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
            return;
        }
        logInfo(runtime, "Plugin lifecycle: disable succeeded entrypoints=" + entries.size());
    }

    private static PluginTask lifecycleTask(
        final PluginRuntime runtime,
        final String taskType
    ) {
        return new PluginTask(taskType, runtime.id(), runtime.descriptor().name(), "none");
    }

    /**
     * Shuts a plugin down by calling {@code shutdown()} on its entrypoints in reverse declaration
     * order.
     *
     * <p>An unknown id is silently ignored. The work is dispatched through the runtime scheduler.
     * Entrypoint failures are reported and do not stop the remaining entrypoints from being shut
     * down.
     *
     * @param id the plugin to shut down
     */
    public void shutdown(final String id) {
        final PluginRuntime runtime = plugins.get(id);
        if (runtime == null) {
            return;
        }
        scheduler.dispatch(lifecycleTask(runtime, "lifecycle.shutdown"), () -> shutdownRuntime(runtime));
    }

    private void shutdownRuntime(final PluginRuntime runtime) {
        logInfo(runtime, "Plugin lifecycle: shutdown started");
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
        logInfo(runtime, "Plugin lifecycle: shutdown succeeded entrypoints=" + entries.size());
        runtime.transitionTo(PluginLifecycleState.UNLOADED);
        logInfo(runtime, "Plugin lifecycle: unload succeeded");
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
            runtime.context().logger().error(
                "Plugin lifecycle stage failed: " + code,
                exception
            );
        }
        report.addProblem(
            code,
            exception.getMessage(),
            runtime.id(),
            StartupReport.Severity.ERROR
        );
    }

    private static void logInfo(
        final PluginRuntime runtime,
        final String message
    ) {
        if (runtime.context() != null) {
            runtime.context().logger().info(message);
        }
    }

    /**
     * Shuts down every registered plugin. Ordering between plugins follows the registration map's
     * iteration order and is not a dependency order; each plugin's own entrypoints are still shut
     * down in reverse declaration order.
     */
    public void shutdownAll() {
        for (String id : plugins.keySet()) {
            shutdown(id);
        }
    }

    /**
     * Records that a plugin is disabled for an out-of-band reason - a failed gate rather than a
     * lifecycle transition - and adds an error to the startup report.
     *
     * <p>This marks state only: it does not call {@code disable()} on any entrypoint. An unknown id
     * is silently ignored.
     *
     * @param id the plugin to mark
     * @param reason why it is disabled; its name becomes the reported problem code
     */
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
