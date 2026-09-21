package dev.turboism.internal.core;

import dev.turboism.sdk.runtime.RuntimeLogReader;
import dev.turboism.sdk.runtime.RuntimeSettingsService;

import java.util.Objects;

/** Runtime-owned service handoff passed to the built-in core instance at construction. */
public record CorePluginServices(
    RuntimeSettingsService settings,
    CubismJvmSettingsService cubismJvmSettings,
    dev.turboism.sdk.ui.settings.SettingsContributionSource settingsContributions,
    CorePluginManagement plugins,
    FloatingPanelActions floatingPanelActions,
    RuntimeLogReader logs,
    CoreUpdateService update
) {
    public CorePluginServices(
        final RuntimeSettingsService settings,
        final CorePluginManagement plugins
    ) {
        this(
            settings,
            CubismJvmSettingsService.unavailable(),
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

    public CorePluginServices {
        settings = Objects.requireNonNull(settings, "settings");
        cubismJvmSettings = Objects.requireNonNull(cubismJvmSettings, "cubismJvmSettings");
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
