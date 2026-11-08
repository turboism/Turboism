package dev.turboism.kernel;

import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.turboism.adapter.host.HostSession;
import dev.turboism.bootstrap.HostRuntimeIngress;
import dev.turboism.core.runtime.RuntimeScheduler;
import dev.turboism.core.runtime.work.FatalErrors;
import dev.turboism.kernel.report.KernelReportType;
import dev.turboism.kernel.report.KernelReportWriter;
import dev.turboism.mapping.verification.CompatibilityResolution;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/** Owns the complete Turboism 0.1 runtime inside one Cubism process. */
public final class KernelRuntime implements AutoCloseable {

    private final Path home;
    private final KernelLog log;
    private final RuntimeScheduler scheduler;
    private final HostRuntimeIngress hostIngress;
    private final LocalPluginRuntime pluginRuntime;
    private volatile LocalPluginRuntime.LoadReport loadReport;
    private final KernelReportWriter reportWriter;
    private final String runtimeId;
    private final Path verificationRecord;
    private final Path hostArtifact;
    private final java.util.Locale effectiveLocale;
    private final ShutdownLifecycle shutdownLifecycle;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicBoolean pluginsStarted = new AtomicBoolean(false);
    private volatile dev.turboism.sdk.cubism.filechooser.FileChooserHistoryService fileChooserHistoryService;
    private volatile dev.turboism.exportsettings.RuntimeExportSettingsAuthority exportSettingsAuthority;
    private volatile dev.turboism.exportsettings.ProtectedExportOrchestrator protectedExportOrchestrator;
    private volatile dev.turboism.automesh.host.NativeAutoMeshBridge.Handler autoMeshGuard;
    private volatile List<ShutdownFailure> shutdownFailures = List.of();

    /** Package-private test composition seam; production startup uses {@link #start}. */
    KernelRuntime(
            final Path home,
            final KernelLog log,
            final RuntimeScheduler scheduler,
            final HostRuntimeIngress hostIngress,
            final LocalPluginRuntime pluginRuntime,
            final LocalPluginRuntime.LoadReport loadReport,
            final KernelReportWriter reportWriter,
            final String runtimeId,
            final Path verificationRecord,
            final Path hostArtifact) {
        this(
                home,
                log,
                scheduler,
                hostIngress,
                pluginRuntime,
                loadReport,
                reportWriter,
                runtimeId,
                verificationRecord,
                hostArtifact,
                java.util.Locale.getDefault());
    }

    KernelRuntime(
            final Path home,
            final KernelLog log,
            final RuntimeScheduler scheduler,
            final HostRuntimeIngress hostIngress,
            final LocalPluginRuntime pluginRuntime,
            final LocalPluginRuntime.LoadReport loadReport,
            final KernelReportWriter reportWriter,
            final String runtimeId,
            final Path verificationRecord,
            final Path hostArtifact,
            final java.util.Locale effectiveLocale) {
        this.home = home;
        this.log = log;
        this.scheduler = scheduler;
        this.hostIngress = hostIngress;
        this.pluginRuntime = pluginRuntime;
        this.loadReport = loadReport;
        this.reportWriter = reportWriter;
        this.runtimeId = runtimeId;
        this.verificationRecord = verificationRecord;
        this.hostArtifact = hostArtifact;
        this.effectiveLocale = Objects.requireNonNull(effectiveLocale, "effectiveLocale");
        this.shutdownLifecycle = new ShutdownLifecycle() {
            @Override
            public void logStopping() {
                log.info("runtime", "Stopping Turboism Developer Preview");
            }

            @Override
            public HostSession.State hostState() {
                return hostIngress.state();
            }

            @Override
            public void closePluginRuntime() {
                pluginRuntime.close();
            }

            @Override
            public void closeHostIngress() {
                hostIngress.close();
            }

            @Override
            public void shutdownScheduler() {
                scheduler.shutdown();
            }

            @Override
            public boolean writeFinalReport(
                    final HostSession.State observedHostState, final boolean shutdownAttempted) {
                return writeReportsOnce(observedHostState, true, shutdownAttempted);
            }

            @Override
            public void logDegradedShutdown() {
                log.warn("runtime", "RUNTIME_SHUTDOWN_DEGRADED: one or more shutdown stages failed safely.");
            }

            @Override
            public void closeLog() throws IOException {
                dev.turboism.runtime.log.RuntimeDiagnostics.clear();
                log.close();
            }
        };
    }

