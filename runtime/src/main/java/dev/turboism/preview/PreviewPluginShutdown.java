package dev.turboism.preview;

import dev.turboism.core.lifecycle.PluginLifecycleState;
import dev.turboism.adapter.cubism.lifecycle.EditorObjectHookRegistry;
import dev.turboism.adapter.cubism.lifecycle.ParameterHookRegistry;
import dev.turboism.adapter.cubism.lifecycle.PartHookRegistry;
import dev.turboism.adapter.cubism.lifecycle.ProjectLifecycleHookRegistry;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

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
final class PreviewPluginShutdown {

    /** Failure codes that mean the close can make progress once outstanding work settles. */
    private static final java.util.Set<String> RETRYABLE_CODES = java.util.Set.of(
        "PLUGIN_BACKUP_QUIESCENCE_FAILED",
        "PLUGIN_SDK_DRAIN_FAILED"
    );

    private final PreviewLog log;
    private final LocalPluginRuntime.PluginCloseHook closeHook;
    private final PreviewPluginShutdownStages stages;
    private final ParameterHookRegistry parameterHookRegistry;
    private final PartHookRegistry partHookRegistry;
    private final EditorObjectHookRegistry editorObjectHookRegistry;
    private final ProjectLifecycleHookRegistry projectLifecycleHookRegistry;
    private final PluginLifecycleLane lane;
    private final PluginLifecyclePolicy policy;
    private final RetainedPluginGenerations retention;

    PreviewPluginShutdown(
        final PreviewLog log,
        final LocalPluginRuntime.PluginCloseHook closeHook,
        final ParameterHookRegistry parameterHookRegistry,
        final PartHookRegistry partHookRegistry,
        final EditorObjectHookRegistry editorObjectHookRegistry,
        final ProjectLifecycleHookRegistry projectLifecycleHookRegistry,
        final PluginLifecycleLane lane,
        final PluginLifecyclePolicy policy,
        final RetainedPluginGenerations retention
    ) {
        this.log = log;
        this.closeHook = closeHook;
        this.stages = new PreviewPluginShutdownStages(log);
        this.parameterHookRegistry = java.util.Objects.requireNonNull(
            parameterHookRegistry,
            "parameterHookRegistry"
        );
        this.partHookRegistry = java.util.Objects.requireNonNull(partHookRegistry, "partHookRegistry");
        this.editorObjectHookRegistry = java.util.Objects.requireNonNull(
            editorObjectHookRegistry,
            "editorObjectHookRegistry"
        );
        this.projectLifecycleHookRegistry = java.util.Objects.requireNonNull(
            projectLifecycleHookRegistry,
            "projectLifecycleHookRegistry"
        );
        this.lane = java.util.Objects.requireNonNull(lane, "lane");
        this.policy = java.util.Objects.requireNonNull(policy, "policy");
        this.retention = java.util.Objects.requireNonNull(retention, "retention");
    }

