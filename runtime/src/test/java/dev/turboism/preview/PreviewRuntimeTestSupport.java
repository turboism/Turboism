package dev.turboism.preview;

import dev.turboism.bootstrap.HostRuntimeIngress;
import dev.turboism.core.runtime.PluginExecutorRegistry;
import dev.turboism.core.runtime.RuntimeScheduler;
import dev.turboism.core.runtime.sidecar.SidecarDispatcher;
import dev.turboism.preview.report.PreviewReportWriter;
import dev.turboism.sdk.plugin.WorkBudget;

import java.nio.file.Path;
import java.time.Clock;
import java.util.List;

/** Shared preview-runtime test composition helpers. */
final class PreviewRuntimeTestSupport {

    private PreviewRuntimeTestSupport() {
    }

    static RuntimeScheduler rejectedScheduler() {
        return new RuntimeScheduler(
            task -> WorkBudget.REJECTED,
            new PluginExecutorRegistry(1, 4, ignored -> { }, Clock.systemUTC()),
            SidecarDispatcher.noop(),
            ignored -> { }
        );
    }

    static PreviewRuntime runtime(
        final Path home,
        final PreviewLog log,
        final RuntimeScheduler scheduler,
        final LocalPluginRuntime plugins
    ) {
        return new PreviewRuntime(
            home,
            log,
            scheduler,
            new HostRuntimeIngress(),
            plugins,
            new LocalPluginRuntime.LoadReport(List.of(), List.of(), List.of()),
            new PreviewReportWriter(home.resolve("state"), ignored -> { }),
            "runtime-failure-integration",
            null,
            null
        );
    }

    static void writeInitialReports(
        final PreviewRuntime runtime,
        final dev.turboism.adapter.host.HostSession.State state
    ) {
        runtime.writeInitialReports(state);
    }
}