    /** Package-private shutdown-lifecycle seam for focused runtime tests. */
    KernelRuntime(final ShutdownLifecycle shutdownLifecycle) {
        this.home = null;
        this.log = null;
        this.scheduler = null;
        this.hostIngress = null;
        this.pluginRuntime = null;
        this.loadReport = null;
        this.reportWriter = null;
        this.runtimeId = null;
        this.verificationRecord = null;
        this.hostArtifact = null;
        this.effectiveLocale = java.util.Locale.getDefault();
        this.shutdownLifecycle = Objects.requireNonNull(shutdownLifecycle, "shutdownLifecycle");
    }

    /**
     * Starts the preview runtime without a status-bar verification record, leaving the status
     * slice unconnected. Convenience overload that delegates to the full {@code start}.
     *
     * @param requestedHome Turboism home directory; normalized before use
     * @param verificationRecord exact-version verification record for the host mapping
     * @param editorModelVerificationRecord verification record for the editor-model slice
     * @param mainToolbarVerificationRecord verification record for the main-toolbar slice
     * @param embeddedPanelVerificationRecord verification record for the embedded-panel slice
     * @param topMenuVerificationRecord verification record for the top-menu slice
     * @param boundingBoxOverlayVerificationRecord verification record for the bounding-box overlay
     * @param hostArtifact the Cubism host artifact the records were verified against; only
     *     Cubism 5.2.03, 5.3.02, and 5.3.03 are admitted
     * @param hostClassLoader classloader through which host classes are resolved
     * @return a started runtime with plugins loaded and initial reports written
     * @throws IOException if the home directory or its log and report files cannot be prepared
     */
    public static KernelRuntime start(
            final Path requestedHome,
            final Path verificationRecord,
            final Path editorModelVerificationRecord,
            final Path mainToolbarVerificationRecord,
            final Path embeddedPanelVerificationRecord,
            final Path topMenuVerificationRecord,
            final Path boundingBoxOverlayVerificationRecord,
            final Path hostArtifact,
            final ClassLoader hostClassLoader)
            throws IOException {
        return start(
                requestedHome,
                verificationRecord,
                editorModelVerificationRecord,
                null,
                mainToolbarVerificationRecord,
                embeddedPanelVerificationRecord,
                topMenuVerificationRecord,
                boundingBoxOverlayVerificationRecord,
                Optional.empty(),
                Optional.empty(),
                null,
                Optional.empty(),
                hostArtifact,
                null,
                hostClassLoader);
    }

    /**
     * Starts the preview with an optional exact-version status-bar verification record.
     * The status slice is only connected when the record is present.
     */
    public static KernelRuntime start(
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
            final ClassLoader hostClassLoader)
            throws IOException {
        return start(
                requestedHome,
                verificationRecord,
                editorModelVerificationRecord,
                coreRuntimeVerificationRecord,
                mainToolbarVerificationRecord,
                embeddedPanelVerificationRecord,
                topMenuVerificationRecord,
                boundingBoxOverlayVerificationRecord,
                statusBarVerificationRecord,
                clipMaskVerificationRecord,
                autoBackupVerificationRecord,
                protectedExportVerificationRecord,
                hostArtifact,
                coreArtifact,
                hostClassLoader,
                null,
                runtime -> {},
                runtime -> {});
    }

