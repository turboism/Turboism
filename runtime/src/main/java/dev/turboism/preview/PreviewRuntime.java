package dev.turboism.preview;

import dev.turboism.adapter.host.HostInstanceDescriptor;
import dev.turboism.bootstrap.HostRuntimeIngress;
import dev.turboism.adapter.host.HostSession;
import dev.turboism.adapter.host.HostVerificationEvidence;
import dev.turboism.core.diagnostics.PluginWorkBudgetEvent;
import dev.turboism.core.runtime.DefaultWorkBudgetPolicy;
import dev.turboism.core.runtime.work.PluginWorkExecutorRegistry;
import dev.turboism.core.runtime.RuntimeScheduler;
import dev.turboism.core.runtime.sidecar.SidecarDispatcher;
import dev.turboism.home.LegacyHomeMigration;
import dev.turboism.home.TurboismHomeLayout;
import dev.turboism.preview.report.PreviewReportSnapshotFactory;
import dev.turboism.preview.report.PreviewReportType;
import dev.turboism.preview.report.PreviewReportWriter;

import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** Owns the complete Turboism 0.1 runtime inside one Cubism process. */
public final class PreviewRuntime implements AutoCloseable {

    private final Path home;
    private final PreviewLog log;
    private final RuntimeScheduler scheduler;
    private final HostRuntimeIngress hostIngress;
    private final LocalPluginRuntime pluginRuntime;
    private final LocalPluginRuntime.LoadReport loadReport;
    private final PreviewReportWriter reportWriter;
    private final String runtimeId;
    private final Path verificationRecord;
    private final Path hostArtifact;
    private final ShutdownLifecycle shutdownLifecycle;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private volatile dev.turboism.sdk.cubism.filechooser.FileChooserHistoryService fileChooserHistoryService;
    private volatile List<ShutdownFailure> shutdownFailures = List.of();

    /** Package-private test composition seam; production startup uses {@link #start}. */
    PreviewRuntime(
        final Path home,
        final PreviewLog log,
        final RuntimeScheduler scheduler,
        final HostRuntimeIngress hostIngress,
        final LocalPluginRuntime pluginRuntime,
        final LocalPluginRuntime.LoadReport loadReport,
        final PreviewReportWriter reportWriter,
        final String runtimeId,
        final Path verificationRecord,
        final Path hostArtifact
    ) {
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
                final HostSession.State observedHostState,
                final boolean shutdownAttempted
            ) {
                return writeReportsOnce(observedHostState, true, shutdownAttempted);
            }

            @Override
            public void logDegradedShutdown() {
                log.warn(
                    "runtime",
                    "RUNTIME_SHUTDOWN_DEGRADED: one or more shutdown stages failed safely."
                );
            }

