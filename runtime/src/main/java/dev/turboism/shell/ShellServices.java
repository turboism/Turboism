package dev.turboism.shell;

import dev.turboism.sdk.runtime.RuntimeLogReader;
import dev.turboism.sdk.runtime.RuntimeSettingsService;

import java.util.Objects;

/** Runtime-owned service bundle handed to the framework shell at construction. */
public record ShellServices(
    RuntimeSettingsService settings,
    CubismJvmSettingsService cubismJvmSettings,
    MeshTriangulationSettingsService meshTriangulationSettings,
    AtlasTileBboxSettingsService atlasTileBboxSettings,
    AtlasCacheReuseSettingsService atlasCacheReuseSettings,
    dev.turboism.sdk.ui.settings.SettingsContributionSource settingsContributions,
    CorePluginManagement plugins,
    FloatingPanelActions floatingPanelActions,
    RuntimeLogReader logs,
    CoreUpdateService update
) {
    public ShellServices(
        final RuntimeSettingsService settings,
        final CorePluginManagement plugins
    ) {
        this(
            settings,
            CubismJvmSettingsService.unavailable(),
            MeshTriangulationSettingsService.unavailable(),
            AtlasTileBboxSettingsService.unavailable(),
            AtlasCacheReuseSettingsService.unavailable(),
            dev.turboism.sdk.ui.settings.SettingsContributionSource.empty(),
            plugins,
            FloatingPanelActions.unavailable(),
            RuntimeLogReader.unavailable(),
            CoreUpdateService.unavailable()
        );
    }

    public interface FloatingPanelActions {
        void togglePanelFloating(dev.turboism.sdk.ui.context.PanelTabSelection selection);

        static FloatingPanelActions unavailable() {
            return selection -> {
                throw new IllegalStateException("panel-tab floating action is unavailable");
            };
        }
    }
    public ShellServices {
        settings = Objects.requireNonNull(settings, "settings");
        cubismJvmSettings = Objects.requireNonNull(cubismJvmSettings, "cubismJvmSettings");
        meshTriangulationSettings = Objects.requireNonNull(
            meshTriangulationSettings,
            "meshTriangulationSettings"
        );
        atlasTileBboxSettings = Objects.requireNonNull(
            atlasTileBboxSettings,
            "atlasTileBboxSettings"
        );
        atlasCacheReuseSettings = Objects.requireNonNull(
            atlasCacheReuseSettings,
            "atlasCacheReuseSettings"
        );
        settingsContributions = Objects.requireNonNull(
            settingsContributions,
            "settingsContributions"
        );
        plugins = Objects.requireNonNull(plugins, "plugins");
        floatingPanelActions = Objects.requireNonNull(floatingPanelActions, "floatingPanelActions");
        logs = Objects.requireNonNull(logs, "logs");
        update = Objects.requireNonNull(update, "update");
    }
}
