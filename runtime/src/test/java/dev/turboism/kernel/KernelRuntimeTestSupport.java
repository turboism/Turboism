package dev.turboism.kernel;

import dev.turboism.bootstrap.HostRuntimeIngress;
import dev.turboism.core.runtime.RuntimeScheduler;
import dev.turboism.core.runtime.work.PluginWorkExecutorRegistry;
import dev.turboism.kernel.report.KernelReportWriter;
import dev.turboism.sdk.plugin.WorkBudget;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;

/** Shared preview-runtime test composition helpers. */
final class KernelRuntimeTestSupport {

    private KernelRuntimeTestSupport() {}

    static RuntimeScheduler rejectedScheduler() {
        return new RuntimeScheduler(
                task -> WorkBudget.REJECTED,
                new PluginWorkExecutorRegistry(1, 4, ignored -> {}, Clock.systemUTC()),
                ignored -> {});
    }

    static KernelRuntime runtime(
            final Path home, final KernelLog log, final RuntimeScheduler scheduler, final LocalPluginRuntime plugins) {
        return new KernelRuntime(
                home,
                log,
                scheduler,
                new HostRuntimeIngress(),
                plugins,
                new LocalPluginRuntime.LoadReport(List.of(), List.of(), List.of()),
                new KernelReportWriter(home.resolve("state"), ignored -> {}),
                "runtime-failure-integration",
                null,
                null);
    }

    static void writeInitialReports(
            final KernelRuntime runtime, final dev.turboism.adapter.host.HostSession.State state) {
        runtime.writeInitialReports(state);
    }
}
