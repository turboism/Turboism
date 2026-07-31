package dev.turboism.preview;

import dev.turboism.adapter.host.RuntimeHostAdapterAccess;
import dev.turboism.adapter.cubism.lifecycle.ParameterHookRegistry;
import dev.turboism.adapter.cubism.lifecycle.ParameterLifecycleCoordinator;
import dev.turboism.adapter.cubism.lifecycle.PartHookRegistry;
import dev.turboism.adapter.cubism.lifecycle.PartLifecycleCoordinator;
import dev.turboism.adapter.cubism.lifecycle.EditorObjectHookRegistry;
import dev.turboism.adapter.cubism.lifecycle.EditorObjectLifecycleCoordinator;
import dev.turboism.core.runtime.RuntimeScheduler;
import dev.turboism.failure.RuntimeFailureCollector;
import dev.turboism.hostread.SharedAsyncHostReadLane;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/** Owns the preview runtime resources shared by loading and shutdown. */
record PreviewPluginRuntimeResources(
    SharedAsyncHostReadLane hostReadLane,
    RuntimeFailureCollector failureCollector,
    PreviewPluginLoadCoordinator loadCoordinator,
    PreviewPluginShutdown shutdown,
    dev.turboism.pluginmanagement.RuntimePluginManagementService pluginManagement,
    PreviewPluginContextFactory contextFactory,
    dev.turboism.sdk.runtime.RuntimeSettingsService runtimeSettings
) {
    static PreviewPluginRuntimeResources create(
        final Path home,
        final RuntimeScheduler scheduler,
        final RuntimeHostAdapterAccess hostAccess,
        final PreviewLog log,
        final RuntimeFailureCollector failureCollector,
        final LocalPluginRuntime.PluginCloseHook pluginCloseHook,
        final List<LocalPluginRuntime.LoadedPlugin> loaded,
        final ParameterLifecycleCoordinator parameterLifecycle,
        final PartLifecycleCoordinator partLifecycle,
        final EditorObjectLifecycleCoordinator editorObjectLifecycle
    ) {
        final Path normalizedHome = Objects.requireNonNull(home, "home").toAbsolutePath().normalize();
        final SharedAsyncHostReadLane lane = new SharedAsyncHostReadLane(32);
        final RuntimeScheduler runtimeScheduler = Objects.requireNonNull(scheduler, "scheduler");
        final RuntimeHostAdapterAccess runtimeHostAccess = Objects.requireNonNull(hostAccess, "hostAccess");
        final PreviewLog runtimeLog = Objects.requireNonNull(log, "log");
        final RuntimeFailureCollector collector = Objects.requireNonNull(failureCollector, "failureCollector");
        final ParameterHookRegistry parameterHookRegistry = new ParameterHookRegistry(
            Objects.requireNonNull(parameterLifecycle, "parameterLifecycle")
        );
        final PartHookRegistry partHookRegistry = new PartHookRegistry(
            Objects.requireNonNull(partLifecycle, "partLifecycle")
        );
        final EditorObjectHookRegistry editorObjectHookRegistry = new EditorObjectHookRegistry(
            Objects.requireNonNull(editorObjectLifecycle, "editorObjectLifecycle")
        );
        return assemble(
            normalizedHome, runtimeScheduler, runtimeHostAccess, lane, runtimeLog, collector,
            pluginCloseHook, loaded, parameterHookRegistry, partHookRegistry, editorObjectHookRegistry
        );
    }

    private static PreviewPluginRuntimeResources assemble(
        final Path home,
        final RuntimeScheduler scheduler,
        final RuntimeHostAdapterAccess hostAccess,
        final SharedAsyncHostReadLane lane,
        final PreviewLog log,
        final RuntimeFailureCollector failureCollector,
        final LocalPluginRuntime.PluginCloseHook pluginCloseHook,
        final List<LocalPluginRuntime.LoadedPlugin> loaded,
        final ParameterHookRegistry parameterHookRegistry,
        final PartHookRegistry partHookRegistry,
        final EditorObjectHookRegistry editorObjectHookRegistry
    ) {
        final dev.turboism.pluginmanagement.RuntimePluginManagementService pluginManagement =
            new dev.turboism.pluginmanagement.RuntimePluginManagementService(home, () -> loaded.stream()
                .map(plugin -> {
                    final var descriptor = plugin.runtime().descriptor();
                    return new dev.turboism.plugin.core.CorePluginManagement.PluginInfo(
                        descriptor.id(), descriptor.name(), descriptor.version(), descriptor.description(),
                        plugin.runtime().state().name(),
                        plugin.runtime().state() == dev.turboism.core.lifecycle.PluginLifecycleState.ENABLED
                            ? "ENABLED" : "DISABLED",
                        false,
                        java.util.Optional.empty()
                    );
                })
                .toList());
        final PreviewPluginContextFactory contextFactory = new PreviewPluginContextFactory(
            home, scheduler, hostAccess, lane, log, failureCollector
        );
        return new PreviewPluginRuntimeResources(
            lane, failureCollector,
            new PreviewPluginLoadCoordinator(
                home, home.resolve("plugins"), contextFactory, log, loaded,
                parameterHookRegistry, partHookRegistry, editorObjectHookRegistry
            ),
            new PreviewPluginShutdown(
                log,
                Objects.requireNonNull(pluginCloseHook, "pluginCloseHook"),
                parameterHookRegistry, partHookRegistry, editorObjectHookRegistry
            ),
            pluginManagement,
            contextFactory,
            new dev.turboism.config.RuntimeSettingsFileService(home, hostAccess.dockMaintenance())
        );
    }
}
