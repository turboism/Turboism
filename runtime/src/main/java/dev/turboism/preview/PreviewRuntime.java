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
import dev.turboism.preview.report.PreviewReportWriter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
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
    private final AtomicBoolean closed = new AtomicBoolean(false);

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
            reportWriter.writeAll(PreviewReportSnapshotFactory.create(
                runtimeId,
                Instant.now(),
                home,
                observedHostState,
                hostArtifact,
                verificationRecord,
                loadReport,
                pluginRuntime.reportSummaries(),
                stopped
            ));
        } catch (RuntimeException exception) {
            log.warn(
                "preview-report",
                "PREVIEW_REPORT_SNAPSHOT_FAILED: report snapshot failed safely."
            );
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        log.info("runtime", "Stopping Turboism Developer Preview");
        final HostSession.State observedHostState = hostIngress.state();
        pluginRuntime.close();
        hostIngress.close();
        scheduler.shutdown();
        writeReports(observedHostState, true);
        try {
            log.close();
        } catch (IOException exception) {
            System.err.println("Turboism preview log close failed: " + exception.getMessage());
        }
    }
}
