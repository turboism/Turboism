package dev.turboism.shell;

import dev.turboism.internal.core.CoreFrameworkUpdates;
import dev.turboism.internal.core.CoreUpdateService;
import dev.turboism.pluginmanagement.PluginAutoUpdateControl;
import dev.turboism.sdk.i18n.PluginLocalization;
import dev.turboism.sdk.ui.settings.SettingsBinding;
import dev.turboism.sdk.ui.settings.SettingsChangeDecision;
import dev.turboism.sdk.ui.settings.SettingsContribution;
import dev.turboism.sdk.ui.settings.SettingsControl;
import dev.turboism.sdk.ui.settings.SettingsTab;
import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;

/** Declarative settings controls for independent Turboism framework and official-plugin update preferences. */
final class CoreUpdateSettingsContribution {
    static final String CONTRIBUTION_ID = "turboism-updates-automatic";
    static final String CHANNEL_CONTRIBUTION_ID = "turboism-updates-channel";
    static final String PLUGIN_UPDATES_CONTRIBUTION_ID = "turboism-plugin-updates-automatic";
    private static final String STARTUP_TAB_ID = "startup";
    private static final int STARTUP_TAB_ORDER = 300;
    private static final int AUTOMATIC_ORDER = 40;
    private static final int CHANNEL_ORDER = 42;
    private static final int PLUGIN_UPDATES_ORDER = 45;

    private CoreUpdateSettingsContribution() {}

    static SettingsContribution create(final PluginLocalization i18n, final CoreUpdateService updates) {
        Objects.requireNonNull(i18n, "i18n");
        Objects.requireNonNull(updates, "updates");
        return new SettingsContribution(
                CONTRIBUTION_ID,
                new SettingsTab(STARTUP_TAB_ID, i18n.text("settings.tab.startup"), OptionalInt.of(STARTUP_TAB_ORDER)),
                OptionalInt.of(AUTOMATIC_ORDER),
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

    static SettingsContribution createChannel(final PluginLocalization i18n, final CoreUpdateService updates) {
        Objects.requireNonNull(i18n, "i18n");
        Objects.requireNonNull(updates, "updates");
        final List<SettingsControl.Option> options = List.of(
                new SettingsControl.Option(CoreUpdateService.CHANNEL_STABLE, i18n.text("updates.channel.stable")),
                new SettingsControl.Option(CoreUpdateService.CHANNEL_BETA, i18n.text("updates.channel.beta")));
        return new SettingsContribution(
                CHANNEL_CONTRIBUTION_ID,
                new SettingsTab(STARTUP_TAB_ID, i18n.text("settings.tab.startup"), OptionalInt.of(STARTUP_TAB_ORDER)),
                OptionalInt.of(CHANNEL_ORDER),
                new SettingsControl.Choice(
                        CHANNEL_CONTRIBUTION_ID,
                        i18n.text("settings.updates.channel"),
                        options,
                        SettingsBinding.of(updates::updateChannel, channel -> {
                            final CoreUpdateService.PreferenceSaveResult result = updates.saveUpdateChannel(channel);
                            if (!result.saved()) {
                                throw new IllegalStateException(result.message());
                            }
                        }),
                        (previous, proposed) -> {
                            if (Objects.equals(previous, proposed)) {
                                return SettingsChangeDecision.allow();
                            }
                            final CoreFrameworkUpdates.Phase phase =
                                    updates.frameworkUpdates().snapshot().phase();
                            if (phase == CoreFrameworkUpdates.Phase.DOWNLOADING
                                    || phase == CoreFrameworkUpdates.Phase.APPLY_PENDING) {
                                return SettingsChangeDecision.rejected(
                                        i18n.text("updates.channel.busy.title"),
                                        i18n.text("updates.channel.busy.message"));
                            }
                            return SettingsChangeDecision.allow();
                        }));
    }

    static SettingsContribution createPluginUpdates(
            final PluginLocalization i18n, final PluginAutoUpdateControl pluginUpdates) {
        Objects.requireNonNull(i18n, "i18n");
        Objects.requireNonNull(pluginUpdates, "pluginUpdates");
        return new SettingsContribution(
                PLUGIN_UPDATES_CONTRIBUTION_ID,
                new SettingsTab(STARTUP_TAB_ID, i18n.text("settings.tab.startup"), OptionalInt.of(STARTUP_TAB_ORDER)),
                OptionalInt.of(PLUGIN_UPDATES_ORDER),
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
