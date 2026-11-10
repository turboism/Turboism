package dev.turboism.kernel;

import dev.turboism.adapter.HostVerificationEvidence;
import dev.turboism.adapter.host.HostInstanceDescriptor;
import dev.turboism.adapter.host.HostSession;
import dev.turboism.bootstrap.HostRuntimeIngress;
import dev.turboism.core.diagnostics.PluginWorkBudgetEvent;
import dev.turboism.core.runtime.DefaultWorkBudgetPolicy;
import dev.turboism.core.runtime.RuntimeScheduler;
import dev.turboism.core.runtime.work.PluginWorkExecutorRegistry;
import dev.turboism.home.LegacyHomeMigration;
import dev.turboism.home.TurboismHomeLayout;
import dev.turboism.kernel.report.KernelReportWriter;
import dev.turboism.mapping.verification.CompatibilityResolution;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The startup sequence behind {@link KernelRuntime#start}: admission, host-services preparation,
 * plugin loading, initial reports and the startup banner.
 *
 * <p>The sequence is a package-private collaborator rather than part of the runtime object because
 * it owns only transient bootstrap state. It returns a fully started {@link KernelRuntime} whose
 * mutable bindings are already installed, so a failed bootstrap leaves no half-started runtime
 * reachable by a caller.</p>
 */
final class KernelStartupSequence {

    private static final StartupBanner STARTUP_BANNER = new StartupBanner();

    private KernelStartupSequence() {}

    /**
     * Prepares the host services, runs trusted bootstrap installation, then loads plugins.
     * A failed bootstrap callback prevents plugin initialization and tears down the prepared
     * runtime. The caller owns any hook handles installed by its callback and must close them
     * if this method fails.
     *
     * @param requestedHome runtime home directory
     * @param verificationRecord project/workspace mapping record
     * @param editorModelVerificationRecord editor-model mapping record
     * @param coreRuntimeVerificationRecord optional Core mapping record
     * @param mainToolbarVerificationRecord optional main-toolbar mapping record
     * @param embeddedPanelVerificationRecord optional embedded-panel mapping record
     * @param topMenuVerificationRecord optional top-menu mapping record
     * @param boundingBoxOverlayVerificationRecord optional overlay mapping record
     * @param statusBarVerificationRecord optional status-bar mapping record
     * @param clipMaskVerificationRecord optional clip-mask mapping record
     * @param autoBackupVerificationRecord optional auto-backup mapping record
     * @param protectedExportVerificationRecord optional protected-export mapping record
     * @param hostArtifact actual Editor artifact
     * @param coreArtifact actual Core artifact when present
     * @param hostClassLoader loader owning the host classes
     * @param hostResolution admission decision, or null for the legacy exact path
     * @param beforePlugins trusted bootstrap work that must finish before plugin code runs
     * @param afterPlugins work that runs once every plugin has loaded
     * @return the started runtime
     * @throws IOException if runtime files cannot be prepared
     */
    static KernelRuntime start(
            final Path requestedHome,
            final Path verificationRecord,
            final Path editorModelVerificationRecord,
            final Path coreRuntimeVerificationRecord,
            final Path mainToolbarVerificationRecord,
            final Path embeddedPanelVerificationRecord,
            final Path topMenuVerificationRecord,
            final Path boundingBoxOverlayVerificationRecord,
            final Optional<Path> statusBarVerificationRecord,
            final Optional<Path> clipMaskVerificationRecord,
            final Path autoBackupVerificationRecord,
            final Optional<Path> protectedExportVerificationRecord,
            final Path hostArtifact,
            final Path coreArtifact,
            final ClassLoader hostClassLoader,
            final CompatibilityResolution hostResolution,
            final java.util.function.Consumer<KernelRuntime> beforePlugins,
            final java.util.function.Consumer<KernelRuntime> afterPlugins)
            throws IOException {
        Objects.requireNonNull(beforePlugins, "beforePlugins");
        Objects.requireNonNull(afterPlugins, "afterPlugins");
        Objects.requireNonNull(statusBarVerificationRecord, "statusBarVerificationRecord");
        Objects.requireNonNull(clipMaskVerificationRecord, "clipMaskVerificationRecord");
        Objects.requireNonNull(protectedExportVerificationRecord, "protectedExportVerificationRecord");
        final Path normalizedHostArtifact = Objects.requireNonNull(hostArtifact, "hostArtifact")
                .toAbsolutePath()
                .normalize();
        KernelHostAdmission.requireAdmittedEditorRelease(normalizedHostArtifact, hostResolution);
        final TurboismHomeLayout layout = TurboismHomeLayout.create(requestedHome);
        final Path home = layout.home();
        LegacyHomeMigration.migrate(home);
        final var pendingPlugins = new dev.turboism.pluginmanagement.PendingPluginOperations(home).apply();
        KernelRuntime.requireSafePendingPlugins(pendingPlugins);

        final ClassLoader verifiedHostClassLoader = Objects.requireNonNull(hostClassLoader, "hostClassLoader");
        final KernelLog log = KernelLog.openSession(
                layout.runtimeLogsDir(),
                Clock.systemUTC(),
                ProcessHandle.current().pid(),
                KernelLog.Sink.NONE);
        final var runtimeConfig = new dev.turboism.config.RuntimeConfigRepository(
                        home, diagnostic -> log.warn("config", diagnostic))
                .read();
        log.setMinimumLevel(runtimeConfig.path("logLevel").asText("INFO"));
        log.setMaxStorageMiB(runtimeConfig
                .path("maxLogStorageMiB")
                .asInt(dev.turboism.sdk.runtime.RuntimeSettings.DEFAULT_MAX_LOG_STORAGE_MIB));
        if (!pendingPlugins.applied()) {
            log.warn(
                    "plugin-management",
                    pendingPlugins.code()
                            + ": installed plugins kept; failed intent retained. Review pending changes in Plugin Management.");
        }
        dev.turboism.runtime.log.RuntimeDiagnostics.install(log);
        RuntimeScheduler scheduler = null;
        HostRuntimeIngress ingress = null;
        LocalPluginRuntime plugins = null;
        final StartupPhaseTimer startupTimer = new StartupPhaseTimer(System::nanoTime);
        try {
            // Provisional: Cubism writes the Environment Settings language onto the
            // process default locale as the editor starts, which is later than this
            // first resolution. Re-resolved once the host is verified and ACTIVE.
            java.util.concurrent.atomic.AtomicReference<java.util.Locale> effectiveLocale =
                    new java.util.concurrent.atomic.AtomicReference<>(
                            resolveEffectiveLocale(runtimeConfig, message -> log.warn("i18n", message)));
            log.info("i18n", "Using startup locale " + effectiveLocale.get().toLanguageTag());
            log.info("runtime", "Starting Turboism 0.1 Developer Preview at " + home);
            startupTimer.completed("configuration", message -> log.info("startup", message));
            // Inject the persisted theme before the Cubism GL scene initializes so
            // the off-canvas background color (cached in a singleton Lazy) takes
            // effect on restart, matching the legacy hook agent's startup timing.
            new dev.turboism.ui.appearance.EarlyThemeAppearanceBootstrap(
                            home,
                            verifiedHostClassLoader,
                            () -> log.info("runtime", "Early theme appearance injected from persisted selection"))
                    .start();
            // One-shot L&F readiness repair: hosts like Cubism 5.3.02 construct
            // windows on the EDT before FlatLaf's UI defaults are installed, so
            // early components get no ComponentUI and stay blank; once FlatLaf is
            // ready, updateUI() reinstalls every already-built component's UI.
            // No colors or themes are injected; version-neutral and fail-open.
            new dev.turboism.ui.appearance.LafReadinessRepair(
                            verifiedHostClassLoader, message -> log.info("appearance", message))
                    .start();
            scheduler = createScheduler(log);
            RecentPreviewDiagnostics.install(message -> log.warn("recent-preview", message));
            ingress = new HostRuntimeIngress(
                    effectiveLocale::get,
                    new dev.turboism.config.ConfigTextureAtlasSelectionStore(
                            home, diagnostic -> log.warn("config", diagnostic)));
            startupTimer.completed("appearance-and-services", message -> log.info("startup", message));

            final Path normalizedVerificationRecord = Objects.requireNonNull(verificationRecord, "verificationRecord")
                    .toAbsolutePath()
                    .normalize();
            final Path normalizedCoreArtifact =
                    coreArtifact == null ? null : coreArtifact.toAbsolutePath().normalize();
            final HostVerificationEvidence.Slice projectWorkspace = KernelHostAdmission.editorSlice(
                    normalizedVerificationRecord,
                    normalizedHostArtifact,
                    verifiedHostClassLoader,
                    hostResolution,
                    "project-workspace");
            final HostVerificationEvidence.Slice editorModel = KernelHostAdmission.editorSlice(
                    Objects.requireNonNull(editorModelVerificationRecord, "editorModelVerificationRecord")
                            .toAbsolutePath()
                            .normalize(),
                    normalizedHostArtifact,
                    verifiedHostClassLoader,
                    hostResolution,
                    "editor-model");
            // Compatibility-bound hosts admit slices individually: a record is
            // only present when the slice's full contract was verified, so
            // optional slices accumulate conditionally instead of requiring
            // every record path up front.
            HostVerificationEvidence evidence = HostVerificationEvidence.withEditorModel(projectWorkspace, editorModel);
            if (coreRuntimeVerificationRecord != null && normalizedCoreArtifact != null) {
                evidence = evidence.addingCoreRuntime(KernelHostAdmission.editorSlice(
                        coreRuntimeVerificationRecord.toAbsolutePath().normalize(),
                        normalizedCoreArtifact,
                        verifiedHostClassLoader,
                        hostResolution,
                        "core-model-read"));
            }
            if (mainToolbarVerificationRecord != null) {
                evidence = evidence.addingMainToolbar(KernelHostAdmission.editorSlice(
                        mainToolbarVerificationRecord.toAbsolutePath().normalize(),
                        normalizedHostArtifact,
                        verifiedHostClassLoader,
                        hostResolution,
                        "ui-main-toolbar"));
            }
            if (embeddedPanelVerificationRecord != null) {
                evidence = evidence.addingEmbeddedPanel(KernelHostAdmission.editorSlice(
                        embeddedPanelVerificationRecord.toAbsolutePath().normalize(),
                        normalizedHostArtifact,
                        verifiedHostClassLoader,
                        hostResolution,
                        "ui-embedded-panel"));
            }
            if (topMenuVerificationRecord != null) {
                evidence = evidence.addingTopMenu(KernelHostAdmission.editorSlice(
                        topMenuVerificationRecord.toAbsolutePath().normalize(),
                        normalizedHostArtifact,
                        verifiedHostClassLoader,
                        hostResolution,
                        "ui-top-menu"));
            }
            if (boundingBoxOverlayVerificationRecord != null) {
                evidence = evidence.addingBoundingBoxOverlayButton(KernelHostAdmission.editorSlice(
                        boundingBoxOverlayVerificationRecord.toAbsolutePath().normalize(),
                        normalizedHostArtifact,
                        verifiedHostClassLoader,
                        hostResolution,
                        "ui-bounding-box-overlay"));
            }
            final HostVerificationEvidence evidenceWithCore = evidence;
            final HostVerificationEvidence evidenceWithStatus = statusBarVerificationRecord
                    .map(record -> record.toAbsolutePath().normalize())
                    .map(record -> evidenceWithCore.addingStatusBar(KernelHostAdmission.editorSlice(
                            record, normalizedHostArtifact, verifiedHostClassLoader, hostResolution, "ui-status-bar")))
                    .orElse(evidenceWithCore);
            final HostVerificationEvidence evidenceWithClipMask = clipMaskVerificationRecord
                    .map(record -> record.toAbsolutePath().normalize())
                    .map(record -> evidenceWithStatus.addingClipMask(KernelHostAdmission.editorSlice(
                            record, normalizedHostArtifact, verifiedHostClassLoader, hostResolution, "clipmask")))
                    .orElse(evidenceWithStatus);
            final HostVerificationEvidence evidenceWithAutoBackup = autoBackupVerificationRecord == null
                    ? evidenceWithClipMask
                    : evidenceWithClipMask.addingAutoBackup(KernelHostAdmission.editorSlice(
                            autoBackupVerificationRecord.toAbsolutePath().normalize(),
                            normalizedHostArtifact,
                            verifiedHostClassLoader,
                            hostResolution,
                            "autobackup"));
            final HostSession.State hostState = ingress.publish(new HostInstanceDescriptor(
                    "cubism-" + ProcessHandle.current().pid(), evidenceWithAutoBackup));
            if (hostState == HostSession.State.ACTIVE) {
                log.info("host", "Verified Cubism project/workspace adapter connected");
            } else {
                final String failure = ingress.lastFailure()
                        .map(value -> value.code() + ": " + value.message())
                        .orElse("No detailed failure");
                log.warn("host", "Host adapter entered " + hostState + ": " + failure);
            }
            if (hostState != HostSession.State.ACTIVE) {
                throw new IllegalStateException("Cubism host admission failed before plugin loading");
            }
            startupTimer.completed("host-adapters", message -> log.info("startup", message));

            // The host class is observable, and this runtime therefore starts, before
            // Cubism finishes applying File -> Environment Settings -> General ->
            // Language to the process default locale. Re-resolve now that the host is
            // verified and ACTIVE so plugin message bundles follow the language the
            // user actually sees; an explicit -Dturboism.locale or config locale still
            // outranks the host in resolveStartup. The first resolution already
            // reported operator/config diagnostics, hence the silent sink here.
            final java.util.Locale hostVerifiedLocale = resolveEffectiveLocale(runtimeConfig, message -> {});
            if (!hostVerifiedLocale.equals(effectiveLocale.get())) {
                log.info(
                        "i18n",
                        "Host locale " + hostVerifiedLocale.toLanguageTag() + " supersedes the startup locale "
                                + effectiveLocale.get().toLanguageTag());
                effectiveLocale.set(hostVerifiedLocale);
            }

            final dev.turboism.sdk.cubism.filechooser.FileChooserHistoryService fileChooserHistory =
                    KernelRuntime.createFileChooserHistoryService(home, log);
            plugins = new LocalPluginRuntime(
                    home,
                    scheduler,
                    ingress.adapterAccess(),
                    log,
                    ingress.adapterAccess().parameterLifecycle(),
                    fileChooserHistory,
                    effectiveLocale.get(),
                    CoreShellRuntime.frameworkAdmission());
            // Host-level export-settings policy. It is created before plugin loading so every
            // plugin's contribution registry is reachable from the native dialog, and identity is
            // read from the same live host snapshots the rest of the runtime uses.
            final dev.turboism.exportsettings.RuntimeExportSettingsAuthority exportSettings =
                    new dev.turboism.exportsettings.RuntimeExportSettingsAuthority(
                            new dev.turboism.exportsettings.HostDocumentExportSettingsIdentitySource(
                                    dev.turboism.adapter.host.HostSessionSnapshotSource.forSession(
                                            ingress.adapterAccess().adapters().projectWorkspace())));
            plugins.bindExportSettingsAuthority(exportSettings);
            // The user-visible veto surface: every rejected/failed protected-export
            // confirmation names its bounded reason instead of dying silently.
            final java.util.function.Consumer<dev.turboism.exportsettings.ExportSettingsVetoDiagnostic> vetoSurface =
                    dev.turboism.exportsettings.ExportSettingsVetoDialog::present;
            exportSettings.vetoReporter(vetoSurface);
            final KernelReportWriter reportWriter = new KernelReportWriter(
                    layout.runtimeStateDir(),
                    diagnostic -> log.warn(
                            "preview-report",
                            diagnostic.code() + " " + diagnostic.reportType() + ": " + diagnostic.message()));
            final KernelRuntime runtime = new KernelRuntime(
                    home,
                    log,
                    scheduler,
                    ingress,
                    plugins,
                    new LocalPluginRuntime.LoadReport(List.of(), List.of(), List.of()),
                    reportWriter,
                    "runtime-" + UUID.randomUUID(),
                    normalizedVerificationRecord,
                    normalizedHostArtifact,
                    effectiveLocale.get());
            // The orchestrator is a degraded-capability seam: when the record is absent or the
            // pinned chain fails, no orchestrator is bound and checked export stays rejected. It is
            // built once the runtime exists because the orchestration owner is read from the
            // capability table at export time, and plugins only load afterwards.
            final dev.turboism.exportsettings.ProtectedExportOrchestrator protectedExport =
                    KernelProtectedExportFactory.createProtectedExportOrchestrator(
                            protectedExportVerificationRecord,
                            normalizedHostArtifact,
                            verifiedHostClassLoader,
                            exportSettings,
                            ingress,
                            layout,
                            log,
                            vetoSurface,
                            runtime::loadReport);
            if (protectedExport != null) {
                exportSettings.protectedExportOrchestrator(protectedExport);
            }
            runtime.bindFileChooserHistoryService(fileChooserHistory);
            runtime.bindExportSettingsAuthority(exportSettings);
            runtime.bindProtectedExportOrchestrator(protectedExport);
            bindAutoMeshGuard(runtime, plugins, ingress, scheduler, effectiveLocale.get(), log);
            runtime.loadPluginsAfterBootstrap(beforePlugins, afterPlugins);
            startupTimer.completed("plugin-loading", message -> log.info("startup", message));
            // Only publish startup after the hooks and plugin listeners are ready.
            ingress.adapterAccess()
                    .editorLifecycleEvents()
                    .publishStartup(KernelHostAdmission.startupHostVersion(normalizedHostArtifact, hostResolution));
            runtime.writeInitialReports(hostState);
            publishStartupBanner(plugins, log, runtime, verifiedHostClassLoader);
            publishNativeStartupNotice(verifiedHostClassLoader, log);
            startupTimer.completed("reports-and-banner", message -> log.info("startup", message));
            KernelRuntime.confirmFrameworkHealthAfterPluginApply(
                    pendingPlugins,
                    () -> dev.turboism.update.FrameworkUpdateHealth.startupSucceeded(
                            home, message -> log.info("updates", message)),
                    message -> log.warn("updates", message));
            return runtime;
        } catch (RuntimeException | Error failure) {
            KernelShutdownOrchestrator.abortStart(plugins, ingress, scheduler, log, failure);
            RecentPreviewDiagnostics.uninstall();
            throw failure;
        }
    }

    /**
     * Binds the Auto Mesh guard policy to the runtime services the Editor already runs.
     *
     * <p>The policy is bound before plugins load, so the transformed funnels never report to an
     * unbound runtime once a custom algorithm is registered. A host whose Editor-model resolver is
     * unavailable keeps the unbound state, which answers "not handled" for every confirmation and
     * therefore leaves the Editor's own Auto Mesh Generate untouched.</p>
     *
     * <p>The session selection is bound even when the policy is not, because the selector row reads
     * it independently of whether the guard can resolve host targets.</p>
     */
    private static void bindAutoMeshGuard(
            final KernelRuntime runtime,
            final LocalPluginRuntime plugins,
            final HostRuntimeIngress ingress,
            final RuntimeScheduler scheduler,
            final java.util.Locale locale,
            final KernelLog log) {
        runtime.bindAutoMeshSelection(plugins.autoMeshSelection());
        final dev.turboism.mapping.verification.VerifiedMemberResolver resolver;
        try {
            resolver = ingress.editorModelResolver();
        } catch (IllegalStateException unavailable) {
            log.warn("automesh", "Auto Mesh guard stays unbound: the Editor-model resolver is unavailable");
            return;
        }
        runtime.bindAutoMeshGuard(KernelAutoMeshGuardFactory.create(
                plugins.autoMeshSelection(),
                resolver,
                ingress.adapterAccess().modelAccess(),
                notification ->
                        ingress.adapterAccess().adapters().statusToolbar().notifyStatus(notification),
                scheduler,
                locale,
                message -> log.warn("automesh", message)));
        log.info("automesh", "Auto Mesh guard policy bound to the runtime dispatch");
    }

    /**
     * Resolves the runtime's effective locale from the configuration, the host language and
     * the JVM display locale, in the frozen {@code PluginLocaleResolver} precedence.
     *
     * @param runtimeConfigTurboism runtime configuration
     * @param diagnostics receives the resolver's locale-source diagnostics
     * @return the resolved locale, never {@code null}
     */
    private static java.util.Locale resolveEffectiveLocale(
            final com.fasterxml.jackson.databind.node.ObjectNode runtimeConfigTurboism,
            final java.util.function.Consumer<String> diagnostics) {
        return dev.turboism.i18n.PluginLocaleResolver.resolveStartup(
                runtimeConfigTurboism.path("locale").asText(""),
                dev.turboism.i18n.CubismHostLocale.resolve(),
                java.util.Locale.getDefault(java.util.Locale.Category.DISPLAY),
                diagnostics);
    }

    private static RuntimeScheduler createScheduler(final KernelLog log) {
        final Clock clock = Clock.systemUTC();
        final java.util.function.Consumer<PluginWorkBudgetEvent> diagnosticSink = event -> log.warn(
                "scheduler", event.pluginId() + " " + event.taskId() + " " + event.phase() + " " + event.decision());
        return new RuntimeScheduler(
                new DefaultWorkBudgetPolicy(),
                // Plugin actions are dispatched asynchronously and commonly invoke
                // host operations (undo/redo, menu work) that legitimately take longer
                // than the 500ms registry default; a too-tight timeout timed the
                // history panel's undo/redo actions out and tripped the circuit
                // breaker (CIRCUIT_OPEN rejected every later action).
                new PluginWorkExecutorRegistry(5_000L, 1, 64, diagnosticSink, clock),
                diagnosticSink);
    }

    private static void publishNativeStartupNotice(final ClassLoader hostClassLoader, final KernelLog log) {
        CubismLoggerBridge bridge = null;
        try {
            bridge = CubismLoggerBridge.connect(hostClassLoader);
            bridge.write(
                    KernelLog.Level.INFO,
                    "runtime",
                    "Turboism started. Open Turboism Logs for framework details.",
                    null);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            log.debug("runtime", "Native startup notice was unavailable");
        } finally {
            if (bridge != null) {
                try {
                    bridge.close();
                } catch (RuntimeException | LinkageError failure) {
                    log.debug("runtime", "Native startup logger cleanup was unavailable");
                }
            }
        }
    }

    private static void publishStartupBanner(
            final LocalPluginRuntime pluginRuntime,
            final KernelLog log,
            final KernelRuntime runtime,
            final ClassLoader hostClassLoader) {
        final LocalPluginRuntime.StartupEnvironment environment = pluginRuntime.startupEnvironment();
        final dev.turboism.graal.GraalHostConfiguration graal = environment.graalConfiguration();
        final String graalVm = graal.enabled()
                ? dev.turboism.graal.ManagedGraalRuntimeService.GRAAL_VERSION + " (" + sourceLabel(graal.source()) + ")"
                : "unavailable (standard JVM)";
        final String graalJs = environment.discoveredScriptCount() + " discovered; host "
                + (graal.enabled() ? "available" : "unavailable");
        STARTUP_BANNER.publish(
                List.of(log::banner, rendered -> publishHostBanner(hostClassLoader, rendered, log)),
                new StartupBanner.Details(
                        StartupBanner.frameworkDisplayVersion(),
                        System.getProperty("java.version", "unavailable"),
                        graalVm,
                        runtime.hostAccess().cubismEditorVersion().orElse("unavailable"),
                        runtime.loadReport().loaded().size(),
                        graalJs));
    }

    private static void publishHostBanner(final ClassLoader hostClassLoader, final String banner, final KernelLog log) {
        CubismLoggerBridge bridge = null;
        try {
            bridge = CubismLoggerBridge.connect(hostClassLoader);
            for (final String line : banner.split("\\n", -1)) {
                bridge.write(KernelLog.Level.INFO, "startup", line, null);
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            log.debug("runtime", "Native startup banner was unavailable");
        } finally {
            if (bridge != null) {
                try {
                    bridge.close();
                } catch (RuntimeException | LinkageError failure) {
                    log.debug("runtime", "Native startup logger cleanup was unavailable");
                }
            }
        }
    }

    private static String sourceLabel(final dev.turboism.graal.GraalHostConfiguration.Source source) {
        return switch (source) {
            case MANAGED -> "managed";
            case LEGACY_PACKAGED -> "legacy packaged";
            case EXTERNAL -> "external";
            case EXPLICIT -> "explicit";
            case DISABLED -> "disabled";
        };
    }
}
