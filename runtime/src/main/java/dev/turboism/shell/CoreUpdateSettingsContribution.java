package dev.turboism.shell;

import dev.turboism.internal.core.CoreUpdateService;
import dev.turboism.pluginmanagement.PluginAutoUpdateControl;
import dev.turboism.sdk.i18n.PluginLocalization;
import dev.turboism.sdk.ui.settings.SettingsBinding;
import dev.turboism.sdk.ui.settings.SettingsContribution;
import dev.turboism.sdk.ui.settings.SettingsControl;
import dev.turboism.sdk.ui.settings.SettingsTab;
import java.util.Objects;
import java.util.OptionalInt;

/** Declarative settings controls for independent Turboism framework and official-plugin update preferences. */
final class CoreUpdateSettingsContribution {
    static final String CONTRIBUTION_ID = "turboism-updates-automatic";
    static final String PLUGIN_UPDATES_CONTRIBUTION_ID = "turboism-plugin-updates-automatic";

    private CoreUpdateSettingsContribution() {}

    static SettingsContribution create(final PluginLocalization i18n, final CoreUpdateService updates) {
        Objects.requireNonNull(i18n, "i18n");
        Objects.requireNonNull(updates, "updates");
        return new SettingsContribution(
                CONTRIBUTION_ID,
                new SettingsTab("startup", i18n.text("settings.tab.startup"), OptionalInt.of(300)),
                OptionalInt.of(40),
                new SettingsControl.Toggle(
                        CONTRIBUTION_ID,
                        i18n.text("settings.updates.automatic"),
                        SettingsBinding.of(updates::automaticChecksEnabled, enabled -> {
                            final CoreUpdateService.PreferenceSaveResult result =
                                    updates.saveAutomaticChecksEnabled(enabled);
                            if (!result.saved()) {
                                throw new IllegalStateException(result.message());
                            }
                        })));
    }

    static SettingsContribution createPluginUpdates(
            final PluginLocalization i18n, final PluginAutoUpdateControl pluginUpdates) {
        Objects.requireNonNull(i18n, "i18n");
        Objects.requireNonNull(pluginUpdates, "pluginUpdates");
        return new SettingsContribution(
                PLUGIN_UPDATES_CONTRIBUTION_ID,
                new SettingsTab("startup", i18n.text("settings.tab.startup"), OptionalInt.of(300)),
                OptionalInt.of(45),
                new SettingsControl.Toggle(
                        PLUGIN_UPDATES_CONTRIBUTION_ID,
                        i18n.text("settings.plugins.automatic-updates"),
                        SettingsBinding.of(pluginUpdates::automaticPluginUpdatesEnabled, enabled -> {
                            final CoreUpdateService.PreferenceSaveResult result =
                                    pluginUpdates.saveAutomaticPluginUpdatesEnabled(enabled);
                            if (!result.saved()) {
                                throw new IllegalStateException(result.message());
                            }
                        })));
    }
}
