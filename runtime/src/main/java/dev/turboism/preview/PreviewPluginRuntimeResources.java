package dev.turboism.preview;

import dev.turboism.adapter.host.RuntimeHostAdapterAccess;
import dev.turboism.adapter.cubism.lifecycle.EditorLifecycleCoordinator;
import dev.turboism.adapter.cubism.lifecycle.EditorObjectHookRegistry;
import dev.turboism.adapter.cubism.lifecycle.EditorObjectLifecycleCoordinator;
import dev.turboism.adapter.cubism.lifecycle.ParameterHookRegistry;
import dev.turboism.adapter.cubism.lifecycle.ParameterLifecycleCoordinator;
import dev.turboism.adapter.cubism.lifecycle.PartHookRegistry;
import dev.turboism.adapter.cubism.lifecycle.PartLifecycleCoordinator;
import dev.turboism.adapter.cubism.lifecycle.ProjectFileLifecycleCoordinator;
import dev.turboism.adapter.cubism.lifecycle.ProjectLifecycleHookRegistry;
import dev.turboism.core.runtime.RuntimeScheduler;
import dev.turboism.failure.RuntimeFailureCollector;
import dev.turboism.hostread.SharedAsyncHostReadLane;
import dev.turboism.i18n.CubismHostLocale;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Owns the preview runtime resources shared by loading and shutdown. */
record PreviewPluginRuntimeResources(
    SharedAsyncHostReadLane hostReadLane,
    RuntimeFailureCollector failureCollector,
    PreviewPluginLoadCoordinator loadCoordinator,
    PreviewPluginShutdown shutdown,
    dev.turboism.pluginmanagement.RuntimePluginManagementService pluginManagement,
    PreviewPluginContextFactory contextFactory,
    dev.turboism.sdk.runtime.RuntimeSettingsService runtimeSettings,
    dev.turboism.plugin.core.CubismJvmSettingsService cubismJvmSettings
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
        final EditorObjectLifecycleCoordinator editorObjectLifecycle,
        final ProjectFileLifecycleCoordinator projectFileLifecycle,
        final EditorLifecycleCoordinator editorLifecycleEvents,
        final dev.turboism.sdk.cubism.filechooser.FileChooserHistoryService fileChooserHistory
    ) {
        return create(
            home, scheduler, hostAccess, log, failureCollector, pluginCloseHook, loaded,
            parameterLifecycle, partLifecycle, editorObjectLifecycle, projectFileLifecycle,
            editorLifecycleEvents, fileChooserHistory, CubismHostLocale.resolve()
        );
    }

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
        final EditorObjectLifecycleCoordinator editorObjectLifecycle,
        final ProjectFileLifecycleCoordinator projectFileLifecycle,
        final EditorLifecycleCoordinator editorLifecycleEvents,
        final dev.turboism.sdk.cubism.filechooser.FileChooserHistoryService fileChooserHistory,
        final Locale effectiveLocale
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
        final ProjectLifecycleHookRegistry projectLifecycleHookRegistry =
            new ProjectLifecycleHookRegistry(
                Objects.requireNonNull(projectFileLifecycle, "projectFileLifecycle"),
                Objects.requireNonNull(editorLifecycleEvents, "editorLifecycleEvents")
            );
        return assemble(
            normalizedHome, runtimeScheduler, runtimeHostAccess, lane, runtimeLog, collector,
            pluginCloseHook, loaded, parameterHookRegistry, partHookRegistry,
            editorObjectHookRegistry, projectLifecycleHookRegistry, fileChooserHistory,
            Objects.requireNonNull(effectiveLocale, "effectiveLocale")
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
        final EditorObjectHookRegistry editorObjectHookRegistry,
        final ProjectLifecycleHookRegistry projectLifecycleHookRegistry,
        final dev.turboism.sdk.cubism.filechooser.FileChooserHistoryService fileChooserHistory,
        final Locale effectiveLocale
    ) {
        final dev.turboism.pluginmanagement.RuntimePluginManagementService pluginManagement =
            dev.turboism.pluginmanagement.RuntimePluginManagementService.withMetadataLocale(home, () -> loaded.stream()
                .map(plugin -> {
                    final var descriptor = plugin.runtime().descriptor();
                    final var metadata = localizedMetadata(plugin.localization(), descriptor.name(), descriptor.description());
                    return new dev.turboism.plugin.core.CorePluginManagement.PluginInfo(
                        descriptor.id(), metadata.name(), descriptor.version(), metadata.description(),
                        plugin.runtime().state().name(),
                        plugin.runtime().state() == dev.turboism.core.lifecycle.PluginLifecycleState.ENABLED
                            ? "ENABLED" : "DISABLED",
                        false,
                        java.util.Optional.empty(),
                        dev.turboism.pluginmanagement.PluginCategoryRegistry.presentation(
                            descriptor.id(), descriptor.category(),
                            diagnostic -> log.warn("plugin-management",
                                diagnostic.code() + ": " + diagnostic.message())
                        ),
                        java.util.List.copyOf(descriptor.tags())
                    );
                })
                .toList(),
                (dev.turboism.pluginmanagement.RuntimePluginManagementService.MetadataLocaleProvider)
                    () -> effectiveLocale,
                message -> log.warn("plugin-management", message)
            );
        final PreviewPluginContextFactory contextFactory = new PreviewPluginContextFactory(
            home, scheduler, hostAccess, lane, log, failureCollector, fileChooserHistory,
            effectiveLocale
        );
        final dev.turboism.config.RuntimeSettingsFileService runtimeSettings =
            new dev.turboism.config.RuntimeSettingsFileService(
                home,
                hostAccess.dockMaintenance(),
                log::setMinimumLevel,
                log::setMaxStorageMiB,
                message -> log.warn("config", message)
            );
        final dev.turboism.sdk.runtime.RuntimeSettings settings = runtimeSettings.read();
        log.setMinimumLevel(settings.logLevel());
        log.setMaxStorageMiB(settings.maxLogStorageMiB());
        final dev.turboism.config.CubismJvmSettingsFileService cubismJvmSettings =
            new dev.turboism.config.CubismJvmSettingsFileService(home);
        return new PreviewPluginRuntimeResources(
            lane, failureCollector,
            new PreviewPluginLoadCoordinator(
                home, home.resolve("plugins"), contextFactory, log, loaded,
                parameterHookRegistry, partHookRegistry, editorObjectHookRegistry,
                projectLifecycleHookRegistry
            ),
            new PreviewPluginShutdown(
                log,
                Objects.requireNonNull(pluginCloseHook, "pluginCloseHook"),
                parameterHookRegistry, partHookRegistry, editorObjectHookRegistry,
                projectLifecycleHookRegistry
            ),
            pluginManagement,
            contextFactory,
            runtimeSettings,
            cubismJvmSettings
        );
    }

    private static Metadata localizedMetadata(
        final dev.turboism.sdk.i18n.PluginLocalization localization,
        final String fallbackName,
        final String fallbackDescription
    ) {
        return new Metadata(
            localized(localization, "plugin.name", fallbackName),
            localized(localization, "plugin.description", fallbackDescription)
        );
    }

    private static String localized(
        final dev.turboism.sdk.i18n.PluginLocalization localization,
        final String key,
        final String fallback
    ) {
        try {
            if (localization.contains(key)) {
                final String value = localization.text(key);
                if (value != null && !value.isBlank()) return value;
            }
        } catch (RuntimeException ignored) {
            // Optional metadata localization must never hide a plugin from management.
        }
        return fallback;
    }

    private record Metadata(String name, String description) {
    }
}
