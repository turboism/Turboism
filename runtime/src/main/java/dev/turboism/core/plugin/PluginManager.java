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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Legacy standalone JAR lifecycle coordinator retained for internal compatibility.
 * The production loader and shutdown path are owned by {@code dev.turboism.preview.LocalPluginRuntime};
 * changes here do not replace verification of that production path.
 */
public final class PluginManager {

    private final Map<String, PluginRuntime> plugins = new HashMap<>();
    private final ConcurrentMap<String, CompletableFuture<PluginLifecycleState>> disableOperations =
        new ConcurrentHashMap<>();
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
     * {@link PluginLifecycleState#DISABLE_FAILED} rather than {@code DISABLED}. Entrypoint teardown,
     * scope closure, the terminal state transition, and completion of the returned stage all run in
     * the one lifecycle task accepted by the runtime scheduler. Concurrent disable calls share that
     * task and its result.
     *
     * <p>If the scheduler rejects the lifecycle task, the returned stage fails with a stable
     * {@link IllegalStateException}; no entrypoint or scope teardown runs and the plugin remains
     * enabled so callers cannot mistake rejected work for a completed disable.
     *
     * @param id the plugin to disable
     * @return a stage completed with the terminal lifecycle state; an unknown plugin completes with
     *     {@code null}, and a plugin that is not enabled completes with its current state
     */
    public CompletionStage<PluginLifecycleState> disable(final String id) {
        final PluginRuntime runtime = plugins.get(id);
        if (runtime == null) {
            return CompletableFuture.completedFuture(null);
        }
        if (runtime.state() != PluginLifecycleState.ENABLED) {
            return CompletableFuture.completedFuture(runtime.state());
        }

        final CompletableFuture<PluginLifecycleState> candidate = new CompletableFuture<>();
        final CompletableFuture<PluginLifecycleState> active =
            disableOperations.putIfAbsent(runtime.id(), candidate);
        if (active != null) {
            return active;
        }
        if (runtime.state() != PluginLifecycleState.ENABLED) {
            disableOperations.remove(runtime.id(), candidate);
            candidate.complete(runtime.state());
            return candidate;
        }

        final boolean accepted = scheduler.dispatch(
            lifecycleTask(runtime, "lifecycle.disable"),
            () -> disableRuntime(runtime, candidate)
        );
        if (!accepted) {
            final IllegalStateException rejection = new IllegalStateException(
                "Plugin lifecycle disable dispatch was rejected"
            );
            try {
                reportProblem(runtime, "DISABLE_FAILED", rejection);
            } finally {
                candidate.completeExceptionally(rejection);
                disableOperations.remove(runtime.id(), candidate);
            }
        }
        return candidate;
    }

    private void disableRuntime(
        final PluginRuntime runtime,
        final CompletableFuture<PluginLifecycleState> completion
    ) {
        Throwable failure = null;
        try {
            try {
                logInfo(runtime, "Plugin lifecycle: disable started");
            } catch (Throwable logging) {
                failure = recordDisableFailure(runtime, failure, logging);
            }
            final List<TurboismPlugin> entries = runtime.entrypoints();
            for (int index = entries.size() - 1; index >= 0; index--) {
                try {
                    entries.get(index).disable();
                } catch (Throwable next) {
                    failure = recordDisableFailure(runtime, failure, next);
                }
            }
        } catch (Throwable unexpected) {
            failure = recordDisableFailure(runtime, failure, unexpected);
        } finally {
            try {
                if (runtime.context() != null) runtime.context().disposableScope().close();
            } catch (Throwable cleanup) {
                failure = recordDisableFailure(runtime, failure, cleanup);
            }
            PluginLifecycleState terminalState = failure == null
                ? PluginLifecycleState.DISABLED : PluginLifecycleState.DISABLE_FAILED;
            try {
                runtime.transitionTo(terminalState);
                if (failure == null) {
                    logInfo(runtime, "Plugin lifecycle: disable succeeded entrypoints=" + runtime.entrypoints().size());
                }
            } catch (Throwable settlement) {
                failure = appendDisableFailure(failure, settlement);
                terminalState = PluginLifecycleState.DISABLE_FAILED;
                runtime.transitionTo(terminalState);
            } finally {
                try {
                    if (fatal(failure)) completion.completeExceptionally(failure);
                    else completion.complete(terminalState);
                } finally {
                    disableOperations.remove(runtime.id(), completion);
                }
            }
        }
        if (fatal(failure)) throw (Error) failure;
    }

    private Throwable recordDisableFailure(
        final PluginRuntime runtime, final Throwable first, final Throwable next
    ) {
        Throwable result = appendDisableFailure(first, next);
        try {
            reportProblem(runtime, "DISABLE_FAILED", next);
        } catch (Throwable diagnosticsFailure) {
            result = appendDisableFailure(result, diagnosticsFailure);
        }
        return result;
    }

    private static Throwable appendDisableFailure(final Throwable first, final Throwable next) {
        if (first == null) return next;
        if (first == next) return first;
        if (fatal(next) && !fatal(first)) {
            next.addSuppressed(first);
            return next;
        }
        first.addSuppressed(next);
        return first;
    }

    private static boolean fatal(final Throwable failure) {
        return failure instanceof VirtualMachineError || failure instanceof ThreadDeath;
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
            return false;
        }
    }

    private void reportProblem(
        final PluginRuntime runtime,
        final String code,
        final Throwable exception
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