    /**
     * Starts the preview under an already-computed host compatibility
     * resolution.
     *
     * <p>{@code hostResolution == null} preserves the legacy exact-review
     * contract: the artifact must be a byte-identical reviewed release. A
     * {@code COMPATIBLE} resolution binds admitted slices structurally without
     * claiming reviewed status; a {@code REJECTED} resolution fails closed.
     * Every record argument a compat resolution did not admit is ignored only
     * when it is {@code null}; passing a record for a non-admitted slice throws,
     * so callers cannot smuggle unadmitted adapters into a degraded host.</p>
     */
    public static KernelRuntime start(
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
            final Path hostArtifact,
            final Path coreArtifact,
            final ClassLoader hostClassLoader,
            final CompatibilityResolution hostResolution)
            throws IOException {
        return start(
                requestedHome,
                verificationRecord,
                editorModelVerificationRecord,
                coreRuntimeVerificationRecord,
                mainToolbarVerificationRecord,
                embeddedPanelVerificationRecord,
                topMenuVerificationRecord,
                boundingBoxOverlayVerificationRecord,
                statusBarVerificationRecord,
                clipMaskVerificationRecord,
                autoBackupVerificationRecord,
                hostArtifact,
                coreArtifact,
                hostClassLoader,
                hostResolution,
                runtime -> {});
    }

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
     * @param hostArtifact actual Editor artifact
     * @param coreArtifact actual Core artifact when present
     * @param hostClassLoader loader owning the host classes
     * @param hostResolution admission decision, or null for the legacy exact path
     * @param beforePlugins trusted bootstrap work that must finish before plugin code runs
     * @return the started runtime
     * @throws IOException if runtime files cannot be prepared
     */
    public static KernelRuntime start(
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
            final Path hostArtifact,
            final Path coreArtifact,
            final ClassLoader hostClassLoader,
            final CompatibilityResolution hostResolution,
            final java.util.function.Consumer<KernelRuntime> beforePlugins)
            throws IOException {
        return start(
                requestedHome,
                verificationRecord,
                editorModelVerificationRecord,
                coreRuntimeVerificationRecord,
                mainToolbarVerificationRecord,
                embeddedPanelVerificationRecord,
                topMenuVerificationRecord,
                boundingBoxOverlayVerificationRecord,
                statusBarVerificationRecord,
                clipMaskVerificationRecord,
                autoBackupVerificationRecord,
                Optional.empty(),
                hostArtifact,
                coreArtifact,
                hostClassLoader,
                hostResolution,
                beforePlugins,
                runtime -> {});
    }

    /** Starts an admitted runtime with an optional exact-review protected-export record. */
    public static KernelRuntime start(
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
        return KernelStartupSequence.start(
                requestedHome,
                verificationRecord,
                editorModelVerificationRecord,
                coreRuntimeVerificationRecord,
                mainToolbarVerificationRecord,
                embeddedPanelVerificationRecord,
                topMenuVerificationRecord,
                boundingBoxOverlayVerificationRecord,
                statusBarVerificationRecord,
                clipMaskVerificationRecord,
                autoBackupVerificationRecord,
                protectedExportVerificationRecord,
                hostArtifact,
                coreArtifact,
                hostClassLoader,
                hostResolution,
                beforePlugins,
                afterPlugins);
    }

    static void requireSafePendingPlugins(
            final dev.turboism.pluginmanagement.PendingPluginOperations.ApplyResult result) throws IOException {
        if (result.status() != dev.turboism.pluginmanagement.PendingPluginOperations.Status.APPLIED
                && result.status() != dev.turboism.pluginmanagement.PendingPluginOperations.Status.ROLLED_BACK
                && result.status() != dev.turboism.pluginmanagement.PendingPluginOperations.Status.DEFERRED) {
            throw new IOException(result.code());
        }
    }

    static void confirmFrameworkHealthAfterPluginApply(
            final dev.turboism.pluginmanagement.PendingPluginOperations.ApplyResult result,
            final Runnable confirmation,
            final java.util.function.Consumer<String> diagnostic) {
        if (result.applied()) {
            confirmation.run();
        } else {
            // A complete plugin rollback permits old plugins, not acceptance of the framework update.
            diagnostic.accept("UPDATE_STARTUP_NOT_CONFIRMED: " + result.code());
        }
    }

    /** The same startup barrier is used by production and plugin-initialization tests. */
    void loadPluginsAfterBootstrap(final java.util.function.Consumer<KernelRuntime> beforePlugins) {
        loadPluginsAfterBootstrap(beforePlugins, runtime -> {});
    }

    void loadPluginsAfterBootstrap(
            final java.util.function.Consumer<KernelRuntime> beforePlugins,
            final java.util.function.Consumer<KernelRuntime> afterPlugins) {
        Objects.requireNonNull(beforePlugins, "beforePlugins");
        Objects.requireNonNull(afterPlugins, "afterPlugins");
        if (closed.get() || !pluginsStarted.compareAndSet(false, true)) {
            throw new IllegalStateException("Plugin startup is no longer available");
        }
        beforePlugins.accept(this);
        loadReport = pluginRuntime.loadAll();
        afterPlugins.accept(this);
    }

    /** @return the normalized absolute Turboism home directory this runtime reads and writes under */
    public Path home() {
        return home;
    }

