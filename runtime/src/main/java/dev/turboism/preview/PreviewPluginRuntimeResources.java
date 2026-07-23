package dev.turboism.preview;

import dev.turboism.adapter.host.RuntimeHostAdapterAccess;
import dev.turboism.adapter.cubism.lifecycle.ParameterHookRegistry;
import dev.turboism.adapter.cubism.lifecycle.ParameterLifecycleCoordinator;
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
    PreviewPluginShutdown shutdown
) {
    static PreviewPluginRuntimeResources create(
        final Path home,
        final RuntimeScheduler scheduler,
        final RuntimeHostAdapterAccess hostAccess,
        final PreviewLog log,
        final RuntimeFailureCollector failureCollector,
        final LocalPluginRuntime.PluginCloseHook pluginCloseHook,
        final List<LocalPluginRuntime.LoadedPlugin> loaded,
        final ParameterLifecycleCoordinator parameterLifecycle
    ) {
        final Path normalizedHome = Objects.requireNonNull(home, "home").toAbsolutePath().normalize();
        final SharedAsyncHostReadLane lane = new SharedAsyncHostReadLane(32);
        final RuntimeScheduler runtimeScheduler = Objects.requireNonNull(scheduler, "scheduler");
        final RuntimeHostAdapterAccess runtimeHostAccess = Objects.requireNonNull(hostAccess, "hostAccess");
        final PreviewLog runtimeLog = Objects.requireNonNull(log, "log");
        final RuntimeFailureCollector collector = Objects.requireNonNull(failureCollector, "failureCollector");
        final ParameterHookRegistry hookRegistry = new ParameterHookRegistry(
            Objects.requireNonNull(parameterLifecycle, "parameterLifecycle")
        );
        return assemble(
            normalizedHome, runtimeScheduler, runtimeHostAccess, lane, runtimeLog, collector,
            pluginCloseHook, loaded, hookRegistry
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
        final ParameterHookRegistry hookRegistry
    ) {
        final PreviewPluginContextFactory contextFactory = new PreviewPluginContextFactory(
            home, scheduler, hostAccess, lane, log, failureCollector
        );
        return new PreviewPluginRuntimeResources(
            lane, failureCollector,
            new PreviewPluginLoadCoordinator(
                home.resolve("plugins"), contextFactory, log, loaded, hookRegistry
            ),
            new PreviewPluginShutdown(
                log,
                Objects.requireNonNull(pluginCloseHook, "pluginCloseHook"),
                hookRegistry
            )
        );
    }
}
