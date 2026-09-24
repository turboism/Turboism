package dev.turboism.shell;

import dev.turboism.internal.core.MeshTriangulationSettingsService;
import dev.turboism.sdk.i18n.PluginLocalization;
import dev.turboism.sdk.ui.settings.SettingsBinding;
import dev.turboism.sdk.ui.settings.SettingsContribution;
import dev.turboism.sdk.ui.settings.SettingsControl;
import dev.turboism.sdk.ui.settings.SettingsTab;

import java.util.Objects;
import java.util.OptionalInt;

/** Core-owned toggle for the mesh triangulation hash fix, rendered in the shared Performance tab. */
final class MeshTriangulationSettingsContribution {

    static final String CONTRIBUTION_ID = "mesh-triangulation-hash";
    static final String LABEL_KEY = "settings.mesh-triangulation.hash-degeneracy";

    private MeshTriangulationSettingsContribution() {
    }

    /**
     * The toggle persists through {@link MeshTriangulationSettingsService#save(boolean)} when the
     * settings dialog is confirmed, so a storage failure propagates instead of being reported as a
     * successful change.
     */
    static SettingsContribution create(
        final PluginLocalization i18n,
        final MeshTriangulationSettingsService settings
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
            OptionalInt.of(70),
            new SettingsControl.Toggle(
                CONTRIBUTION_ID,
                i18n.text(LABEL_KEY),
                SettingsBinding.of(settings::read, settings::save)
            )
        );
    }
}