    /**
     * @return the locale the runtime resolved at startup and uses for plugin message bundles; this
     *     is the configured locale, which need not equal the JVM default
     */
    public java.util.Locale effectiveLocale() {
        return effectiveLocale;
    }

    /**
     * @return the host session's state as of this call, read through the ingress; reflects whether
     *     a real Cubism host is attached
     */
    public HostSession.State hostState() {
        return hostIngress.state();
    }

    /**
     * Writes an INFO line to the runtime log.
     *
     * @param component short subsystem tag
     * @param message the text to record
     */
    public void info(final String component, final String message) {
        log.info(component, message);
    }

    /**
     * Writes a DEBUG line to the runtime log.
     *
     * @param component short subsystem tag
     * @param message the text to record
     */
    public void debug(final String component, final String message) {
        log.debug(component, message);
    }

    /**
     * Writes a WARN line to the runtime log.
     *
     * @param component short subsystem tag
     * @param message the text to record
     */
    public void warn(final String component, final String message) {
        log.warn(component, message);
    }

    /**
     * Writes an ERROR line to the runtime log, attaching the throwable's cause chain.
     *
     * @param component short subsystem tag
     * @param message the text to record
     * @param failure throwable to attach, or null to write the line alone
     */
    public void error(final String component, final String message, final Throwable failure) {
        log.error(component, message, failure);
    }

    /**
     * @return the plugin load outcome captured during {@link #start}; fixed for the runtime's
     *     lifetime, so it describes the initial load and not any later state
     */
    public LocalPluginRuntime.LoadReport loadReport() {
        return loadReport;
    }

    /** @return the gateway through which runtime code reaches verified host members */
    public dev.turboism.adapter.host.RuntimeHostAdapterAccess hostAccess() {
        return hostIngress.adapterAccess();
    }

    /** Runtime file-chooser history service (lazily bound; persistence via plugin provider). */
    public dev.turboism.sdk.cubism.filechooser.FileChooserHistoryService fileChooserHistoryService() {
        dev.turboism.sdk.cubism.filechooser.FileChooserHistoryService service = fileChooserHistoryService;
        if (service == null) {
            synchronized (this) {
                service = fileChooserHistoryService;
                if (service == null) {
                    service = createFileChooserHistoryService(home, log);
                    fileChooserHistoryService = service;
                }
            }
        }
        return service;
    }

    /** Binds the shared singleton created during {@link #start}; a no-op when already bound. */
    void bindFileChooserHistoryService(final dev.turboism.sdk.cubism.filechooser.FileChooserHistoryService service) {
        synchronized (this) {
            if (fileChooserHistoryService == null) {
                fileChooserHistoryService = java.util.Objects.requireNonNull(service, "service");
            }
        }
    }

    /**
     * Host-level export-settings policy shared by every loaded plugin.
     *
     * <p>Returns the authority bound during {@link #start}. An unbound runtime throws instead of
     * answering with a substitute: the bootstrap hook must never transform host bytecode whose
     * callbacks no runtime policy can serve.</p>
     *
     * @return the export-settings authority bound to this runtime
     * @throws IllegalStateException if no authority was bound
     */
    public dev.turboism.exportsettings.RuntimeExportSettingsAuthority exportSettingsAuthority() {
        final dev.turboism.exportsettings.RuntimeExportSettingsAuthority authority = exportSettingsAuthority;
        if (authority == null) {
            throw new IllegalStateException("export settings authority is not bound");
        }
        return authority;
    }

    /** Binds the shared authority created during {@link #start}; a no-op when already bound. */
    void bindExportSettingsAuthority(final dev.turboism.exportsettings.RuntimeExportSettingsAuthority authority) {
        synchronized (this) {
            if (exportSettingsAuthority == null) {
                exportSettingsAuthority = java.util.Objects.requireNonNull(authority, "authority");
            }
        }
    }

    /** Keeps the orchestrator reachable for shutdown; a no-op for an unwired capability. */
    void bindProtectedExportOrchestrator(final dev.turboism.exportsettings.ProtectedExportOrchestrator orchestrator) {
        if (orchestrator == null) {
            return;
        }
        synchronized (this) {
            if (protectedExportOrchestrator == null) {
                protectedExportOrchestrator = orchestrator;
            }
        }
    }

