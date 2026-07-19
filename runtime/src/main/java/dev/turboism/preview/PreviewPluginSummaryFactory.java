package dev.turboism.preview;

import dev.turboism.core.lifecycle.PluginLifecycleState;
import dev.turboism.sdk.plugin.PluginDescriptor;

import java.util.Comparator;
import java.util.List;

/** Creates immutable runtime summaries without exposing loader internals. */
final class PreviewPluginSummaryFactory {

    private PreviewPluginSummaryFactory() {
    }

    static LocalPluginRuntime.LoadedPluginSummary active(
        final LocalPluginRuntime.LoadedPlugin loadedPlugin
    ) {
        final String disable = loadedPlugin.runtime().state() == PluginLifecycleState.ENABLED
            ? "NOT_STARTED" : "NOT_REQUIRED";
        return create(
            loadedPlugin, disable, "NOT_STARTED", "NOT_STARTED", "NOT_STARTED",
            "NOT_STARTED", List.of()
        );
    }

    static LocalPluginRuntime.LoadedPluginSummary create(
        final LocalPluginRuntime.LoadedPlugin loadedPlugin,
        final String disableState,
        final String shutdownState,
        final String unloadState,
        final String scopeCleanupState,
        final String classloaderCleanupState,
        final List<LocalPluginRuntime.PluginSummaryFailure> failures
    ) {
        final PluginDescriptor descriptor = loadedPlugin.runtime().descriptor();
        return new LocalPluginRuntime.LoadedPluginSummary(
            loadedPlugin.runtime().id(), descriptor.name(), descriptor.version(),
            loadedPlugin.runtime().state(), loadedPlugin.jar(), descriptor.capabilities(),
            descriptor.permissions().stream().map(PluginDescriptor.PermissionRef::id).toList(),
            loadedPlugin.localization().reportSnapshot(), disableState, shutdownState, unloadState,
            scopeCleanupState, classloaderCleanupState, failures, loadedPlugin.cleanupEvidence().snapshot()
        );
    }

    static List<LocalPluginRuntime.LoadedPluginSummary> sorted(
        final List<LocalPluginRuntime.LoadedPluginSummary> summaries
    ) {
        return summaries.stream()
            .sorted(Comparator.comparing(LocalPluginRuntime.LoadedPluginSummary::id))
            .toList();
    }
}
