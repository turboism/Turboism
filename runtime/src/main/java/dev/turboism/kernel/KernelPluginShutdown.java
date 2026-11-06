package dev.turboism.kernel;

import dev.turboism.adapter.cubism.lifecycle.EditorObjectHookRegistry;
import dev.turboism.adapter.cubism.lifecycle.ParameterHookRegistry;
import dev.turboism.adapter.cubism.lifecycle.PartHookRegistry;
import dev.turboism.adapter.cubism.lifecycle.ProjectLifecycleHookRegistry;
import dev.turboism.core.lifecycle.PluginLifecycleState;
import dev.turboism.core.runtime.work.FatalErrors;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Coordinates reverse-order plugin shutdown and failure fallback reporting.
 *
 * <p>Every plugin's disable/shutdown/unload sequence runs as one task on the shared bounded
 * {@link PluginLifecycleLane} under {@link PluginLifecyclePolicy#closeTimeout()}. Closing starts
 * with an immediate non-blocking fence on the caller thread — event admission ends via
 * {@code beginClosing}, the guarded context denies new non-terminal calls, and the scope seals —
 * so no plugin thread can admit work while teardown runs. Destructive stages defer until admitted
 * SDK calls drain; a timed-out or quiescence-blocked close is retained and re-driven by the
 * bounded retention watcher with at-most-once phase semantics.</p>
 */
final class KernelPluginShutdown {

    private final KernelLog log;
    private final LocalPluginRuntime.PluginCloseHook closeHook;
    private final KernelPluginShutdownStages stages;
    private final ParameterHookRegistry parameterHookRegistry;
    private final PartHookRegistry partHookRegistry;
    private final EditorObjectHookRegistry editorObjectHookRegistry;
    private final ProjectLifecycleHookRegistry projectLifecycleHookRegistry;
    private final PluginLifecycleLane lane;
    private final PluginLifecyclePolicy policy;
    private final RetainedPluginGenerations retention;
    private final PluginLifecycleEvents lifecycleEvents;
    private final AdmittedGeneration.Admission admission;

    KernelPluginShutdown(
            final KernelLog log,
            final LocalPluginRuntime.PluginCloseHook closeHook,
            final ParameterHookRegistry parameterHookRegistry,
            final PartHookRegistry partHookRegistry,
            final EditorObjectHookRegistry editorObjectHookRegistry,
            final ProjectLifecycleHookRegistry projectLifecycleHookRegistry,
            final PluginLifecycleLane lane,
            final PluginLifecyclePolicy policy,
            final RetainedPluginGenerations retention,
            final PluginLifecycleEvents lifecycleEvents) {
        this.log = log;
        this.closeHook = closeHook;
        this.stages = new KernelPluginShutdownStages(log);
        this.parameterHookRegistry = java.util.Objects.requireNonNull(parameterHookRegistry, "parameterHookRegistry");
        this.partHookRegistry = java.util.Objects.requireNonNull(partHookRegistry, "partHookRegistry");
        this.editorObjectHookRegistry =
                java.util.Objects.requireNonNull(editorObjectHookRegistry, "editorObjectHookRegistry");
        this.projectLifecycleHookRegistry =
                java.util.Objects.requireNonNull(projectLifecycleHookRegistry, "projectLifecycleHookRegistry");
        this.lane = java.util.Objects.requireNonNull(lane, "lane");
        this.policy = java.util.Objects.requireNonNull(policy, "policy");
        this.retention = java.util.Objects.requireNonNull(retention, "retention");
        this.lifecycleEvents = java.util.Objects.requireNonNull(lifecycleEvents, "lifecycleEvents");
        this.admission = new AdmittedGeneration.Admission(policy, lane, retention, log);
    }

    /**
     * Drops every hook registration attributed to one event-owner key. The framework shell
     * closes outside the plugin machinery but shares this safety net.
     */
    void unregisterOwnedHooks(final dev.turboism.core.event.PluginEventOwnerKey key) {
        projectLifecycleHookRegistry.unregister(key);
        editorObjectHookRegistry.unregister(key);
        partHookRegistry.unregister(key);
        parameterHookRegistry.unregister(key);
    }

    List<LocalPluginRuntime.LoadedPluginSummary> closeAll(final List<LocalPluginRuntime.LoadedPlugin> loaded) {
        final List<LocalPluginRuntime.LoadedPluginSummary> summaries = new ArrayList<>();
        for (int index = loaded.size() - 1; index >= 0; index--) {
            closeOne(loaded.get(index), summaries);
        }
        return summaries;
    }

    /**
     * Dependency-rollback seam: closes one already-loaded plugin through the same bounded path as
     * {@link #closeAll}. Pair with {@link #fence(List)} — fence first so the affected generation
     * stops admitting work before any close wait begins.
     *
     * @return the close summary; the caller owns removing the plugin from live visibility
     */
    LocalPluginRuntime.LoadedPluginSummary unloadOne(final LocalPluginRuntime.LoadedPlugin loadedPlugin) {
        final List<LocalPluginRuntime.LoadedPluginSummary> summaries = new ArrayList<>(1);
        closeOne(loadedPlugin, summaries);
        return summaries.get(0);
    }

    /**
     * Immediately fences every listed generation without waiting: event admission ends, the
     * guarded SDK context denies new calls, and the scope seals. Safe while lifecycle work is in
     * flight; idempotent. Callers then remove the plugins from live visibility and close them
     * through {@link #unloadOne} in dependent-first order.
     */
    void fence(final List<LocalPluginRuntime.LoadedPlugin> plugins) {
        for (LocalPluginRuntime.LoadedPlugin loadedPlugin : plugins) {
            fenceFully(loadedPlugin);
        }
    }

    /** Immediate non-blocking full fence: events, SDK admission, and scope registrations. */
    private void fenceFully(final LocalPluginRuntime.LoadedPlugin loadedPlugin) {
        final String id = safePluginId(loadedPlugin);
        try {
            loadedPlugin.eventOwner().beginClosing();
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            log.error(id, "Plugin event owner fencing failed safely", failure);
        }
        // Capture the executor set while admission is fenced: at this point the registry entry
        // still belongs to this generation, so the terminal close tail can release exactly the
        // set this generation used — never a replacement claimed by a newer generation.
        try {
            loadedPlugin.eventOwner().claimExecutors();
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            log.error(id, "Plugin executor claim failed safely", failure);
        }
        final PluginGenerationGuard guard = loadedPlugin.guard();
        if (guard != null) {
            guard.fence();
        }
        final dev.turboism.sdk.plugin.DisposableScope scope = loadedPlugin.scope();
        if (scope != null) {
            scope.seal();
        }
    }

    private void closeOne(
            final LocalPluginRuntime.LoadedPlugin loadedPlugin,
            final List<LocalPluginRuntime.LoadedPluginSummary> summaries) {
        final String id = safePluginId(loadedPlugin);
        final long generation = generation(loadedPlugin);
        final PluginCloseContent content = new PluginCloseContent(
                loadedPlugin,
                stages,
                parameterHookRegistry,
                partHookRegistry,
                editorObjectHookRegistry,
                projectLifecycleHookRegistry,
                closeHook,
                log);
        final AdmittedGeneration.Handle admitted = AdmittedGeneration.adopt(content, admission);
        // Full non-blocking fence at close admission — before any wait and before the task is
        // even submitted: event admission ends, the guarded context denies new non-terminal
        // calls on every previously acquired handle, and the scope seals. Plugin teardown code
        // still works through terminal operations (close/cancel/dispose/release) and
        // diagnostics; destructive stages only run after admitted calls drain.
        admitted.fence(AdmittedGeneration.Cause.CLOSE);
        final AtomicBoolean verdictPublished = new AtomicBoolean();
        final AdmittedGeneration.CloseAttempt attempt = admitted.close(() -> true);
        switch (attempt.outcome()) {
            case SUCCEEDED -> {
                final AdmittedGeneration.TeardownReport report = attempt.report();
                final PreviewPluginShutdownResult result;
                if (report.deferred()) {
                    result = content.report().deferred(deferredFailures(content, report));
                } else {
                    result = content.report().result(content.failures());
                }
                summaries.add(KernelPluginSummaryFactory.create(
                        loadedPlugin,
                        result.disableState(),
                        result.shutdownState(),
                        result.unloadState(),
                        result.scopeCleanupState(),
                        result.classloaderCleanupState(),
                        result.failures()));
                if (report.deferred()) {
                    // UNLOAD/SUCCEEDED is deferred until the retained cleanup
                    // actually completes — never while it is still incomplete.
                    retainClose(admitted, attempt.workerDone(), content, id, generation, verdictPublished);
                } else if (unloadSucceeded(result)) {
                    verdictPublished.set(true);
                    lifecycleEvents.unloaded(id, generation);
                } else {
                    // The task returned but a disable/shutdown/scope/loader stage
                    // recorded a permanent failure — the unload is not a success.
                    verdictPublished.set(true);
                    lifecycleEvents.unloadFailed(id, generation);
                    if (!report.disposalProven()) {
                        // Scope/classloader disposal was never proven: the failed
                        // verdict is sticky but the generation stays retained so
                        // its references are not released on an unverified cleanup.
                        retainClose(admitted, attempt.workerDone(), content, id, generation, verdictPublished);
                    }
                }
            }
            case FAILED -> {
                summaries.add(fallbackSummary(loadedPlugin));
                verdictPublished.set(true);
                lifecycleEvents.unloadFailed(id, generation);
                // The FAILED verdict is final, but a stage that threw may never have reached
                // scope/classloader disposal. The retained re-drive runs the remaining one-shot
                // stages and never publishes a second verdict.
                retainClose(admitted, attempt.workerDone(), content, id, generation, verdictPublished);
                tryLogStableFailure(id, "PLUGIN_CLOSE_STAGE_FAILED");
            }
            case TIMED_OUT -> {
                // The worker may still be inside plugin teardown; the admission fence is
                // already up, so report the timeout and let retention finish the close once
                // the worker exits and admitted calls drain.
                summaries.add(timeoutSummary(loadedPlugin));
                lifecycleEvents.unloadTimedOut(id, generation);
                retainClose(admitted, attempt.workerDone(), content, id, generation, verdictPublished);
                tryLogStableFailure(id, "PLUGIN_CLOSE_TIMEOUT");
            }
            case REJECTED -> {
                // The close task never ran; no verdict yet — retention emits UNLOAD when
                // the deferred close actually completes.
                summaries.add(fallbackSummary(loadedPlugin));
                retainClose(admitted, attempt.workerDone(), content, id, generation, verdictPublished);
                tryLogStableFailure(id, "PLUGIN_CLOSE_LANE_REJECTED");
            }
        }
    }

    /** Stage failures of the pass plus the gate that ended it, in report order. */
    private static List<LocalPluginRuntime.PluginSummaryFailure> deferredFailures(
            final PluginCloseContent content, final AdmittedGeneration.TeardownReport report) {
        final List<LocalPluginRuntime.PluginSummaryFailure> all = new ArrayList<>(content.failures());
        all.addAll(content.recordDeferral(report.gate()));
        return List.copyOf(all);
    }

    /**
     * A close is a genuine unload success only when the runtime reached UNLOADED and no
     * stage recorded a failure — a thrown disable/shutdown body or a failed scope/loader
     * close still ends in a returned task result, so task completion alone is not a verdict.
     */
    private static boolean unloadSucceeded(final PreviewPluginShutdownResult result) {
        return "SUCCEEDED".equals(result.unloadState())
                && !"FAILED".equals(result.disableState())
                && !"FAILED".equals(result.shutdownState())
                && !"FAILED".equals(result.scopeCleanupState())
                && !"FAILED".equals(result.classloaderCleanupState())
                && result.failures().isEmpty();
    }

    /**
     * Retains a generation whose close did not finish inside its deadline. The watcher re-drives
     * the idempotent close on the lane once the in-flight worker exits, event callbacks quiesce
     * and admitted SDK calls drain; {@link RetainedPluginGenerations} performs no plugin work on
     * its own thread.
     */
    private void retainClose(
            final AdmittedGeneration.Handle admitted,
            final CompletableFuture<Void> workerDone,
            final PluginCloseContent content,
            final String id,
            final long generation,
            final AtomicBoolean verdictPublished) {
        admitted.retain(
                workerDone,
                AdmittedGeneration.Cause.CLOSE,
                // An external plugin close never waits for other generations: only the
                // runtime-owned shell close is ordered after plugin teardown.
                () -> true,
                report -> publishDeferredVerdict(content, id, generation, report, verdictPublished));
    }

    /**
     * Publishes the verdict a deferred close still owes once a re-driven pass genuinely finishes.
     * The terminal verdict is published at most once across re-drives: a close that already
     * reported FAILED or TIMED_OUT never gains a contradicting second row.
     */
    private void publishDeferredVerdict(
            final PluginCloseContent content,
            final String id,
            final long generation,
            final AdmittedGeneration.TeardownReport report,
            final AtomicBoolean verdictPublished) {
        if (report.deferred()) {
            // Still gated: no verdict until the deferred close can actually finish.
            return;
        }
        final boolean unloaded = unloadSucceeded(content.report().result(content.failures()));
        if (!verdictPublished.compareAndSet(false, true)) {
            return;
        }
        if (unloaded) {
            lifecycleEvents.unloaded(id, generation);
        } else {
            lifecycleEvents.unloadFailed(id, generation);
        }
    }

    int retainedGenerationCount() {
        return retention.retainedCount();
    }

    private static long generation(final LocalPluginRuntime.LoadedPlugin loadedPlugin) {
        return loadedPlugin.eventOwner().key().generation();
    }

    private LocalPluginRuntime.LoadedPluginSummary fallbackSummary(final LocalPluginRuntime.LoadedPlugin loadedPlugin) {
        try {
            projectLifecycleHookRegistry.unregister(loadedPlugin.eventOwner().key());
            editorObjectHookRegistry.unregister(loadedPlugin.eventOwner().key());
            partHookRegistry.unregister(loadedPlugin.eventOwner().key());
            parameterHookRegistry.unregister(loadedPlugin.eventOwner().key());
            closeHook.run(safePluginId(loadedPlugin), "fallback-summary");
            loadedPlugin.runtime().transitionTo(PluginLifecycleState.SHUTDOWN_FAILED);
        } catch (Throwable ignored) {
            FatalErrors.rethrowIfFatal(ignored);
            // The final summary must remain available when its fallback hook fails.
        }
        return fallbackSummaryWithoutRuntimeMutation(loadedPlugin);
    }

    private LocalPluginRuntime.LoadedPluginSummary timeoutSummary(final LocalPluginRuntime.LoadedPlugin loadedPlugin) {
        try {
            closeHook.run(safePluginId(loadedPlugin), "timeout-summary");
            loadedPlugin.runtime().transitionTo(PluginLifecycleState.SHUTDOWN_FAILED);
        } catch (Throwable ignored) {
            FatalErrors.rethrowIfFatal(ignored);
            // The final summary must remain available when its fallback hook fails.
        }
        return KernelPluginSummaryFactory.create(
                loadedPlugin,
                "NOT_STARTED",
                "NOT_STARTED",
                "NOT_STARTED",
                "NOT_STARTED",
                "NOT_STARTED",
                List.of(new LocalPluginRuntime.PluginSummaryFailure(
                        "PLUGIN_CLOSE_TIMEOUT",
                        "close",
                        "Plugin close exceeded its deadline; generation retained for deferred cleanup.")));
    }

    private static LocalPluginRuntime.LoadedPluginSummary fallbackSummaryWithoutRuntimeMutation(
            final LocalPluginRuntime.LoadedPlugin loadedPlugin) {
        return KernelPluginSummaryFactory.create(
                loadedPlugin,
                "NOT_STARTED",
                "NOT_STARTED",
                "NOT_STARTED",
                "NOT_STARTED",
                "NOT_STARTED",
                List.of(new LocalPluginRuntime.PluginSummaryFailure(
                        "PLUGIN_CLOSE_STAGE_FAILED", "close", "Plugin close stage failed safely.")));
    }

    void tryLogStableFailure(final String component, final String code) {
        try {
            closeHook.run(component, "fallback-log");
            logStableFailure(component, code);
        } catch (Throwable ignored) {
            FatalErrors.rethrowIfFatal(ignored);
            // A failed fallback logger must not prevent the remaining shutdown.
        }
    }

    private void logStableFailure(final String component, final String code) {
        log.error(component, "Runtime shutdown stage failed safely: " + code, new IllegalStateException(code));
    }

    private static String safePluginId(final LocalPluginRuntime.LoadedPlugin loadedPlugin) {
        try {
            return loadedPlugin.runtime().id();
        } catch (Throwable ignored) {
            FatalErrors.rethrowIfFatal(ignored);
            return "plugin";
        }
    }
}