    /**
     * Auto-mesh dispatch policy the transformed native funnels report to.
     *
     * <p>Empty until the milestone that resolves host targets binds it. The guard hook still
     * installs against an unbound runtime, because an unbound policy answers "not handled" for
     * every confirmation and therefore leaves the Editor's own Auto Mesh Generate untouched.</p>
     *
     * @return the bound guard policy, or empty when nothing has bound one yet
     */
    public java.util.Optional<dev.turboism.automesh.host.NativeAutoMeshBridge.Handler> autoMeshGuard() {
        return java.util.Optional.ofNullable(autoMeshGuard);
    }

    /** Binds the auto-mesh dispatch policy; a no-op when a policy is already bound. */
    void bindAutoMeshGuard(final dev.turboism.automesh.host.NativeAutoMeshBridge.Handler handler) {
        synchronized (this) {
            if (autoMeshGuard == null) {
                autoMeshGuard = java.util.Objects.requireNonNull(handler, "handler");
            }
        }
    }

    /**
     * Session auto-mesh catalog the native selector row and the dispatch guard both read.
     *
     * <p>The catalog outlives every plugin generation, so a plugin reload never resets which
     * algorithm the Auto Mesh Generate entry runs. Empty only before plugins are wired.</p>
     *
     * @return the session catalog, or empty when the plugin runtime has not been composed yet
     */
    public java.util.Optional<dev.turboism.automesh.AutoMeshAlgorithmCatalog> autoMeshCatalog() {
        return java.util.Optional.ofNullable(pluginRuntime).map(LocalPluginRuntime::autoMeshCatalog);
    }

    static dev.turboism.sdk.cubism.filechooser.FileChooserHistoryService createFileChooserHistoryService(
            final Path home, final KernelLog log) {
        final dev.turboism.config.RuntimeConfigRepository config =
                new dev.turboism.config.RuntimeConfigRepository(home, diagnostic -> log.warn("config", diagnostic));
        return new dev.turboism.filechooser.RuntimeFileChooserHistoryService(() -> config.read()
                .path("hooks")
                .path("startup")
                .path("separateExportSaveDirectory")
                .asBoolean(false));
    }

    /**
     * @return the resolver for verified editor-model members, through which mapped host fields and
     *     methods are reached; access it only for members the verification record admits
     */
    public dev.turboism.mapping.verification.VerifiedMemberResolver editorModelResolver() {
        return hostIngress.editorModelResolver();
    }

    /**
     * Withdraws Editor capabilities that depend on a failed or closed bootstrap hook. SDK
     * proxies consult the live session view, so previously acquired handles also see the loss.
     *
     * @param hookId catalog hook identity without the {@code hook:} prefix
     * @return affected capability ids, empty after the host connection has closed
     */
    public java.util.Set<String> disableEditorCapabilitiesRequiringHook(final String hookId) {
        final dev.turboism.mapping.verification.VerifiedMemberResolver resolver;
        try {
            resolver = editorModelResolver();
        } catch (IllegalStateException unavailable) {
            return java.util.Set.of();
        }
        final var disabled = resolver.disableCapabilitiesRequiringHook(hookId);
        if (!disabled.isEmpty()) {
            log.warn("host-capabilities", "hook=" + hookId + " unavailable; disabled=" + disabled);
        }
        return disabled;
    }

    /**
     * @return the texture-atlas data-model capture of the active verified connection
     * @throws IllegalStateException when no verified connection is active
     */
    public dev.turboism.adapter.cubism.textureatlas.TextureAtlasDataModelCapture textureAtlasDataModelCapture() {
        return hostIngress.textureAtlasDataModelCapture();
    }

    /** Package-private initial-report seam that avoids reflective test access. */
    void writeInitialReports(final HostSession.State observedHostState) {
        try {
            writeReportsStrict(observedHostState, false);
        } catch (RuntimeException failure) {
            log.warn("preview-report", "PREVIEW_REPORT_SNAPSHOT_FAILED: report snapshot failed safely: " + failure);
        }
    }

    private void writeReportsStrict(final HostSession.State observedHostState, final boolean stopped) {
        if (!writeReportsOnce(observedHostState, stopped, stopped)) {
            throw new IllegalStateException("Preview report persistence failed safely");
        }
    }

