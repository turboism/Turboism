package dev.turboism.preview;

import dev.turboism.adapter.host.HostInstanceDescriptor;
import dev.turboism.bootstrap.HostRuntimeIngress;
import dev.turboism.adapter.host.HostSession;
import dev.turboism.adapter.host.HostVerificationEvidence;
import dev.turboism.core.diagnostics.CallbackBudgetEvent;
import dev.turboism.core.runtime.DefaultWorkBudgetPolicy;
import dev.turboism.core.runtime.PluginExecutorRegistry;
import dev.turboism.core.runtime.RuntimeScheduler;
import dev.turboism.core.runtime.sidecar.SidecarDispatcher;
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
    private volatile List<ShutdownFailure> shutdownFailures = List.of();

    private PreviewRuntime(
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
            public boolean writeFinalReport(final HostSession.State observedHostState) {
                return writeReportsOnce(observedHostState, true);
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
        final Path hostArtifact,
        final ClassLoader hostClassLoader
    ) throws IOException {
        final Path home = Objects.requireNonNull(requestedHome, "requestedHome")
            .toAbsolutePath()
            .normalize();
        Files.createDirectories(home);
        Files.createDirectories(home.resolve("plugins"));
        Files.createDirectories(home.resolve("state"));
        Files.createDirectories(home.resolve("logs"));

        final PreviewLog log = new PreviewLog(home.resolve("logs").resolve("turboism.log"));
        RuntimeScheduler scheduler = null;
        HostRuntimeIngress ingress = null;
        LocalPluginRuntime plugins = null;
        try {
            log.info("runtime", "Starting Turboism 0.1 Developer Preview at " + home);
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
            final HostVerificationEvidence.Slice projectWorkspace = new HostVerificationEvidence.Slice(
                normalizedVerificationRecord,
                normalizedHostArtifact,
                Objects.requireNonNull(hostClassLoader, "hostClassLoader")
            );
            final HostSession.State hostState = ingress.publish(new HostInstanceDescriptor(
                "cubism-" + ProcessHandle.current().pid(),
                HostVerificationEvidence.projectOnly(projectWorkspace)
            ));
            if (hostState == HostSession.State.ACTIVE) {
                log.info("host", "Verified Cubism project/workspace adapter connected");
            } else {
                final String failure = ingress.lastFailure()
                    .map(value -> value.code() + ": " + value.message())
                    .orElse("No detailed failure");
                log.warn("host", "Host adapter entered " + hostState + ": " + failure);
            }

            plugins = new LocalPluginRuntime(home, scheduler, ingress.adapterAccess(), log);
            final LocalPluginRuntime.LoadReport report = plugins.loadAll();
            final PreviewReportWriter reportWriter = new PreviewReportWriter(
                home.resolve("state"),
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
            runtime.writeReports(hostState, false);
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

    public LocalPluginRuntime.LoadReport loadReport() {
        return loadReport;
    }

    private static RuntimeScheduler createScheduler(final PreviewLog log) {
        final Clock clock = Clock.systemUTC();
        final java.util.function.Consumer<CallbackBudgetEvent> diagnosticSink = event ->
            log.warn(
                "scheduler",
                event.pluginId() + " " + event.taskId() + " " + event.phase() + " " + event.decision()
            );
        return new RuntimeScheduler(
            new DefaultWorkBudgetPolicy(),
            new PluginExecutorRegistry(1, 64, diagnosticSink, clock),
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

    private void writeReports(
        final HostSession.State observedHostState,
        final boolean stopped
    ) {
        try {
            writeReportsStrict(observedHostState, stopped);
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
        if (!writeReportsOnce(observedHostState, stopped)) {
            throw new IllegalStateException("Preview report persistence failed safely");
        }
    }

    private boolean writeReportsOnce(
        final HostSession.State observedHostState,
        final boolean stopped
    ) {
        final List<LocalPluginRuntime.LoadedPluginSummary> summaries = pluginRuntime.reportSummaries();
        final Map<PreviewReportType, ObjectNode> reports = PreviewReportSnapshotFactory.create(
            runtimeId,
            Instant.now(),
            home,
            observedHostState,
            hostArtifact,
            verificationRecord,
            loadReport,
            summaries,
            stopped
        );
        applyShutdownReportCounts(reports, summaries, stopped);
        final Map<?, Boolean> results = reportWriter.writeAll(reports);
        return results.values().stream().allMatch(Boolean.TRUE::equals);
    }

    static void applyShutdownReportCounts(
        final Map<PreviewReportType, ObjectNode> reports,
        final List<LocalPluginRuntime.LoadedPluginSummary> summaries,
        final boolean stopped
    ) {
        if (!stopped) {
            return;
        }
        final ShutdownReportCounts counts = shutdownReportCounts(summaries);
        final ObjectNode payload = (ObjectNode) reports.get(PreviewReportType.PREVIEW_RUNTIME)
            .get("payload");
        final ObjectNode shutdownCounts = (ObjectNode) payload.get("shutdownCounts");
        shutdownCounts.put("attempted", counts.shutdownAttempted());
        shutdownCounts.put("succeeded", counts.shutdownSucceeded());
        shutdownCounts.put("failed", counts.shutdownFailed());
        final ObjectNode cleanupCounts = (ObjectNode) payload.get("cleanupCounts");
        cleanupCounts.put("scopesClosed", counts.scopesClosed());
        cleanupCounts.put("classloadersClosed", counts.classloadersClosed());
        cleanupCounts.put("failures", counts.cleanupFailures());
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
        failures.addAll(runShutdownStages(List.of(
            new ShutdownStage(
                "PLUGIN_RUNTIME_CLOSE_FAILED", "plugin-runtime", shutdownLifecycle::closePluginRuntime
            ),
            new ShutdownStage(
                "HOST_INGRESS_CLOSE_FAILED", "host-ingress", shutdownLifecycle::closeHostIngress
            ),
            new ShutdownStage(
                "SCHEDULER_SHUTDOWN_FAILED", "scheduler", shutdownLifecycle::shutdownScheduler
            ),
            new ShutdownStage(
                "FINAL_REPORT_WRITE_FAILED",
                "final-report",
                () -> {
                    if (!shutdownLifecycle.writeFinalReport(finalObservedHostState)) {
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

        boolean writeFinalReport(HostSession.State observedHostState) throws Throwable;

        void logDegradedShutdown() throws Throwable;

        void closeLog() throws Throwable;
    }
}
