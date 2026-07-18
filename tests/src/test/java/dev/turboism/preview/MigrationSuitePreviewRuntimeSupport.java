package dev.turboism.preview;

import dev.turboism.adapter.host.HostSession;
import dev.turboism.bootstrap.HostRuntimeIngress;
import dev.turboism.core.runtime.RuntimeScheduler;
import dev.turboism.preview.report.PreviewReportWriter;

import java.nio.file.Path;

/** Test-only bridge for exercising package-private preview composition without widening production APIs. */
public final class MigrationSuitePreviewRuntimeSupport {

    private MigrationSuitePreviewRuntimeSupport() {
    }

    public static PreviewRuntime compose(
        final Path home,
        final PreviewLog log,
        final RuntimeScheduler scheduler,
        final HostRuntimeIngress ingress,
        final LocalPluginRuntime plugins,
        final LocalPluginRuntime.LoadReport loadReport
    ) {
        return new PreviewRuntime(
            home,
            log,
            scheduler,
            ingress,
            plugins,
            loadReport,
            new PreviewReportWriter(home.resolve("state"), ignored -> { }),
            "migration-suite-safe-child",
            null,
            null
        );
    }

    public static void writeInitialReports(
        final PreviewRuntime runtime,
        final HostSession.State hostState
    ) {
        runtime.writeInitialReports(hostState);
    }
}
