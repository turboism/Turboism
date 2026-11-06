package dev.turboism.shell;

import dev.turboism.config.optimization.Optimization;
import dev.turboism.config.optimization.OptimizationCatalog;
import dev.turboism.internal.core.CubismJvmSettingsService;
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

    private CubismJvmSettingsContribution() {}

    /**
     * The tooltip key a rendered control declares, or empty when it has none.
     *
     * <p>Optimization rows answer from the catalogue, which holds the key they were rendered
     * with; only the JVM selector, the memory-profile rows and the notes are declared here,
     * because this contribution is the one that builds them.</p>
     *
     * @param contributionId the id of the rendered control, or its transport note
     * @return the tooltip key, or empty when the control has no tooltip
     */
    static java.util.Optional<String> tooltipKey(final String contributionId) {
        if (contributionId == null) return java.util.Optional.empty();
        final java.util.Optional<Optimization> optimization = OptimizationCatalog.byContributionId(contributionId);
        if (optimization.isPresent()) {
            return optimization.map(Optimization::tooltipKey).filter(key -> !key.isEmpty());
        }
        return java.util.Optional.ofNullable(
                switch (contributionId) {
                    case "cubism-disable-auto-backup" -> "settings.cubism-jvm.disable-auto-backup.tooltip";
                    case "cubism-zgc" -> "settings.cubism-jvm.zgc.tooltip";
                    case CONTRIBUTION_ID -> "settings.cubism-jvm.tooltip";
                    case "cubism-memory-profile" -> "settings.cubism-jvm.memory-profile.tooltip";
                    case "performance-recommendation" -> "settings.performance.recommendation.tooltip";
                    case "performance.section.model" -> "settings.performance.section.model.tooltip";
                    case "performance.section.rendering" -> "settings.performance.section.rendering.tooltip";
                    case "performance.section.upload" -> "settings.performance.section.upload.tooltip";
                    case "performance.section.mesh" -> "settings.performance.section.mesh.tooltip";
                    case "performance.section.jvm" -> "settings.performance.section.jvm.tooltip";
                    default -> null;
                });
    }

    static SettingsContribution createPath(final PluginLocalization i18n, final CubismJvmSettingsService settings) {
        Objects.requireNonNull(i18n, "i18n");
        Objects.requireNonNull(settings, "settings");
        return new SettingsContribution(
                PATH_CONTRIBUTION_ID,
                new SettingsTab("performance", i18n.text("settings.tab.performance"), OptionalInt.of(200)),
                OptionalInt.of(114),
                new SettingsControl.Text(
                        PATH_CONTRIBUTION_ID,
                        i18n.text("settings.cubism-jvm.graalvm-path"),
                        36,
                        SettingsBinding.of(settings::graalVmPath, settings::saveGraalVmPath),
                        (current, proposed) -> settings.graalVmPathCompatible(proposed)
                                ? SettingsChangeDecision.allow()
                                : SettingsChangeDecision.rejected(
                                        i18n.text("settings.cubism-jvm.graalvm-path-invalid-title"),
                                        i18n.text("settings.cubism-jvm.graalvm-path-invalid"))));
    }

    static SettingsContribution create(final PluginLocalization i18n, final CubismJvmSettingsService settings) {
        Objects.requireNonNull(i18n, "i18n");
        Objects.requireNonNull(settings, "settings");
        return new SettingsContribution(
                CONTRIBUTION_ID,
                new SettingsTab("performance", i18n.text("settings.tab.performance"), OptionalInt.of(200)),
                OptionalInt.of(113),
                new SettingsControl.Choice(
                        CONTRIBUTION_ID,
                        i18n.text("settings.cubism-jvm"),
                        List.of(
                                new SettingsControl.Option(
                                        CubismJvmSettingsService.CubismJvm.GRAALVM.configValue(),
                                        i18n.text("settings.cubism-jvm.graalvm")),
                                new SettingsControl.Option(
                                        CubismJvmSettingsService.CubismJvm.BUNDLED.configValue(),
                                        i18n.text("settings.cubism-jvm.bundled"))),
                        SettingsBinding.of(
                                () -> acceptedInitial(settings).configValue(),
                                value -> settings.save(CubismJvmSettingsService.CubismJvm.fromConfig(value))),
                        (current, proposed) -> {
                            if (!CubismJvmSettingsService.CubismJvm.GRAALVM
                                            .configValue()
                                            .equals(proposed)
                                    || settings.graalVmAvailable()) {
                                return SettingsChangeDecision.allow();
                            }
                            return SettingsChangeDecision.rejected(
                                    i18n.text("settings.cubism-jvm.graalvm-required-title"),
                                    i18n.format(
                                            "settings.cubism-jvm.graalvm-required-managed",
                                            CubismJvmSettingsService.MANAGED_GRAAL_VERSION,
                                            CubismJvmSettingsService.MANAGED_JAVA_VERSION),
                                    new SettingsDecisionAction(
                                            i18n.text("settings.cubism-jvm.graalvm-install"),
                                            () -> install(i18n, settings)),
                                    new SettingsLink(
                                            i18n.text("settings.cubism-jvm.graalvm-open-download"),
                                            CubismJvmSettingsService.GRAALVM_DOWNLOAD_URI,
                                            i18n.format(
                                                    "settings.cubism-jvm.graalvm-open-failed",
                                                    CubismJvmSettingsService.GRAALVM_DOWNLOAD_URI)));
                        }));
    }

    /**
     * Opt-in toggle in the Performance tab: when on, the session disables the
     * host's periodic auto-backup entirely (crash-recovery trades for no
     * mid-edit backup stalls). Like the other page controls, edits persist for
     * the next launch; session startup applies through the verified backup service.
     */
    static SettingsContribution createBackupDisable(
            final PluginLocalization i18n, final CubismJvmSettingsService settings) {
        Objects.requireNonNull(i18n, "i18n");
        Objects.requireNonNull(settings, "settings");
        return new SettingsContribution(
                "cubism-disable-auto-backup",
                new SettingsTab("performance", i18n.text("settings.tab.performance"), OptionalInt.of(200)),
                OptionalInt.of(112),
                new SettingsControl.Toggle(
                        "cubism-disable-auto-backup",
                        i18n.text("settings.cubism-jvm.disable-auto-backup"),
                        SettingsBinding.of(settings::reduceAutoBackup, value -> {
                            settings.saveReduceAutoBackup(value);
                        })));
    }

    /**
     * Launch-time preference: appends {@code -XX:+UseZGC} and its companion
     * {@code -XX:ZUncommitDelay=60} to the managed JVM options the launcher
     * injects into the staged launch BAT on the next Cubism start, so freed
     * heap returns to the OS a minute after editing stops. No live apply —
     * the current JVM cannot change collectors mid-process.
     */
    static SettingsContribution createZgcToggle(
            final PluginLocalization i18n, final CubismJvmSettingsService settings) {
        Objects.requireNonNull(i18n, "i18n");
        Objects.requireNonNull(settings, "settings");
        return new SettingsContribution(
                "cubism-zgc",
                new SettingsTab("performance", i18n.text("settings.tab.performance"), OptionalInt.of(200)),
                OptionalInt.of(111),
                new SettingsControl.Toggle(
                        "cubism-zgc",
                        i18n.text("settings.cubism-jvm.zgc"),
                        SettingsBinding.of(settings::zgc, settings::saveZgc)));
    }

    /**
     * Launch-time preference: the managed launcher's memory tier for the next
     * Cubism start. {@code system} emits nothing so the official BAT keeps its
     * {@code -XX:MaxRAMPercentage=100} sizing; the capped tiers append
     * {@code -Xmx4g} (plus a shorter soft-reference LRU clock for the fast-soft
     * variant) to the managed JVM options. No live apply — the running JVM
     * cannot resize its heap. The tiers compose with the ZGC toggle; an
     * explicit {@code -Xmx} already present in the launch command wins over the
     * profile's heap limit.
     */
    static SettingsContribution createMemoryProfile(
            final PluginLocalization i18n, final CubismJvmSettingsService settings) {
        Objects.requireNonNull(i18n, "i18n");
        Objects.requireNonNull(settings, "settings");
        return new SettingsContribution(
                "cubism-memory-profile",
                new SettingsTab("performance", i18n.text("settings.tab.performance"), OptionalInt.of(200)),
                OptionalInt.of(115),
                new SettingsControl.Choice(
                        "cubism-memory-profile",
                        i18n.text("settings.cubism-jvm.memory-profile"),
                        List.of(
                                new SettingsControl.Option(
                                        CubismJvmSettingsService.MemoryProfile.SYSTEM.configValue(),
                                        i18n.text("settings.cubism-jvm.memory-profile.system")),
                                new SettingsControl.Option(
                                        CubismJvmSettingsService.MemoryProfile.BALANCED_4G.configValue(),
                                        i18n.text("settings.cubism-jvm.memory-profile.balanced4g")),
                                new SettingsControl.Option(
                                        CubismJvmSettingsService.MemoryProfile.BALANCED_4G_FAST_SOFT.configValue(),
                                        i18n.text("settings.cubism-jvm.memory-profile.balanced4g-fast-soft"))),
                        SettingsBinding.of(
                                () -> settings.memoryProfile().configValue(),
                                value -> settings.saveMemoryProfile(
                                        CubismJvmSettingsService.MemoryProfile.fromConfig(value)))));
    }

    /** Memory-profile semantics note under the selector; no binding, display only. */
    static SettingsContribution createMemoryProfileNote(final PluginLocalization i18n) {
        Objects.requireNonNull(i18n, "i18n");
        return new SettingsContribution(
                "cubism-memory-profile-note",
                new SettingsTab("performance", i18n.text("settings.tab.performance"), OptionalInt.of(200)),
                OptionalInt.of(116),
                new SettingsControl.Note(
                        "cubism-memory-profile-note", i18n.text("settings.cubism-jvm.memory-profile-note")));
    }

    /**
     * One header row on the Performance tab: the restart warning and the
     * recommended combinations, separated by {@code |}. The tooltip shows the
     * recommended combinations with line breaks.
     */
    static SettingsContribution createPerformanceRecommendationNote(final PluginLocalization i18n) {
        Objects.requireNonNull(i18n, "i18n");
        final String label = i18n.text("settings.performance.restart-note")
                + " | "
                + i18n.text("settings.performance.recommendation");
        return new SettingsContribution(
                "performance-recommendation",
                new SettingsTab("performance", i18n.text("settings.tab.performance"), OptionalInt.of(200)),
                OptionalInt.of(60),
                new SettingsControl.Note("performance-recommendation", label));
    }

    static String optimizationStatusMessage(final PluginLocalization i18n, final CubismJvmSettingsService settings) {
        final StringBuilder message =
                new StringBuilder(i18n.format("settings.performance.status-file", settings.globalConfigPath()));
        for (var status : settings.performanceStatuses()) {
            message.append("\n\n")
                    .append(i18n.format(
                            "settings.performance.status-all-row",
                            status.key(),
                            status.saved(),
                            status.requested(),
                            status.source(),
                            status.actual(),
                            status.pendingRestart()));
        }
        return message.toString();
    }

    private static SettingsActionHandle install(
            final PluginLocalization i18n, final CubismJvmSettingsService settings) {
        final CubismJvmSettingsService.ManagedRuntimeOperation operation = settings.installManagedRuntime();
        return new SettingsActionHandle() {
            @Override
            public SettingsActionProgress progress() {
                final CubismJvmSettingsService.ManagedRuntimeStatus status = operation.status();
                return new SettingsActionProgress(
                        status.completedBytes(), status.totalBytes(), progressMessage(i18n, status));
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
                                        status.version(),
                                        status.javaVersion()));
                    }
                    if (status.state() == CubismJvmSettingsService.ManagedRuntimeState.CANCELLED) {
                        return SettingsActionResult.failed(
                                i18n.text("settings.cubism-jvm.graalvm-install-cancelled-title"),
                                i18n.text("settings.cubism-jvm.graalvm-install-cancelled"));
                    }
                    return SettingsActionResult.failed(
                            i18n.text("settings.cubism-jvm.graalvm-install-failed-title"),
                            i18n.format(
                                    "settings.cubism-jvm.graalvm-install-failed",
                                    status.code().isBlank() ? "GRAAL_RUNTIME_INSTALL_FAILED" : status.code()));
                });
            }

            @Override
            public boolean cancel() {
                return operation.cancel();
            }
        };
    }

    private static String progressMessage(
            final PluginLocalization i18n, final CubismJvmSettingsService.ManagedRuntimeStatus status) {
        return switch (status.state()) {
            case INSTALLING ->
                status.totalBytes() > 0L && status.completedBytes() < status.totalBytes()
                        ? i18n.format(
                                "settings.cubism-jvm.graalvm-install-downloading",
                                mib(status.completedBytes()),
                                mib(status.totalBytes()))
                        : i18n.text("settings.cubism-jvm.graalvm-install-verifying");
            case READY -> i18n.text("settings.cubism-jvm.graalvm-install-ready");
            case CANCELLED -> i18n.text("settings.cubism-jvm.graalvm-install-cancelled");
            case FAILED ->
                i18n.format(
                        "settings.cubism-jvm.graalvm-install-failed",
                        status.code().isBlank() ? "GRAAL_RUNTIME_INSTALL_FAILED" : status.code());
            case UNSUPPORTED -> i18n.text("settings.cubism-jvm.graalvm-install-unsupported");
            case ABSENT -> i18n.text("settings.cubism-jvm.graalvm-install-starting");
        };
    }

    private static long mib(final long bytes) {
        return (bytes + (1024L * 1024L - 1L)) / (1024L * 1024L);
    }

    static CubismJvmSettingsService.CubismJvm acceptedInitial(final CubismJvmSettingsService settings) {
        final CubismJvmSettingsService.CubismJvm saved = settings.read();
        return saved == CubismJvmSettingsService.CubismJvm.GRAALVM && !settings.graalVmAvailable()
                ? CubismJvmSettingsService.CubismJvm.BUNDLED
                : saved;
    }
}
