package dev.turboism.plugin.core;

import dev.turboism.sdk.i18n.PluginLocalization;
import dev.turboism.sdk.ui.settings.SettingsActionHandle;
import dev.turboism.sdk.ui.settings.SettingsActionProgress;
import dev.turboism.sdk.ui.settings.SettingsActionResult;
import dev.turboism.sdk.ui.settings.SettingsBinding;
import dev.turboism.sdk.ui.settings.SettingsChangeDecision;
import dev.turboism.sdk.ui.settings.SettingsContribution;
import dev.turboism.sdk.ui.settings.SettingsControl;
import dev.turboism.sdk.ui.settings.SettingsDecisionAction;
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
                        i18n.format(
                            "settings.cubism-jvm.graalvm-required-managed",
                            CubismJvmSettingsService.MANAGED_GRAAL_VERSION,
                            CubismJvmSettingsService.MANAGED_JAVA_VERSION
                        ),
                        new SettingsDecisionAction(
                            i18n.text("settings.cubism-jvm.graalvm-install"),
                            () -> install(i18n, settings)
                        ),
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

    private static SettingsActionHandle install(
        final PluginLocalization i18n,
        final CubismJvmSettingsService settings
    ) {
        final CubismJvmSettingsService.ManagedRuntimeOperation operation =
            settings.installManagedRuntime();
        return new SettingsActionHandle() {
            @Override
            public SettingsActionProgress progress() {
                final CubismJvmSettingsService.ManagedRuntimeStatus status = operation.status();
                return new SettingsActionProgress(
                    status.completedBytes(),
                    status.totalBytes(),
                    progressMessage(i18n, status)
                );
            }

            @Override
            public java.util.concurrent.CompletionStage<SettingsActionResult> completion() {
                return operation.completion().thenApply(status -> {
                    if (status.state() == CubismJvmSettingsService.ManagedRuntimeState.READY) {
                        settings.save(CubismJvmSettingsService.CubismJvm.GRAALVM);
                        return SettingsActionResult.succeeded(
                            i18n.text("settings.cubism-jvm.graalvm-install-success-title"),
                            i18n.format(
                                "settings.cubism-jvm.graalvm-install-success",
                                status.version(), status.javaVersion()
                            )
                        );
                    }
                    if (status.state() == CubismJvmSettingsService.ManagedRuntimeState.CANCELLED) {
                        return SettingsActionResult.failed(
                            i18n.text("settings.cubism-jvm.graalvm-install-cancelled-title"),
                            i18n.text("settings.cubism-jvm.graalvm-install-cancelled")
                        );
                    }
                    return SettingsActionResult.failed(
                        i18n.text("settings.cubism-jvm.graalvm-install-failed-title"),
                        i18n.format(
                            "settings.cubism-jvm.graalvm-install-failed",
                            status.code().isBlank() ? "GRAAL_RUNTIME_INSTALL_FAILED" : status.code()
                        )
                    );
                });
            }

            @Override public boolean cancel() { return operation.cancel(); }
        };
    }

    private static String progressMessage(
        final PluginLocalization i18n,
        final CubismJvmSettingsService.ManagedRuntimeStatus status
    ) {
        return switch (status.state()) {
            case INSTALLING -> status.totalBytes() > 0L
                && status.completedBytes() < status.totalBytes()
                    ? i18n.format(
                        "settings.cubism-jvm.graalvm-install-downloading",
                        mib(status.completedBytes()), mib(status.totalBytes())
                    )
                    : i18n.text("settings.cubism-jvm.graalvm-install-verifying");
            case READY -> i18n.text("settings.cubism-jvm.graalvm-install-ready");
            case CANCELLED -> i18n.text("settings.cubism-jvm.graalvm-install-cancelled");
            case FAILED -> i18n.format(
                "settings.cubism-jvm.graalvm-install-failed",
                status.code().isBlank() ? "GRAAL_RUNTIME_INSTALL_FAILED" : status.code()
            );
            case UNSUPPORTED -> i18n.text("settings.cubism-jvm.graalvm-install-unsupported");
            case ABSENT -> i18n.text("settings.cubism-jvm.graalvm-install-starting");
        };
    }

    private static long mib(final long bytes) {
        return (bytes + (1024L * 1024L - 1L)) / (1024L * 1024L);
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
