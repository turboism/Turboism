package dev.turboism.preview;

import dev.turboism.adapter.host.RuntimeHostAdapterAccess;
import dev.turboism.cleanup.CleanupEvidenceCollector;
import dev.turboism.core.lifecycle.PluginLifecycleState;
import dev.turboism.core.plugin.PluginRuntime;
import dev.turboism.core.runtime.RuntimeScheduler;
import dev.turboism.failure.RuntimeFailureCollector;
import dev.turboism.hostread.SharedAsyncHostReadLane;
import dev.turboism.i18n.RuntimePluginLocalization;
import dev.turboism.sdk.plugin.DisposableScope;
import dev.turboism.sdk.plugin.TurboismPlugin;

import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** Minimal real plugin loading and lifecycle path for Turboism 0.1. */
public final class LocalPluginRuntime implements AutoCloseable {

    private final SharedAsyncHostReadLane hostReadLane;
    private final RuntimeFailureCollector failureCollector;
    private final PreviewPluginLoadCoordinator loadCoordinator;
    private final PreviewPluginShutdown shutdown;
    private final List<LoadedPlugin> loaded = new ArrayList<>();
    private List<LoadedPluginSummary> closedSummaries = List.of();
    private final AtomicBoolean started = new AtomicBoolean(false);
    private final AtomicBoolean closed = new AtomicBoolean(false);

    public LocalPluginRuntime(
        final Path home,
        final RuntimeScheduler scheduler,
        final RuntimeHostAdapterAccess hostAccess,
        final PreviewLog log
    ) {
        this(home, scheduler, hostAccess, log, new RuntimeFailureCollector(), (pluginId, phase) -> { });
    }

    /** Package-private close/report-failure seam retained for lifecycle tests. */
    LocalPluginRuntime(
        final Path home,
        final RuntimeScheduler scheduler,
        final RuntimeHostAdapterAccess hostAccess,
        final PreviewLog log,
        final PluginCloseHook pluginCloseHook
    ) {
        this(home, scheduler, hostAccess, log, new RuntimeFailureCollector(), pluginCloseHook);
    }

    /** Package-private report-failure seam retained for focused preview tests. */
    LocalPluginRuntime(
        final Path home,
        final RuntimeScheduler scheduler,
        final RuntimeHostAdapterAccess hostAccess,
        final PreviewLog log,
        final RuntimeFailureCollector failureCollector
    ) {
        this(home, scheduler, hostAccess, log, failureCollector, (pluginId, phase) -> { });
    }

    private LocalPluginRuntime(
        final Path home,
        final RuntimeScheduler scheduler,
        final RuntimeHostAdapterAccess hostAccess,
        final PreviewLog log,
        final RuntimeFailureCollector failureCollector,
        final PluginCloseHook pluginCloseHook
    ) {
        final PreviewPluginRuntimeResources resources = PreviewPluginRuntimeResources.create(
            home, scheduler, hostAccess, log, failureCollector, pluginCloseHook, loaded
        );
        this.hostReadLane = resources.hostReadLane();
        this.failureCollector = resources.failureCollector();
        this.loadCoordinator = resources.loadCoordinator();
        this.shutdown = resources.shutdown();
    }

    public synchronized LoadReport loadAll() {
        ensureCanStart();
        return loadCoordinator.loadAll();
    }

    public synchronized List<LoadedPluginSummary> loadedPlugins() {
        return loaded.stream().map(PreviewPluginSummaryFactory::active).toList();
    }

    /** Immutable point-in-time report evidence for one preview report write. */
    synchronized LocalPluginRuntimeReportSnapshot reportSnapshot() {
        return new LocalPluginRuntimeReportSnapshot(
            closed.get() ? closedSummaries : currentSummaries(), failureCollector.snapshot()
        );
    }

    public synchronized List<LoadedPluginSummary> reportSummaries() {
        return closed.get() ? closedSummaries : currentSummaries();
    }

    @Override
    public synchronized void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        final List<LoadedPluginSummary> summaries = new ArrayList<>();
        try {
            summaries.addAll(shutdown.closeAll(loaded));
        } finally {
            closeHostReadLane();
            closedSummaries = PreviewPluginSummaryFactory.sorted(summaries);
            loaded.clear();
        }
    }

    private void ensureCanStart() {
        if (!started.compareAndSet(false, true)) {
            throw new IllegalStateException("LocalPluginRuntime has already started");
        }
        if (closed.get()) {
            throw new IllegalStateException("LocalPluginRuntime is closed");
        }
    }

    private List<LoadedPluginSummary> currentSummaries() {
        return PreviewPluginSummaryFactory.sorted(
            loaded.stream().map(PreviewPluginSummaryFactory::active).toList()
        );
    }

    private void closeHostReadLane() {
        try {
            hostReadLane.close();
        } catch (Throwable failure) {
            shutdown.tryLogStableFailure("runtime", "HOST_READ_LANE_CLOSE_FAILED");
        }
    }

    @FunctionalInterface
    interface PluginCloseHook {
        void run(String pluginId, String phase) throws Throwable;
    }

    static record LoadedPlugin(
        Path jar,
        PluginRuntime runtime,
        List<TurboismPlugin> entrypoints,
        DisposableScope scope,
        URLClassLoader classLoader,
        RuntimePluginLocalization localization,
        CleanupEvidenceCollector cleanupEvidence
    ) {
        LoadedPlugin {
            entrypoints = List.copyOf(entrypoints);
        }
    }

    public record PluginSummaryFailure(String code, String phase, String message) {
    }

    public record LoadedPluginSummary(
        String id,
        String name,
        String version,
        PluginLifecycleState state,
        Path jar,
        List<String> capabilities,
        List<String> permissionIds,
        RuntimePluginLocalization.ReportSnapshot localization,
        String disableState,
        String shutdownState,
        String unloadState,
        String scopeCleanupState,
        String classloaderCleanupState,
        List<PluginSummaryFailure> failures,
        CleanupEvidenceCollector.Snapshot cleanupEvidence
    ) {
        public LoadedPluginSummary {
            capabilities = List.copyOf(capabilities);
            permissionIds = List.copyOf(permissionIds);
            failures = List.copyOf(failures);
            cleanupEvidence = Objects.requireNonNull(cleanupEvidence, "cleanupEvidence");
        }
    }

    public record PluginFailure(String pluginId, Path jar, String code, String message) {
    }

    public record LoadReport(
        List<LoadedPluginSummary> loaded,
        List<PluginFailure> failures,
        List<String> dependencyCycles
    ) {
        public LoadReport {
            loaded = List.copyOf(loaded);
            failures = List.copyOf(failures);
            dependencyCycles = List.copyOf(dependencyCycles);
        }
    }
}
