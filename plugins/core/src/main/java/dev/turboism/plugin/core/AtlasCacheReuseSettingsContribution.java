package dev.turboism.plugin.core;

import dev.turboism.sdk.i18n.PluginLocalization;
import dev.turboism.sdk.ui.settings.SettingsBinding;
import dev.turboism.sdk.ui.settings.SettingsContribution;
import dev.turboism.sdk.ui.settings.SettingsControl;
import dev.turboism.sdk.ui.settings.SettingsTab;

import java.util.Objects;
import java.util.OptionalInt;

/** Core-owned toggle for the atlas cache-reuse guard, rendered in the shared Performance tab. */
final class AtlasCacheReuseSettingsContribution {

    static final String CONTRIBUTION_ID = "atlas-cache-reuse";
    static final String LABEL_KEY = "settings.atlas.cache-reuse";

    private AtlasCacheReuseSettingsContribution() {
    }

    /**
     * The toggle persists through {@link AtlasCacheReuseSettingsService#save(boolean)} when
     * the settings dialog is confirmed, so a storage failure propagates instead of being
     * reported as a successful change.
     */
    static SettingsContribution create(
        final PluginLocalization i18n,
        final AtlasCacheReuseSettingsService settings
    ) {
        Objects.requireNonNull(i18n, "i18n");
        Objects.requireNonNull(settings, "settings");
        return new SettingsContribution(
            CONTRIBUTION_ID,
            new SettingsTab(
                "performance",
                i18n.text("settings.tab.performance"),
                OptionalInt.of(200)
            ),
            OptionalInt.of(72),
            new SettingsControl.Toggle(
                CONTRIBUTION_ID,
                i18n.text(LABEL_KEY),
                SettingsBinding.of(settings::read, settings::save)
            )
        );
    }
}