            @Override
            public void closeLog() throws IOException {
                log.close();
            }
        };
    }

    /** Package-private shutdown-lifecycle seam for focused runtime tests. */
    PreviewRuntime(final ShutdownLifecycle shutdownLifecycle) {
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
        this.shutdownLifecycle = Objects.requireNonNull(shutdownLifecycle, "shutdownLifecycle");
    }

    public static PreviewRuntime start(
        final Path requestedHome,
        final Path verificationRecord,
        final Path editorModelVerificationRecord,
        final Path mainToolbarVerificationRecord,
        final Path embeddedPanelVerificationRecord,
        final Path topMenuVerificationRecord,
        final Path boundingBoxOverlayVerificationRecord,
        final Path hostArtifact,
        final ClassLoader hostClassLoader
    ) throws IOException {
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
            hostArtifact,
            null,
            hostClassLoader
        );
    }

    /**
     * Starts the preview with an optional exact-version status-bar verification record.
     * The status slice is only connected when the record is present.
     */
    public static PreviewRuntime start(
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
        final Path hostArtifact,
        final Path coreArtifact,
        final ClassLoader hostClassLoader
    ) throws IOException {
        Objects.requireNonNull(statusBarVerificationRecord, "statusBarVerificationRecord");
        Objects.requireNonNull(clipMaskVerificationRecord, "clipMaskVerificationRecord");
        final TurboismHomeLayout layout = TurboismHomeLayout.create(requestedHome);
        final Path home = layout.home();
        LegacyHomeMigration.migrate(home);
        final var pendingPlugins = new dev.turboism.pluginmanagement.PendingPluginOperations(home).apply();
        if (!pendingPlugins.applied()) {
            throw new IOException(pendingPlugins.code());
        }

        final ClassLoader verifiedHostClassLoader = Objects.requireNonNull(
            hostClassLoader,
            "hostClassLoader"
        );
        PreviewLog.Sink hostLogSink;
        String hostLogFailure;
        try {
            hostLogSink = CubismLoggerBridge.connect(verifiedHostClassLoader);
            hostLogFailure = null;
        } catch (ReflectiveOperationException | LinkageError | SecurityException failure) {
            hostLogSink = PreviewLog.Sink.STDERR;
            hostLogFailure = failure.getClass().getSimpleName() + ": " + failure.getMessage();
        }
        final PreviewLog log;
        try {
            log = PreviewLog.openSession(
                layout.runtimeLogsDir(),
                Clock.systemUTC(),
                ProcessHandle.current().pid(),
                hostLogSink
            );
        } catch (IOException failure) {
            hostLogSink.close();
            throw failure;
        }
        RuntimeScheduler scheduler = null;
        HostRuntimeIngress ingress = null;
        LocalPluginRuntime plugins = null;
        try {
            final var runtimeConfig = new dev.turboism.config.RuntimeConfigRepository(
                home,
                diagnostic -> log.warn("config", diagnostic)
            ).read();
            log.setMinimumLevel(runtimeConfig.path("logLevel").asText("INFO"));
            log.setMaxStorageMiB(runtimeConfig.path("maxLogStorageMiB").asInt(
                dev.turboism.sdk.runtime.RuntimeSettings.DEFAULT_MAX_LOG_STORAGE_MIB
            ));
            if (hostLogFailure != null) {
                log.warn("runtime", "Cubism logger bridge unavailable; using stderr: " + hostLogFailure);
            }
            log.info("runtime", "Starting Turboism 0.1 Developer Preview at " + home);
            // Inject the persisted theme before the Cubism GL scene initializes so
            // the off-canvas background color (cached in a singleton Lazy) takes
            // effect on restart, matching the legacy hook agent's startup timing.
            new dev.turboism.ui.appearance.EarlyThemeAppearanceBootstrap(
                home,
                verifiedHostClassLoader,
                () -> log.info("runtime", "Early theme appearance injected from persisted selection")
            ).start();
            scheduler = createScheduler(log);
            ingress = new HostRuntimeIngress();

            final Path normalizedVerificationRecord = Objects.requireNonNull(
                verificationRecord,
                "verificationRecord"
            ).toAbsolutePath().normalize();
            final Path normalizedHostArtifact = Objects.requireNonNull(
                hostArtifact,
                "hostArtifact"
            ).toAbsolutePath().normalize();
            final Path normalizedCoreArtifact = coreArtifact == null
                ? null
                : coreArtifact.toAbsolutePath().normalize();
            final HostVerificationEvidence.Slice projectWorkspace = new HostVerificationEvidence.Slice(
                normalizedVerificationRecord,
                normalizedHostArtifact,
                verifiedHostClassLoader
            );
            final HostVerificationEvidence.Slice editorModel = new HostVerificationEvidence.Slice(
                Objects.requireNonNull(editorModelVerificationRecord, "editorModelVerificationRecord")
                    .toAbsolutePath().normalize(),
                normalizedHostArtifact,
                verifiedHostClassLoader
            );
            final HostVerificationEvidence.Slice coreRuntime =
                coreRuntimeVerificationRecord == null || normalizedCoreArtifact == null
                    ? null
                    : new HostVerificationEvidence.Slice(
                        coreRuntimeVerificationRecord.toAbsolutePath().normalize(),
                        normalizedCoreArtifact,
                        verifiedHostClassLoader
                    );
            final HostVerificationEvidence.Slice mainToolbar = new HostVerificationEvidence.Slice(
                Objects.requireNonNull(mainToolbarVerificationRecord, "mainToolbarVerificationRecord")
                    .toAbsolutePath().normalize(),
                normalizedHostArtifact,
                verifiedHostClassLoader
            );
            final HostVerificationEvidence.Slice embeddedPanel = new HostVerificationEvidence.Slice(
                Objects.requireNonNull(embeddedPanelVerificationRecord, "embeddedPanelVerificationRecord")
                    .toAbsolutePath().normalize(),
                normalizedHostArtifact,
                verifiedHostClassLoader
            );
            final HostVerificationEvidence.Slice topMenu = new HostVerificationEvidence.Slice(
                Objects.requireNonNull(topMenuVerificationRecord, "topMenuVerificationRecord")
                    .toAbsolutePath().normalize(),
                normalizedHostArtifact,
                verifiedHostClassLoader
            );
            final HostVerificationEvidence.Slice boundingBoxOverlayButton =
                new HostVerificationEvidence.Slice(
                    Objects.requireNonNull(
                        boundingBoxOverlayVerificationRecord,
                        "boundingBoxOverlayVerificationRecord"
                    ).toAbsolutePath().normalize(),
                    normalizedHostArtifact,
                    verifiedHostClassLoader
                );
            final HostVerificationEvidence evidence = HostVerificationEvidence
                .withEditorModel(projectWorkspace, editorModel)
                .addingMainToolbar(mainToolbar)
                .addingEmbeddedPanel(embeddedPanel)
                .addingTopMenu(topMenu)
                .addingBoundingBoxOverlayButton(boundingBoxOverlayButton);
            final HostVerificationEvidence evidenceWithCore = coreRuntime == null
                ? evidence
                : evidence.addingCoreRuntime(coreRuntime);
            final HostVerificationEvidence evidenceWithStatus = statusBarVerificationRecord
                .map(record -> record.toAbsolutePath().normalize())
                .map(record -> evidenceWithCore.addingStatusBar(new HostVerificationEvidence.Slice(
                    record,
                    normalizedHostArtifact,
                    verifiedHostClassLoader
                )))
                .orElse(evidenceWithCore);
            final HostVerificationEvidence evidenceWithClipMask = clipMaskVerificationRecord
                .map(record -> record.toAbsolutePath().normalize())
                .map(record -> evidenceWithStatus.addingClipMask(new HostVerificationEvidence.Slice(
                    record,
                    normalizedHostArtifact,
                    verifiedHostClassLoader
                )))
                .orElse(evidenceWithStatus);
            final HostSession.State hostState = ingress.publish(new HostInstanceDescriptor(
                "cubism-" + ProcessHandle.current().pid(),
                evidenceWithClipMask
            ));
            if (hostState == HostSession.State.ACTIVE) {
                log.info("host", "Verified Cubism project/workspace adapter connected");
            } else {
                final String failure = ingress.lastFailure()
                    .map(value -> value.code() + ": " + value.message())
                    .orElse("No detailed failure");
                log.warn("host", "Host adapter entered " + hostState + ": " + failure);
            }

            plugins = new LocalPluginRuntime(
                home,
                scheduler,
                ingress.adapterAccess(),
                log,
                ingress.adapterAccess().parameterLifecycle()
            );
            final LocalPluginRuntime.LoadReport report = plugins.loadAll();
            ingress.adapterAccess().editorLifecycleEvents().publishStartup(
                dev.turboism.mapping.verification.EditorModelVerificationManifest
                    .resourceProfileForArtifact(
                        dev.turboism.mapping.verification.HostArtifactDigest.from(
                            normalizedHostArtifact
                        )
                    )
            );
            final PreviewReportWriter reportWriter = new PreviewReportWriter(
                layout.runtimeStateDir(),
                diagnostic -> log.warn(
                    "preview-report",
                    diagnostic.code() + " " + diagnostic.reportType() + ": "
                        + diagnostic.message()
                )
            );
            final PreviewRuntime runtime = new PreviewRuntime(
                home,
                log,
                scheduler,
                ingress,
                plugins,
                report,
                reportWriter,
                "runtime-" + UUID.randomUUID(),
                normalizedVerificationRecord,
                normalizedHostArtifact
            );
            runtime.writeInitialReports(hostState);
            return runtime;
        } catch (RuntimeException | Error failure) {
            closeAfterFailedStart(plugins, ingress, scheduler, log, failure);
            throw failure;
        }
    }

    public Path home() {
        return home;
    }

    public HostSession.State hostState() {
        return hostIngress.state();
    }

    public void info(final String component, final String message) {
        log.info(component, message);
    }

    public void warn(final String component, final String message) {
        log.warn(component, message);
    }

    public void error(final String component, final String message, final Throwable failure) {
        log.error(component, message, failure);
    }

    public LocalPluginRuntime.LoadReport loadReport() {
        return loadReport;
    }

    public dev.turboism.adapter.host.RuntimeHostAdapterAccess hostAccess() {
        return hostIngress.adapterAccess();
    }

    /** Runtime file-chooser history service (lazily bound to the global config). */
    public dev.turboism.sdk.cubism.filechooser.FileChooserHistoryService fileChooserHistoryService() {
        dev.turboism.sdk.cubism.filechooser.FileChooserHistoryService service = fileChooserHistoryService;
        if (service == null) {
            synchronized (this) {
                service = fileChooserHistoryService;
                if (service == null) {
                    service = new dev.turboism.filechooser.RuntimeFileChooserHistoryService(
                        new dev.turboism.config.RuntimeConfigRepository(
                            home,
                            diagnostic -> log.warn("config", diagnostic)
                        )
                    );
                    fileChooserHistoryService = service;
                }
            }
        }
        return service;
    }

    public dev.turboism.mapping.verification.VerifiedMemberResolver editorModelResolver() {
        return hostIngress.editorModelResolver();
    }

    public dev.turboism.adapter.cubism.textureatlas.TextureAtlasDataModelCapture
        textureAtlasDataModelCapture() {
        return hostIngress.textureAtlasDataModelCapture();
    }

    private static RuntimeScheduler createScheduler(final PreviewLog log) {
        final Clock clock = Clock.systemUTC();
        final java.util.function.Consumer<PluginWorkBudgetEvent> diagnosticSink = event ->
            log.warn(
                "scheduler",
                event.pluginId() + " " + event.taskId() + " " + event.phase() + " " + event.decision()
            );
        return new RuntimeScheduler(
            new DefaultWorkBudgetPolicy(),
            new PluginWorkExecutorRegistry(1, 64, diagnosticSink, clock),
            SidecarDispatcher.noop(),
            diagnosticSink
        );
    }

    private static void closeAfterFailedStart(
        final LocalPluginRuntime plugins,
        final HostRuntimeIngress ingress,
        final RuntimeScheduler scheduler,
        final PreviewLog log,
        final Throwable failure
    ) {
        log.error("runtime", "Turboism preview startup failed", failure);
        if (plugins != null) {
            plugins.close();
        }
        if (ingress != null) {
            ingress.close();
        }
        if (scheduler != null) {
            scheduler.shutdown();
        }
        try {
            log.close();
        } catch (IOException ignored) {
        }
    }

    /** Package-private initial-report seam that avoids reflective test access. */
    void writeInitialReports(final HostSession.State observedHostState) {
        try {
            writeReportsStrict(observedHostState, false);
        } catch (RuntimeException failure) {
            try {
                log.warn(
                    "preview-report",
                    "PREVIEW_REPORT_SNAPSHOT_FAILED: report snapshot failed safely."
                );
            } catch (RuntimeException ignored) {
            }
        }
    }

    private void writeReportsStrict(
        final HostSession.State observedHostState,
        final boolean stopped
    ) {
        if (!writeReportsOnce(observedHostState, stopped, stopped)) {
            throw new IllegalStateException("Preview report persistence failed safely");
        }
    }

    private boolean writeReportsOnce(
        final HostSession.State observedHostState,
        final boolean stopped,
        final boolean shutdownAttempted
    ) {
        final LocalPluginRuntimeReportSnapshot snapshot = pluginRuntime.reportSnapshot();
        final Map<PreviewReportType, ObjectNode> reports = PreviewReportSnapshotFactory.create(
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
            shutdownAttempted
        );
        final Map<?, Boolean> results = reportWriter.writeAll(reports);
        return results.values().stream().allMatch(Boolean.TRUE::equals);
    }

    static ShutdownReportCounts shutdownReportCounts(
        final List<LocalPluginRuntime.LoadedPluginSummary> summaries
    ) {
        final long succeeded = summaries.stream()
            .filter(summary -> summary.unloadState().equals("SUCCEEDED"))
            .count();
        final long scopesClosed = summaries.stream()
            .filter(summary -> summary.scopeCleanupState().equals("SUCCEEDED"))
            .count();
        final long classloadersClosed = summaries.stream()
            .filter(summary -> summary.classloaderCleanupState().equals("SUCCEEDED"))
            .count();
        final long cleanupFailures = summaries.stream().mapToLong(summary ->
            (summary.scopeCleanupState().equals("FAILED") ? 1 : 0)
                + (summary.classloaderCleanupState().equals("FAILED") ? 1 : 0)
        ).sum();
        return new ShutdownReportCounts(
            summaries.size(),
            succeeded,
            summaries.size() - succeeded,
            scopesClosed,
            classloadersClosed,
            cleanupFailures
        );
    }

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
        final List<ShutdownFailure> failures = new ArrayList<>();
        try {
            shutdownLifecycle.logStopping();
        } catch (Throwable failure) {
            failures.add(stableFailure("STOP_LOG_FAILED", "stop-log"));
        }

        HostSession.State observedHostState = HostSession.State.FAILED;
        try {
            observedHostState = shutdownLifecycle.hostState();
        } catch (Throwable failure) {
            failures.add(stableFailure("HOST_STATE_CAPTURE_FAILED", "host-state"));
        }
        final HostSession.State finalObservedHostState = observedHostState;
        if (cleanHostResources) {
            failures.addAll(runShutdownStages(List.of(
                new ShutdownStage(
                    "PLUGIN_RUNTIME_CLOSE_FAILED", "plugin-runtime", shutdownLifecycle::closePluginRuntime
                ),
                new ShutdownStage(
                    "HOST_INGRESS_CLOSE_FAILED", "host-ingress", shutdownLifecycle::closeHostIngress
                ),
                new ShutdownStage(
                    "SCHEDULER_SHUTDOWN_FAILED", "scheduler", shutdownLifecycle::shutdownScheduler
                )
            )));
        }
        failures.addAll(runShutdownStages(List.of(
            new ShutdownStage(
                "FINAL_REPORT_WRITE_FAILED",
                "final-report",
                () -> {
                    if (!shutdownLifecycle.writeFinalReport(finalObservedHostState, cleanHostResources)) {
                        throw new IllegalStateException("Preview report persistence failed safely");
                    }
                }
            )
        )));

        if (!failures.isEmpty()) {
            try {
                shutdownLifecycle.logDegradedShutdown();
            } catch (Throwable failure) {
                failures.add(stableFailure("SHUTDOWN_SUMMARY_LOG_FAILED", "summary-log"));
            }
        }

        failures.addAll(runShutdownStages(List.of(
            new ShutdownStage("LOG_CLOSE_FAILED", "log", shutdownLifecycle::closeLog)
        )));
        shutdownFailures = List.copyOf(failures);
        if (failures.stream().anyMatch(failure -> failure.code().equals("LOG_CLOSE_FAILED"))) {
            System.err.println("Turboism preview log close failed safely: LOG_CLOSE_FAILED");
        }
    }

    static List<ShutdownFailure> runShutdownStages(final List<ShutdownStage> stages) {
        final List<ShutdownFailure> failures = new ArrayList<>();
        for (ShutdownStage stage : stages) {
            try {
                stage.action().run();
            } catch (Throwable failure) {
                failures.add(stableFailure(stage.code(), stage.phase()));
            }
        }
        return List.copyOf(failures);
    }

    private static ShutdownFailure stableFailure(final String code, final String phase) {
        return new ShutdownFailure(code, phase, "Runtime shutdown stage failed safely.");
    }

    record ShutdownReportCounts(
        long shutdownAttempted,
        long shutdownSucceeded,
        long shutdownFailed,
        long scopesClosed,
        long classloadersClosed,
        long cleanupFailures
    ) {
    }

    public record ShutdownFailure(String code, String phase, String message) {
    }

    record ShutdownStage(String code, String phase, ShutdownAction action) {
    }

    @FunctionalInterface
    interface ShutdownAction {
        void run() throws Throwable;
    }

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
