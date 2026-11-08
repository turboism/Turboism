package dev.turboism.kernel;

import dev.turboism.adapter.host.HostSession;
import dev.turboism.bootstrap.HostRuntimeIngress;
import dev.turboism.core.runtime.RuntimeScheduler;
import dev.turboism.core.runtime.work.FatalErrors;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Runs the ordered shutdown stages of one {@link KernelRuntime} generation.
 *
 * <p>Shutdown is best-effort and evidence-preserving: every stage runs even when an earlier one
 * failed, each failure is recorded as a stable {@link KernelRuntime.ShutdownFailure} code, and the
 * terminal report is still written. The orchestrator owns only the stage order; the runtime keeps
 * the mutable bindings the stages tear down, so the sequence cannot be re-entered or reordered by
 * a caller.</p>
 */
final class KernelShutdownOrchestrator {

    private final KernelRuntime.ShutdownLifecycle lifecycle;
    private final Runnable closeExportSettings;

    /**
     * @param lifecycle the runtime's host/plugin/log teardown seam
     * @param closeExportSettings releases the export-settings bindings held by the runtime
     */
    KernelShutdownOrchestrator(final KernelRuntime.ShutdownLifecycle lifecycle, final Runnable closeExportSettings) {
        this.lifecycle = lifecycle;
        this.closeExportSettings = closeExportSettings;
    }

    /**
     * Performs the terminal sequence and returns the recorded failures.
     *
     * @param cleanHostResources whether plugin/host resources may still be touched; {@code false}
     *     during JVM shutdown, where only terminal evidence is persisted
     * @return immutable failures in stage order, empty when every stage completed
     */
    List<KernelRuntime.ShutdownFailure> close(final boolean cleanHostResources) {
        final List<KernelRuntime.ShutdownFailure> failures = new ArrayList<>();
        try {
            lifecycle.logStopping();
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            failures.add(stableFailure("STOP_LOG_FAILED", "stop-log"));
        }

        HostSession.State observedHostState = HostSession.State.FAILED;
        try {
            observedHostState = lifecycle.hostState();
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            failures.add(stableFailure("HOST_STATE_CAPTURE_FAILED", "host-state"));
        }
        final HostSession.State finalObservedHostState = observedHostState;
        if (cleanHostResources) {
            failures.addAll(runShutdownStages(List.of(
                    new ShutdownStage("PLUGIN_RUNTIME_CLOSE_FAILED", "plugin-runtime", lifecycle::closePluginRuntime),
                    new ShutdownStage("AUTO_MESH_GUARD_CLOSE_FAILED", "auto-mesh-guard", lifecycle::closeAutoMeshGuard),
                    new ShutdownStage(
                            "EXPORT_SETTINGS_AUTHORITY_CLOSE_FAILED",
                            "export-settings-authority",
                            closeExportSettings::run),
                    new ShutdownStage("HOST_INGRESS_CLOSE_FAILED", "host-ingress", lifecycle::closeHostIngress),
                    new ShutdownStage("SCHEDULER_SHUTDOWN_FAILED", "scheduler", lifecycle::shutdownScheduler))));
        }
        failures.addAll(runShutdownStages(List.of(new ShutdownStage("FINAL_REPORT_WRITE_FAILED", "final-report", () -> {
            if (!lifecycle.writeFinalReport(finalObservedHostState, cleanHostResources)) {
                throw new IllegalStateException("Preview report persistence failed safely");
            }
        }))));

        if (!failures.isEmpty()) {
            try {
                lifecycle.logDegradedShutdown();
            } catch (Throwable failure) {
                FatalErrors.rethrowIfFatal(failure);
                failures.add(stableFailure("SHUTDOWN_SUMMARY_LOG_FAILED", "summary-log"));
            }
        }

        failures.addAll(runShutdownStages(List.of(new ShutdownStage("LOG_CLOSE_FAILED", "log", lifecycle::closeLog))));
        RecentPreviewDiagnostics.uninstall();
        if (failures.stream().anyMatch(failure -> failure.code().equals("LOG_CLOSE_FAILED"))) {
            System.err.println("Turboism preview log close failed safely: LOG_CLOSE_FAILED");
        }
        return List.copyOf(failures);
    }

    /**
     * Tears down whatever a failed bootstrap had already prepared, in reverse acquisition order.
     *
     * @param plugins plugin runtime being prepared, or {@code null} when it was never created
     * @param ingress host ingress being prepared, or {@code null} when it was never created
     * @param scheduler runtime scheduler being prepared, or {@code null} when it was never created
     * @param log the session log that must still record the failure
     * @param failure the startup failure being reported
     */
    static void abortStart(
            final LocalPluginRuntime plugins,
            final HostRuntimeIngress ingress,
            final RuntimeScheduler scheduler,
            final KernelLog log,
            final Throwable failure) {
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
        dev.turboism.runtime.log.RuntimeDiagnostics.clear();
        try {
            log.close();
        } catch (IOException ignored) {
        }
    }

    private static List<KernelRuntime.ShutdownFailure> runShutdownStages(final List<ShutdownStage> stages) {
        final List<KernelRuntime.ShutdownFailure> failures = new ArrayList<>();
        for (ShutdownStage stage : stages) {
            try {
                stage.action().run();
            } catch (Throwable failure) {
                FatalErrors.rethrowIfFatal(failure);
                failures.add(stableFailure(stage.code(), stage.phase()));
            }
        }
        return List.copyOf(failures);
    }

    private static KernelRuntime.ShutdownFailure stableFailure(final String code, final String phase) {
        return new KernelRuntime.ShutdownFailure(code, phase, "Runtime shutdown stage failed safely.");
    }

    private record ShutdownStage(String code, String phase, ShutdownAction action) {}

    @FunctionalInterface
    private interface ShutdownAction {
        void run() throws Throwable;
    }
}
