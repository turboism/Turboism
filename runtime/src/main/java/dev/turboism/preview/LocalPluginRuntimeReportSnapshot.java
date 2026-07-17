package dev.turboism.preview;

import dev.turboism.failure.RuntimeFailureSnapshot;

import java.util.List;
import java.util.Objects;

/** Package-private immutable point-in-time evidence exported by the local plugin runtime. */
record LocalPluginRuntimeReportSnapshot(
    List<LocalPluginRuntime.LoadedPluginSummary> pluginSummaries,
    RuntimeFailureSnapshot failures
) {
    public LocalPluginRuntimeReportSnapshot {
        pluginSummaries = List.copyOf(Objects.requireNonNull(pluginSummaries, "pluginSummaries"));
        failures = Objects.requireNonNull(failures, "failures");
    }
}
