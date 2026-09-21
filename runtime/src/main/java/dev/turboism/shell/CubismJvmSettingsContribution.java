package dev.turboism.shell;

import dev.turboism.adapter.cubism.optimization.modelupdate.ModelUpdateSkipBridge;
import dev.turboism.adapter.cubism.optimization.modelupdate.incremental.IncrementalUpdateBridge;
import dev.turboism.adapter.cubism.optimization.uniform.UniformLocationHookBridge;
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
    static final String PATH_CONTRIBUTION_ID = "cubism-graalvm-path";

    private CubismJvmSettingsContribution() {
    }

    static SettingsContribution createPath(
        final PluginLocalization i18n,
        final CubismJvmSettingsService settings
    ) {
        Objects.requireNonNull(i18n, "i18n");
        Objects.requireNonNull(settings, "settings");
        return new SettingsContribution(
            PATH_CONTRIBUTION_ID,
            new SettingsTab(
                "performance",
                i18n.text("settings.tab.performance"),
                OptionalInt.of(200)
            ),
            OptionalInt.of(90),
            new SettingsControl.Text(
                PATH_CONTRIBUTION_ID,
                i18n.text("settings.cubism-jvm.graalvm-path")
                    + " ("
                    + i18n.text("settings.locale.restart-required")
                    + ")",
                36,
                SettingsBinding.of(settings::graalVmPath, settings::saveGraalVmPath),
                (current, proposed) -> settings.graalVmPathCompatible(proposed)
                    ? SettingsChangeDecision.allow()
                    : SettingsChangeDecision.rejected(
                        i18n.text("settings.cubism-jvm.graalvm-path-invalid-title"),
                        i18n.text("settings.cubism-jvm.graalvm-path-invalid")
                    )
            )
        );
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

    /**
     * Opt-in toggle in the Performance tab: when on, the session disables the
     * host's periodic auto-backup entirely (crash-recovery trades for no
     * mid-edit backup stalls). The writer persists first, then applies live
     * through the verified backup service so a rejected apply cannot fake a
     * saved state.
     */
    static SettingsContribution createBackupDisable(
        final PluginLocalization i18n,
        final CubismJvmSettingsService settings,
        final java.util.function.Consumer<Boolean> apply
    ) {
        Objects.requireNonNull(i18n, "i18n");
        Objects.requireNonNull(settings, "settings");
        Objects.requireNonNull(apply, "apply");
        return new SettingsContribution(
            "cubism-disable-auto-backup",
            new SettingsTab(
                "performance",
                i18n.text("settings.tab.performance"),
                OptionalInt.of(200)
            ),
            OptionalInt.of(110),
            new SettingsControl.Toggle(
                "cubism-disable-auto-backup",
                i18n.text("settings.cubism-jvm.disable-auto-backup"),
                SettingsBinding.of(
                    settings::reduceAutoBackup,
                    value -> {
                        settings.saveReduceAutoBackup(value);
                        apply.accept(value);
                    }
                )
            )
        );
    }

    /**
     * Launch-time preference: appends {@code -XX:+UseZGC} to the managed
     * JAVA_TOOL_OPTIONS block on the next Cubism launch. No live apply —
     * the current JVM cannot change collectors mid-process.
     */
    static SettingsContribution createZgcToggle(
        final PluginLocalization i18n,
        final CubismJvmSettingsService settings
    ) {
        Objects.requireNonNull(i18n, "i18n");
        Objects.requireNonNull(settings, "settings");
        return new SettingsContribution(
            "cubism-zgc",
            new SettingsTab(
                "performance",
                i18n.text("settings.tab.performance"),
                OptionalInt.of(200)
            ),
            OptionalInt.of(120),
            new SettingsControl.Toggle(
                "cubism-zgc",
                i18n.text("settings.cubism-jvm.zgc"),
                SettingsBinding.of(settings::zgc, settings::saveZgc)
            )
        );
    }

    /** Small header note on the Performance tab; no binding, display only. */
    static SettingsContribution createPerformanceNote(final PluginLocalization i18n) {
        Objects.requireNonNull(i18n, "i18n");
        return new SettingsContribution(
            "performance-restart-note",
            new SettingsTab(
                "performance",
                i18n.text("settings.tab.performance"),
                OptionalInt.of(200)
            ),
            OptionalInt.of(60),
            new SettingsControl.Note(
                "performance-restart-note",
                i18n.text("settings.performance.restart-note")
            )
        );
    }

    /**
     * Launch-time preference toggles for the verified model-update
     * optimizations. Verified unchanged-frame reuse defaults on; experimental
     * incremental updates require opt-in. The writer persists the
     * launcher preference (next launch emits the {@code -D...=false}
     * opt-out) and flips the process system property so an installed
     * hook also stops live.
     */
    static SettingsContribution createModelUpdateSkipToggle(
        final PluginLocalization i18n,
        final CubismJvmSettingsService settings
    ) {
        Objects.requireNonNull(settings, "settings");
        return createOptimizationToggle(
            i18n,
            "model-update-skip",
            "settings.optimization.model-update-skip",
            ModelUpdateSkipBridge.ENABLE_PROPERTY,
            settings::modelUpdateSkip,
            settings::saveModelUpdateSkip,
            true,
            80
        );
    }

    static SettingsContribution createIncrementalUpdateToggle(
        final PluginLocalization i18n,
        final CubismJvmSettingsService settings
    ) {
        Objects.requireNonNull(settings, "settings");
        return createOptimizationToggle(
            i18n,
            "incremental-update",
            "settings.optimization.incremental-update",
            IncrementalUpdateBridge.ENABLE_PROPERTY,
            settings::incrementalUpdate,
            settings::saveIncrementalUpdate,
            false,
            81
        );
    }

    /** Saved default-on preference; unsupported hosts still use their native renderer. */
    static SettingsContribution createUniformLocationCacheToggle(
        final PluginLocalization i18n,
        final CubismJvmSettingsService settings
    ) {
        Objects.requireNonNull(settings, "settings");
        return createOptimizationToggle(
            i18n,
            "uniform-location-cache",
            "settings.optimization.uniform-location-cache",
            UniformLocationHookBridge.ENABLE_PROPERTY,
            settings::uniformLocationCache,
            value -> {
                if (settings.saveUniformLocationCache(value) != value) {
                    throw new IllegalStateException("Uniform-location preference was not saved");
                }
                return value;
            },
            true,
            82
        );
    }

    private static SettingsContribution createOptimizationToggle(
        final PluginLocalization i18n,
        final String id,
        final String labelKey,
        final String enableProperty,
        final java.util.function.BooleanSupplier getter,
        final java.util.function.Function<Boolean, Boolean> setter,
        final boolean defaultValue,
        final int index
    ) {
        Objects.requireNonNull(i18n, "i18n");
        return new SettingsContribution(
            id,
            new SettingsTab(
                "performance",
                i18n.text("settings.tab.performance"),
                OptionalInt.of(200)
            ),
            OptionalInt.of(index),
            new SettingsControl.Toggle(
                id,
                i18n.text(labelKey),
                SettingsBinding.of(
                    getter::getAsBoolean,
                    value -> {
                        setter.apply(value);
                        if (value == defaultValue) System.clearProperty(enableProperty);
                        else System.setProperty(enableProperty, Boolean.toString(value));
                    }
                )
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
