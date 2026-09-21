package dev.turboism.preview;

import dev.turboism.core.lifecycle.PluginLifecycleState;
import dev.turboism.adapter.cubism.lifecycle.EditorObjectHookRegistry;
import dev.turboism.adapter.cubism.lifecycle.ParameterHookRegistry;
import dev.turboism.adapter.cubism.lifecycle.PartHookRegistry;
import dev.turboism.adapter.cubism.lifecycle.ProjectLifecycleHookRegistry;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** Coordinates reverse-order plugin shutdown and failure fallback reporting. */
final class PreviewPluginShutdown {

    private final PreviewLog log;
    private final LocalPluginRuntime.PluginCloseHook closeHook;
    private final PreviewPluginShutdownStages stages;
    private final ParameterHookRegistry parameterHookRegistry;
    private final PartHookRegistry partHookRegistry;
    private final EditorObjectHookRegistry editorObjectHookRegistry;
    private final ProjectLifecycleHookRegistry projectLifecycleHookRegistry;
    private final java.util.Set<LocalPluginRuntime.LoadedPlugin> retainedGenerations =
        java.util.concurrent.ConcurrentHashMap.newKeySet();

    PreviewPluginShutdown(
        final PreviewLog log,
        final LocalPluginRuntime.PluginCloseHook closeHook,
        final ParameterHookRegistry parameterHookRegistry,
        final PartHookRegistry partHookRegistry,
        final EditorObjectHookRegistry editorObjectHookRegistry,
        final ProjectLifecycleHookRegistry projectLifecycleHookRegistry
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
     * Stops event admission for every plugin in the batch before any of them is torn down.
     * Sealing the whole fenced set up front means one member's slow quiescence cannot leave the
     * rest of the set still accepting callbacks; the per-plugin close below only re-enters an
     * already-closing owner. A member whose owner refuses the transition is left to the normal
     * close path, which retries {@code beginClosing} itself.
     */
    void fence(final List<LocalPluginRuntime.LoadedPlugin> loadedPlugins) {
        for (LocalPluginRuntime.LoadedPlugin loadedPlugin : loadedPlugins) {
            try {
                loadedPlugin.eventOwner().beginClosing();
            } catch (Throwable ignored) {
                // Fencing is best-effort per member: the teardown path retries the transition.
            }
        }
    }

    /**
     * Unloads one already-loaded plugin mid-load through the same shutdown path used at runtime
     * close. The load coordinator calls this when a required dependency fails after the dependent
     * was already loaded under a {@code before}/{@code none} ordering, so the dependent is not
     * left running against a dependency that never came up. The caller fences the batch and
     * removes the plugin from the shared {@code loaded} list beforehand.
     *
     * @return the teardown summary for reporting; the plugin is not re-added to any live list
     */
    LocalPluginRuntime.LoadedPluginSummary unloadOne(
        final LocalPluginRuntime.LoadedPlugin loadedPlugin
    ) {
        final List<LocalPluginRuntime.LoadedPluginSummary> summaries = new ArrayList<>();
        closeOne(loadedPlugin, summaries);
        return summaries.get(0);
    }

    private void closeOne(
        final LocalPluginRuntime.LoadedPlugin loadedPlugin,
        final List<LocalPluginRuntime.LoadedPluginSummary> summaries
    ) {
        try {
            summaries.add(closeLoadedPlugin(loadedPlugin));
        } catch (Throwable failure) {
            summaries.add(fallbackSummary(loadedPlugin));
            finalizeEventOwnerAfterFailure(loadedPlugin);
            tryLogStableFailure(safePluginId(loadedPlugin), "PLUGIN_CLOSE_STAGE_FAILED");
        }
    }

    private LocalPluginRuntime.LoadedPluginSummary closeLoadedPlugin(
        final LocalPluginRuntime.LoadedPlugin loadedPlugin
    ) throws Throwable {
        final String id = loadedPlugin.runtime().id();
        // Teardown is deliberately outside the EventBus lifecycle: once closing begins,
        // disable()/shutdown() cannot publish or add subscribers. This cancels queued
        // callbacks before plugin state starts disappearing and makes unload quiescence
        // authoritative rather than relying on each plugin to stop event traffic itself.
        loadedPlugin.eventOwner().beginClosing();
        final boolean eventQuiesced = loadedPlugin.eventOwner().awaitQuiescence(
            Duration.ofSeconds(5)
        );
        projectLifecycleHookRegistry.unregister(loadedPlugin.eventOwner().key());
        editorObjectHookRegistry.unregister(loadedPlugin.eventOwner().key());
        partHookRegistry.unregister(loadedPlugin.eventOwner().key());
        parameterHookRegistry.unregister(loadedPlugin.eventOwner().key());
        closeHook.run(id, "close");
        final PreviewPluginShutdownResult result = stages.close(
            loadedPlugin, id, eventQuiesced
        );
        final boolean retryableCleanup = !eventQuiesced
            || result.failures().stream().anyMatch(failure ->
                "PLUGIN_BACKUP_QUIESCENCE_FAILED".equals(failure.code())
            );
        if (retryableCleanup && retainedGenerations.add(loadedPlugin)) {
            scheduleRetainedCleanup(loadedPlugin);
        }
        log.info(id, "Plugin unloaded with state " + loadedPlugin.runtime().state());
        if (eventQuiesced) {
            loadedPlugin.eventOwner().close();
        }
        return PreviewPluginSummaryFactory.create(
            loadedPlugin, result.disableState(), result.shutdownState(), result.unloadState(),
            result.scopeCleanupState(), result.classloaderCleanupState(), result.failures()
        );
    }

    private void scheduleRetainedCleanup(
        final LocalPluginRuntime.LoadedPlugin loadedPlugin
    ) {
        final Thread reaper = new Thread(
            () -> reapRetainedGeneration(loadedPlugin),
            "turboism-event-zombie-" + safePluginId(loadedPlugin)
        );
        reaper.setDaemon(true);
        reaper.setContextClassLoader(PreviewPluginShutdown.class.getClassLoader());
        reaper.start();
    }

    private void reapRetainedGeneration(
        final LocalPluginRuntime.LoadedPlugin loadedPlugin
    ) {
        try {
            if (!loadedPlugin.eventOwner().awaitQuiescence(Duration.ofDays(3650))) {
                return;
            }
            final String id = safePluginId(loadedPlugin);
            while (retainedGenerations.contains(loadedPlugin)) {
                final PreviewPluginShutdownResult result = stages.close(
                    loadedPlugin, id, true
                );
                if ("SUCCEEDED".equals(result.classloaderCleanupState())) {
                    loadedPlugin.eventOwner().close();
                    retainedGenerations.remove(loadedPlugin);
                    log.info(id, "Retained plugin generation cleanup succeeded");
                    return;
                }
                if (result.failures().stream().noneMatch(failure ->
                    "PLUGIN_BACKUP_QUIESCENCE_FAILED".equals(failure.code())
                )) {
                    return;
                }
                Thread.sleep(1_000L);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (Throwable failure) {
            tryLogStableFailure(safePluginId(loadedPlugin), "PLUGIN_RETAINED_CLEANUP_FAILED");
        }
    }

    int retainedGenerationCount() {
        return retainedGenerations.size();
    }

    private void finalizeEventOwnerAfterFailure(
        final LocalPluginRuntime.LoadedPlugin loadedPlugin
    ) {
        try {
            loadedPlugin.eventOwner().beginClosing();
            if (loadedPlugin.eventOwner().awaitQuiescence(Duration.ZERO)) {
                loadedPlugin.eventOwner().close();
                return;
            }
            if (retainedGenerations.add(loadedPlugin)) {
                scheduleRetainedCleanup(loadedPlugin);
            }
        } catch (Throwable failure) {
            if (retainedGenerations.add(loadedPlugin)) {
                scheduleRetainedCleanup(loadedPlugin);
            }
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
}
