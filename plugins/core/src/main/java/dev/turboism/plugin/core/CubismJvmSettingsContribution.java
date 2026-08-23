package dev.turboism.plugin.core;

import dev.turboism.sdk.i18n.PluginLocalization;
import dev.turboism.sdk.ui.settings.SettingsBinding;
import dev.turboism.sdk.ui.settings.SettingsChangeDecision;
import dev.turboism.sdk.ui.settings.SettingsContribution;
import dev.turboism.sdk.ui.settings.SettingsControl;
import dev.turboism.sdk.ui.settings.SettingsLink;
import dev.turboism.sdk.ui.settings.SettingsTab;

import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;

/** Core-owned declarative Cubism JVM selector rendered in the shared Performance tab. */
final class CubismJvmSettingsContribution {
    static final String CONTRIBUTION_ID = "cubism-jvm";

    private CubismJvmSettingsContribution() {
    }

    static SettingsContribution create(
        final PluginLocalization i18n,
        final CubismJvmSettingsService settings
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
            OptionalInt.of(100),
            new SettingsControl.Choice(
                CONTRIBUTION_ID,
                i18n.text("settings.cubism-jvm")
                    + " ("
                    + i18n.text("settings.locale.restart-required")
                    + ")",
                List.of(
                    new SettingsControl.Option(
                        CubismJvmSettingsService.CubismJvm.GRAALVM.configValue(),
                        i18n.text("settings.cubism-jvm.graalvm")
                    ),
                    new SettingsControl.Option(
                        CubismJvmSettingsService.CubismJvm.BUNDLED.configValue(),
                        i18n.text("settings.cubism-jvm.bundled")
                    )
                ),
                SettingsBinding.of(
                    () -> acceptedInitial(settings).configValue(),
                    value -> settings.save(CubismJvmSettingsService.CubismJvm.fromConfig(value))
                ),
                (current, proposed) -> {
                    if (!CubismJvmSettingsService.CubismJvm.GRAALVM.configValue().equals(proposed)
                        || settings.graalVmAvailable()) {
                        return SettingsChangeDecision.allow();
                    }
                    return SettingsChangeDecision.rejected(
                        i18n.text("settings.cubism-jvm.graalvm-required-title"),
                        i18n.text("settings.cubism-jvm.graalvm-required"),
                        new SettingsLink(
                            i18n.text("settings.cubism-jvm.graalvm-open-download"),
                            CubismJvmSettingsService.GRAALVM_DOWNLOAD_URI,
                            i18n.format(
                                "settings.cubism-jvm.graalvm-open-failed",
                                CubismJvmSettingsService.GRAALVM_DOWNLOAD_URI
                            )
                        )
                    );
                }
            )
        );
    }

    static CubismJvmSettingsService.CubismJvm acceptedInitial(
        final CubismJvmSettingsService settings
    ) {
        final CubismJvmSettingsService.CubismJvm saved = settings.read();
        return saved == CubismJvmSettingsService.CubismJvm.GRAALVM
            && !settings.graalVmAvailable()
                ? CubismJvmSettingsService.CubismJvm.BUNDLED
                : saved;
    }
}
