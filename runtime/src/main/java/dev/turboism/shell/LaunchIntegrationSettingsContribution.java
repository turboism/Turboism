package dev.turboism.shell;

import dev.turboism.sdk.i18n.PluginLocalization;
import dev.turboism.sdk.ui.settings.SettingsBinding;
import dev.turboism.sdk.ui.settings.SettingsContribution;
import dev.turboism.sdk.ui.settings.SettingsControl;
import dev.turboism.sdk.ui.settings.SettingsTab;

import java.util.Objects;
import java.util.OptionalInt;

/**
 * Declarative Startup-tab controls for the installer-managed official BAT launch
 * integration. The toggle mirrors the installer state and delegates changes to the
 * guarded configurator script; the note explains elevation and the restart-only effect.
 */
final class LaunchIntegrationSettingsContribution {
    static final String CONTRIBUTION_ID = "turboism-launch-bat-integration";
    static final String TOGGLE_LABEL_KEY = "settings.launch.batIntegration";
    static final String NOTE_LABEL_KEY = "settings.launch.batIntegration.note";
    /** Sits after the automatic-update preference (40) on the shared startup tab. */
    private static final int TOGGLE_INDEX = 45;
    private static final int NOTE_INDEX = 46;

    private LaunchIntegrationSettingsContribution() {
    }

    static SettingsContribution create(
        final PluginLocalization i18n,
        final BatLaunchIntegrationService integration
    ) {
        Objects.requireNonNull(i18n, "i18n");
        Objects.requireNonNull(integration, "integration");
        return new SettingsContribution(
            CONTRIBUTION_ID,
            startupTab(i18n),
            OptionalInt.of(TOGGLE_INDEX),
            new SettingsControl.Toggle(
                "launch.batIntegration",
                i18n.text(TOGGLE_LABEL_KEY),
                SettingsBinding.of(integration::integrated, integration::setIntegrated)
            )
        );
    }

    static SettingsContribution createNote(final PluginLocalization i18n) {
        Objects.requireNonNull(i18n, "i18n");
        return new SettingsContribution(
            CONTRIBUTION_ID + ".note",
            startupTab(i18n),
            OptionalInt.of(NOTE_INDEX),
            new SettingsControl.Note("launch.batIntegration.note", i18n.text(NOTE_LABEL_KEY))
        );
    }

    private static SettingsTab startupTab(final PluginLocalization i18n) {
        return new SettingsTab("startup", i18n.text("settings.tab.startup"), OptionalInt.of(300));
    }
}
