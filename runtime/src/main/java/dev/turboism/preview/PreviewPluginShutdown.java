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
        if (!eventQuiesced) {
            retainedGenerations.add(loadedPlugin);
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
            final PreviewPluginShutdownResult result = stages.close(
                loadedPlugin, id, true
            );
            if ("SUCCEEDED".equals(result.classloaderCleanupState())) {
                loadedPlugin.eventOwner().close();
                retainedGenerations.remove(loadedPlugin);
                log.info(id, "Retained plugin generation cleanup succeeded");
            }
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
            projectLifecycleHookRegistry.unregister(safePluginId(loadedPlugin));
            editorObjectHookRegistry.unregister(safePluginId(loadedPlugin));
            partHookRegistry.unregister(safePluginId(loadedPlugin));
            parameterHookRegistry.unregister(safePluginId(loadedPlugin));
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