    List<LocalPluginRuntime.LoadedPluginSummary> closeAll(
        final List<LocalPluginRuntime.LoadedPlugin> loaded
    ) {
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
    LocalPluginRuntime.LoadedPluginSummary unloadOne(
        final LocalPluginRuntime.LoadedPlugin loadedPlugin
    ) {
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
            log.error(id, "Plugin event owner fencing failed safely", failure);
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
        final List<LocalPluginRuntime.LoadedPluginSummary> summaries
    ) {
        final String id = safePluginId(loadedPlugin);
        // Full non-blocking fence at close admission — before any wait and before the task is
        // even submitted: event admission ends, the guarded context denies new non-terminal
        // calls on every previously acquired handle, and the scope seals. Plugin teardown code
        // still works through terminal operations (close/cancel/dispose/release) and
        // diagnostics; destructive stages only run after admitted calls drain.
        fenceFully(loadedPlugin);
        final PreviewPluginShutdownStages.CloseProgress progress =
            new PreviewPluginShutdownStages.CloseProgress();
        final PluginLifecycleLease lease = new PluginLifecycleLease(id);
        final PluginLifecycleLane.Invocation<CloseOutcome> invocation =
            lane.submit(id, "close", () -> {
                try {
                    return closeLoadedPlugin(loadedPlugin, progress);
                } catch (Exception exception) {
                    throw exception;
                } catch (Throwable failure) {
                    // closeHook.run declares Throwable; Errors pass through, the rest wrap.
                    if (failure instanceof Error error) {
                        throw error;
                    }
                    throw new RuntimeException(failure);
                }
            });
        final PluginLifecycleLane.AwaitResult<CloseOutcome> result =
            lane.await(invocation, policy.closeTimeout(), lease);
        switch (result.outcome) {
            case SUCCEEDED -> {
                summaries.add(result.value.summary());
                if (result.value.retain()) {
                    retainClose(loadedPlugin, invocation.workerDone, progress);
                }
            }
            case FAILED -> {
                summaries.add(fallbackSummary(loadedPlugin));
                finalizeEventOwnerAfterFailure(loadedPlugin, invocation.workerDone, progress);
                tryLogStableFailure(id, "PLUGIN_CLOSE_STAGE_FAILED");
            }
            case TIMED_OUT -> {
                // The worker may still be inside plugin teardown; the admission fence is
                // already up, so report the timeout and let retention finish the close once
                // the worker exits and admitted calls drain.
                summaries.add(timeoutSummary(loadedPlugin));
                retainClose(loadedPlugin, invocation.workerDone, progress);
                tryLogStableFailure(id, "PLUGIN_CLOSE_TIMEOUT");
            }
            case REJECTED -> {
                summaries.add(fallbackSummary(loadedPlugin));
                retainClose(loadedPlugin, invocation.workerDone, progress);
                tryLogStableFailure(id, "PLUGIN_CLOSE_LANE_REJECTED");
            }
        }
    }

    /**
     * One close task on the lane: bounded event-quiescence wait, hook unregistration, then the
     * ordered disable/shutdown/scope/classloader stages.
     */
    private CloseOutcome closeLoadedPlugin(
        final LocalPluginRuntime.LoadedPlugin loadedPlugin,
        final PreviewPluginShutdownStages.CloseProgress progress
    ) throws Throwable {
        final String id = loadedPlugin.runtime().id();
        // Teardown is deliberately outside the EventBus lifecycle: once closing begins,
        // disable()/shutdown() cannot publish or add subscribers. This cancels queued
        // callbacks before plugin state starts disappearing and makes unload quiescence
        // authoritative rather than relying on each plugin to stop event traffic itself.
        // (beginClosing already ran on the caller thread; repeating it is idempotent and keeps
        // this method self-sufficient for retention re-drives.)
        loadedPlugin.eventOwner().beginClosing();
        final boolean eventQuiesced = loadedPlugin.eventOwner().awaitQuiescence(
            policy.eventQuiescenceTimeout()
        );
        projectLifecycleHookRegistry.unregister(loadedPlugin.eventOwner().key());
        editorObjectHookRegistry.unregister(loadedPlugin.eventOwner().key());
        partHookRegistry.unregister(loadedPlugin.eventOwner().key());
        parameterHookRegistry.unregister(loadedPlugin.eventOwner().key());
        closeHook.run(id, "close");
        final PreviewPluginShutdownResult result = stages.close(
            loadedPlugin, id, eventQuiesced, progress
        );
        final boolean retryableCleanup = !eventQuiesced
            || result.failures().stream().anyMatch(failure -> RETRYABLE_CODES.contains(
                failure.code()
            ));
        log.info(id, "Plugin unloaded with state " + loadedPlugin.runtime().state());
        if (eventQuiesced) {
            loadedPlugin.eventOwner().close();
        }
        return new CloseOutcome(
            PreviewPluginSummaryFactory.create(
                loadedPlugin, result.disableState(), result.shutdownState(), result.unloadState(),
                result.scopeCleanupState(), result.classloaderCleanupState(), result.failures()
            ),
            retryableCleanup
        );
    }

    /**
     * Retains a generation whose close did not finish inside its deadline. The watcher re-drives
     * the idempotent close on the lane once the in-flight worker exits, event callbacks quiesce
     * and admitted SDK calls drain; {@link RetainedPluginGenerations} performs no plugin work on
     * its own thread.
     */
    private void retainClose(
        final LocalPluginRuntime.LoadedPlugin loadedPlugin,
        final java.util.concurrent.CompletableFuture<Void> workerDone,
        final PreviewPluginShutdownStages.CloseProgress progress
    ) {
        final String id = safePluginId(loadedPlugin);
        retention.retain(new RetainedPluginGenerations.RetainedGeneration(
            id,
            workerDone,
            loadedPlugin.eventOwner(),
            loadedPlugin.guard(),
            () -> reclaimClose(loadedPlugin, id, progress)
        ));
        log.warn(id, "Plugin close deferred: generation retained until lifecycle work quiesces");
    }

    /**
     * Re-drive of the close stages. {@code progress} makes this genuinely idempotent: phases that
     * already completed are skipped, a failed scope/loader close keeps its recorded outcome, and
     * plugin {@code shutdown()} is never invoked twice.
     */
    private boolean reclaimClose(
        final LocalPluginRuntime.LoadedPlugin loadedPlugin,
        final String id,
        final PreviewPluginShutdownStages.CloseProgress progress
    ) {
        loadedPlugin.eventOwner().beginClosing();
        if (!loadedPlugin.eventOwner().awaitQuiescence(Duration.ZERO)) {
            return false;
        }
        final PreviewPluginShutdownResult result = stages.close(loadedPlugin, id, true, progress);
        if ("SUCCEEDED".equals(result.classloaderCleanupState())) {
            loadedPlugin.eventOwner().close();
            return true;
        }
        // Quiescence-type failures are retryable; every other failure shape is terminal and the
        // generation stops being re-driven (the partial cleanup stays recorded in its summary).
        return result.failures().stream().noneMatch(failure ->
            RETRYABLE_CODES.contains(failure.code())
        );
    }

    int retainedGenerationCount() {
        return retention.retainedCount();
    }

    private void finalizeEventOwnerAfterFailure(
        final LocalPluginRuntime.LoadedPlugin loadedPlugin,
        final java.util.concurrent.CompletableFuture<Void> workerDone,
        final PreviewPluginShutdownStages.CloseProgress progress
    ) {
        try {
            loadedPlugin.eventOwner().beginClosing();
            if (loadedPlugin.eventOwner().awaitQuiescence(Duration.ZERO)) {
                loadedPlugin.eventOwner().close();
                return;
            }
            retainClose(loadedPlugin, workerDone, progress);
        } catch (Throwable failure) {
            retainClose(loadedPlugin, workerDone, progress);
        }
    }

    private LocalPluginRuntime.LoadedPluginSummary fallbackSummary(
        final LocalPluginRuntime.LoadedPlugin loadedPlugin
    ) {
        try {
            projectLifecycleHookRegistry.unregister(loadedPlugin.eventOwner().key());
            editorObjectHookRegistry.unregister(loadedPlugin.eventOwner().key());
            partHookRegistry.unregister(loadedPlugin.eventOwner().key());
            parameterHookRegistry.unregister(loadedPlugin.eventOwner().key());
            closeHook.run(safePluginId(loadedPlugin), "fallback-summary");
            loadedPlugin.runtime().transitionTo(PluginLifecycleState.SHUTDOWN_FAILED);
        } catch (Throwable ignored) {
            // The final summary must remain available when its fallback hook fails.
        }
        return fallbackSummaryWithoutRuntimeMutation(loadedPlugin);
    }

    private LocalPluginRuntime.LoadedPluginSummary timeoutSummary(
        final LocalPluginRuntime.LoadedPlugin loadedPlugin
    ) {
        try {
            closeHook.run(safePluginId(loadedPlugin), "timeout-summary");
            loadedPlugin.runtime().transitionTo(PluginLifecycleState.SHUTDOWN_FAILED);
        } catch (Throwable ignored) {
            // The final summary must remain available when its fallback hook fails.
        }
        return PreviewPluginSummaryFactory.create(
            loadedPlugin, "NOT_STARTED", "NOT_STARTED", "NOT_STARTED", "NOT_STARTED",
            "NOT_STARTED", List.of(new LocalPluginRuntime.PluginSummaryFailure(
                "PLUGIN_CLOSE_TIMEOUT", "close",
                "Plugin close exceeded its deadline; generation retained for deferred cleanup."
            ))
        );
    }

    private static LocalPluginRuntime.LoadedPluginSummary fallbackSummaryWithoutRuntimeMutation(
        final LocalPluginRuntime.LoadedPlugin loadedPlugin
    ) {
        return PreviewPluginSummaryFactory.create(
            loadedPlugin, "NOT_STARTED", "NOT_STARTED", "NOT_STARTED", "NOT_STARTED",
            "NOT_STARTED", List.of(new LocalPluginRuntime.PluginSummaryFailure(
                "PLUGIN_CLOSE_STAGE_FAILED", "close", "Plugin close stage failed safely."
            ))
        );
    }

    void tryLogStableFailure(final String component, final String code) {
        try {
            closeHook.run(component, "fallback-log");
            logStableFailure(component, code);
        } catch (Throwable ignored) {
            // A failed fallback logger must not prevent the remaining shutdown.
        }
    }

    private void logStableFailure(final String component, final String code) {
        log.error(
            component,
            "Runtime shutdown stage failed safely: " + code,
            new IllegalStateException(code)
        );
    }

    private static String safePluginId(final LocalPluginRuntime.LoadedPlugin loadedPlugin) {
        try {
            return loadedPlugin.runtime().id();
        } catch (Throwable ignored) {
            return "plugin";
        }
    }

    private record CloseOutcome(
        LocalPluginRuntime.LoadedPluginSummary summary,
        boolean retain
    ) {
    }
}