    private boolean writeReportsOnce(
            final HostSession.State observedHostState, final boolean stopped, final boolean shutdownAttempted) {
        final LocalPluginRuntimeReportSnapshot snapshot = pluginRuntime.reportSnapshot();
        final Map<KernelReportType, ObjectNode> reports = KernelReportSnapshotFactory.create(
                runtimeId,
                Instant.now(),
                home,
                observedHostState,
                hostArtifact,
                verificationRecord,
                loadReport,
                snapshot.pluginSummaries(),
                snapshot.failures(),
                stopped,
                shutdownAttempted);
        final Map<?, Boolean> results = reportWriter.writeAll(reports);
        return results.values().stream().allMatch(Boolean.TRUE::equals);
    }

    static ShutdownReportCounts shutdownReportCounts(final List<LocalPluginRuntime.LoadedPluginSummary> summaries) {
        final long succeeded = summaries.stream()
                .filter(summary -> summary.unloadState().equals("SUCCEEDED"))
                .count();
        final long scopesClosed = summaries.stream()
                .filter(summary -> summary.scopeCleanupState().equals("SUCCEEDED"))
                .count();
        final long classloadersClosed = summaries.stream()
                .filter(summary -> summary.classloaderCleanupState().equals("SUCCEEDED"))
                .count();
        final long cleanupFailures = summaries.stream()
                .mapToLong(summary -> (summary.scopeCleanupState().equals("FAILED") ? 1 : 0)
                        + (summary.classloaderCleanupState().equals("FAILED") ? 1 : 0))
                .sum();
        return new ShutdownReportCounts(
                summaries.size(),
                succeeded,
                summaries.size() - succeeded,
                scopesClosed,
                classloadersClosed,
                cleanupFailures);
    }

    /**
     * @return the failures recorded while shutting the runtime down; empty until {@link #close()}
     *     or {@link #closeForProcessExit()} has run. The list is an immutable copy published
     *     through a volatile field, so it is safe to read from any thread.
     */
    public List<ShutdownFailure> shutdownFailures() {
        return shutdownFailures;
    }

    @Override
    public void close() {
        close(true);
    }

    /**
     * Persists terminal evidence without invoking plugin or host cleanup after JVM shutdown starts.
     * Host-bound cleanup remains the responsibility of {@link #close()} while the host is live.
     */
    public void closeForProcessExit() {
        close(false);
    }

    private void close(final boolean cleanHostResources) {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        shutdownFailures = new KernelShutdownOrchestrator(shutdownLifecycle, this::closeExportSettingsAuthority)
                .close(cleanHostResources);
    }

    /**
     * Closes the host-level export-settings policy and drops every dialog attachment it owns.
     *
     * <p>Runs after the plugin runtime has closed, so no plugin registry is still bound, and before
     * the host ingress is torn down, so dialog state is released while the host objects it refers to
     * are still allocated.</p>
     */
    private void closeExportSettingsAuthority() {
        final dev.turboism.exportsettings.ProtectedExportOrchestrator orchestrator = protectedExportOrchestrator;
        protectedExportOrchestrator = null;
        if (orchestrator != null) {
            try {
                orchestrator.close();
            } catch (Throwable ignored) {
                FatalErrors.rethrowIfFatal(ignored);
                // A stuck armed session must not block the authority teardown below.
            }
        }
        final dev.turboism.exportsettings.RuntimeExportSettingsAuthority authority = exportSettingsAuthority;
        exportSettingsAuthority = null;
        if (authority != null) {
            authority.close();
        }
    }

    record ShutdownReportCounts(
            long shutdownAttempted,
            long shutdownSucceeded,
            long shutdownFailed,
            long scopesClosed,
            long classloadersClosed,
            long cleanupFailures) {}

    /**
     * One shutdown stage that did not complete. Shutdown is best-effort: a stage failure is
     * recorded here and the remaining stages still run.
     *
     * @param code stable diagnostic code identifying the stage that failed
     * @param phase the shutdown phase the stage belongs to
     * @param message fixed, report-safe description; deliberately not derived from the underlying
     *     throwable, so host detail cannot leak into a report
     */
    public record ShutdownFailure(String code, String phase, String message) {}

    interface ShutdownLifecycle {
        void logStopping() throws Throwable;

        HostSession.State hostState() throws Throwable;

        void closePluginRuntime() throws Throwable;

        void closeHostIngress() throws Throwable;

        void shutdownScheduler() throws Throwable;

        boolean writeFinalReport(HostSession.State observedHostState, boolean shutdownAttempted) throws Throwable;

        void logDegradedShutdown() throws Throwable;

        void closeLog() throws Throwable;
    }
}
