package dev.turboism.preview;

import dev.turboism.adapter.host.RuntimeHostAdapterAccess;
import dev.turboism.adapter.cubism.lifecycle.EditorLifecycleCoordinator;
import dev.turboism.adapter.cubism.lifecycle.EditorObjectLifecycleCoordinator;
import dev.turboism.adapter.cubism.lifecycle.ParameterLifecycleCoordinator;
import dev.turboism.adapter.cubism.lifecycle.PartLifecycleCoordinator;
import dev.turboism.adapter.cubism.lifecycle.ProjectFileLifecycleCoordinator;
import dev.turboism.cleanup.CleanupEvidenceCollector;
import dev.turboism.core.lifecycle.PluginLifecycleState;
import dev.turboism.core.plugin.PluginRuntime;
import dev.turboism.core.runtime.RuntimeScheduler;
import dev.turboism.failure.RuntimeFailureCollector;
import dev.turboism.hostread.SharedAsyncHostReadLane;
import dev.turboism.i18n.RuntimePluginLocalization;
import dev.turboism.i18n.CubismHostLocale;
import dev.turboism.sdk.plugin.DisposableScope;
import dev.turboism.sdk.plugin.TurboismPlugin;

import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/** Minimal real plugin loading and lifecycle path for Turboism 0.1. */
public final class LocalPluginRuntime implements AutoCloseable {

    private final SharedAsyncHostReadLane hostReadLane;
    private final RuntimeFailureCollector failureCollector;
    private final PreviewPluginLoadCoordinator loadCoordinator;
    private final PreviewPluginShutdown shutdown;
    private final ParameterLifecycleCoordinator parameterLifecycle;
    private final PartLifecycleCoordinator partLifecycle;
    private final EditorObjectLifecycleCoordinator editorObjectLifecycle;
    private final ProjectFileLifecycleCoordinator projectFileLifecycle;
    private final EditorLifecycleCoordinator editorLifecycleEvents;
    private final List<LoadedPlugin> loaded = new ArrayList<>();
    private final dev.turboism.pluginmanagement.RuntimePluginManagementService pluginManagement;
    private final PreviewPluginContextFactory contextFactory;
    private final dev.turboism.sdk.runtime.RuntimeSettingsService runtimeSettings;
    private final PreviewLog log;
    private List<LoadedPluginSummary> closedSummaries = List.of();
    private final AtomicBoolean started = new AtomicBoolean(false);
    private final AtomicBoolean closed = new AtomicBoolean(false);

    public LocalPluginRuntime(
        final Path home,
        final RuntimeScheduler scheduler,
        final RuntimeHostAdapterAccess hostAccess,
        final PreviewLog log
    ) {
        this(
            home,
            scheduler,
            hostAccess,
            log,
            new RuntimeFailureCollector(),
            (pluginId, phase) -> { },
            hostAccess.parameterLifecycle(),
            hostAccess.partLifecycle(),
            hostAccess.editorObjectLifecycle(),
            hostAccess.projectFileLifecycle(),
            hostAccess.editorLifecycleEvents(),
            null
        );
    }

    /** Package-private parameter-lifecycle seam retained for integration tests. */
    LocalPluginRuntime(
        final Path home,
        final RuntimeScheduler scheduler,
        final RuntimeHostAdapterAccess hostAccess,
        final PreviewLog log,
        final ParameterLifecycleCoordinator parameterLifecycle
    ) {
        this(
            home,
            scheduler,
            hostAccess,
            log,
            new RuntimeFailureCollector(),
            (pluginId, phase) -> { },
            parameterLifecycle,
            hostAccess.partLifecycle(),
            hostAccess.editorObjectLifecycle(),
            hostAccess.projectFileLifecycle(),
            hostAccess.editorLifecycleEvents(),
            null
        );
    }

    /** Production seam: preview runtime passes the shared file-chooser history singleton. */
    LocalPluginRuntime(
        final Path home,
        final RuntimeScheduler scheduler,
        final RuntimeHostAdapterAccess hostAccess,
        final PreviewLog log,
        final ParameterLifecycleCoordinator parameterLifecycle,
        final dev.turboism.sdk.cubism.filechooser.FileChooserHistoryService fileChooserHistory
    ) {
        this(
            home,
            scheduler,
            hostAccess,
            log,
            new RuntimeFailureCollector(),
            (pluginId, phase) -> { },
            parameterLifecycle,
            hostAccess.partLifecycle(),
            hostAccess.editorObjectLifecycle(),
            hostAccess.projectFileLifecycle(),
            hostAccess.editorLifecycleEvents(),
            fileChooserHistory
        );
    }

    /** Production composition seam with the locale resolved once at startup. */
    LocalPluginRuntime(
        final Path home,
        final RuntimeScheduler scheduler,
        final RuntimeHostAdapterAccess hostAccess,
        final PreviewLog log,
        final ParameterLifecycleCoordinator parameterLifecycle,
        final dev.turboism.sdk.cubism.filechooser.FileChooserHistoryService fileChooserHistory,
        final Locale effectiveLocale
    ) {
        this(
            home, scheduler, hostAccess, log, new RuntimeFailureCollector(),
            (pluginId, phase) -> { }, parameterLifecycle, hostAccess.partLifecycle(),
            hostAccess.editorObjectLifecycle(), hostAccess.projectFileLifecycle(),
            hostAccess.editorLifecycleEvents(), fileChooserHistory, effectiveLocale
        );
    }

    /** Package-private close/report-failure seam retained for lifecycle tests. */
    LocalPluginRuntime(
        final Path home,
        final RuntimeScheduler scheduler,
        final RuntimeHostAdapterAccess hostAccess,
        final PreviewLog log,
        final PluginCloseHook pluginCloseHook
    ) {
        this(
            home,
            scheduler,
            hostAccess,
            log,
            new RuntimeFailureCollector(),
            pluginCloseHook,
            hostAccess.parameterLifecycle(),
            hostAccess.partLifecycle(),
            hostAccess.editorObjectLifecycle(),
            hostAccess.projectFileLifecycle(),
            hostAccess.editorLifecycleEvents(),
            null
        );
    }

    /** Package-private report-failure seam retained for focused preview tests. */
    LocalPluginRuntime(
        final Path home,
        final RuntimeScheduler scheduler,
        final RuntimeHostAdapterAccess hostAccess,
        final PreviewLog log,
        final RuntimeFailureCollector failureCollector
    ) {
        this(
            home,
            scheduler,
            hostAccess,
            log,
            failureCollector,
            (pluginId, phase) -> { },
            hostAccess.parameterLifecycle(),
            hostAccess.partLifecycle(),
            hostAccess.editorObjectLifecycle(),
            hostAccess.projectFileLifecycle(),
            hostAccess.editorLifecycleEvents(),
            null
        );
    }

    private LocalPluginRuntime(
        final Path home,
        final RuntimeScheduler scheduler,
        final RuntimeHostAdapterAccess hostAccess,
        final PreviewLog log,
        final RuntimeFailureCollector failureCollector,
        final PluginCloseHook pluginCloseHook,
        final ParameterLifecycleCoordinator parameterLifecycle,
        final PartLifecycleCoordinator partLifecycle,
        final EditorObjectLifecycleCoordinator editorObjectLifecycle,
        final ProjectFileLifecycleCoordinator projectFileLifecycle,
        final EditorLifecycleCoordinator editorLifecycleEvents,
        final dev.turboism.sdk.cubism.filechooser.FileChooserHistoryService fileChooserHistory
    ) {
        this(
            home, scheduler, hostAccess, log, failureCollector, pluginCloseHook,
            parameterLifecycle, partLifecycle, editorObjectLifecycle, projectFileLifecycle,
            editorLifecycleEvents, fileChooserHistory, CubismHostLocale.resolve()
        );
    }

    private LocalPluginRuntime(
        final Path home,
        final RuntimeScheduler scheduler,
        final RuntimeHostAdapterAccess hostAccess,
        final PreviewLog log,
        final RuntimeFailureCollector failureCollector,
        final PluginCloseHook pluginCloseHook,
        final ParameterLifecycleCoordinator parameterLifecycle,
        final PartLifecycleCoordinator partLifecycle,
        final EditorObjectLifecycleCoordinator editorObjectLifecycle,
        final ProjectFileLifecycleCoordinator projectFileLifecycle,
        final EditorLifecycleCoordinator editorLifecycleEvents,
        final dev.turboism.sdk.cubism.filechooser.FileChooserHistoryService fileChooserHistory,
        final Locale effectiveLocale
    ) {
        final dev.turboism.sdk.cubism.filechooser.FileChooserHistoryService resolvedFileChooserHistory =
            fileChooserHistory != null
                ? fileChooserHistory
                : new dev.turboism.filechooser.RuntimeFileChooserHistoryService(
                    () -> {
                        final dev.turboism.config.RuntimeConfigRepository config =
                            new dev.turboism.config.RuntimeConfigRepository(
                                home, diagnostic -> log.warn("config", diagnostic)
                            );
                        return config.read().path("hooks").path("startup")
                            .path("separateExportSaveDirectory").asBoolean(false);
                    }
                );
        final PreviewPluginRuntimeResources resources = PreviewPluginRuntimeResources.create(
            home, scheduler, hostAccess, log, failureCollector, pluginCloseHook, loaded,
            parameterLifecycle, partLifecycle, editorObjectLifecycle,
            projectFileLifecycle, editorLifecycleEvents, resolvedFileChooserHistory,
            effectiveLocale
        );
        this.hostReadLane = resources.hostReadLane();
        this.failureCollector = resources.failureCollector();
        this.loadCoordinator = resources.loadCoordinator();
        this.shutdown = resources.shutdown();
        this.pluginManagement = resources.pluginManagement();
        this.contextFactory = resources.contextFactory();
        this.runtimeSettings = resources.runtimeSettings();
        this.log = log;
        this.parameterLifecycle = java.util.Objects.requireNonNull(
            parameterLifecycle,
            "parameterLifecycle"
        );
        this.partLifecycle = java.util.Objects.requireNonNull(partLifecycle, "partLifecycle");
        this.editorObjectLifecycle = java.util.Objects.requireNonNull(
            editorObjectLifecycle,
            "editorObjectLifecycle"
        );
        this.projectFileLifecycle = java.util.Objects.requireNonNull(
            projectFileLifecycle,
            "projectFileLifecycle"
        );
        this.editorLifecycleEvents = java.util.Objects.requireNonNull(
            editorLifecycleEvents,
            "editorLifecycleEvents"
        );
    }

    public synchronized LoadReport loadAll() {
        ensureCanStart();
        final LoadReport external = loadCoordinator.loadAll();
        try {
            loaded.add(BuiltinCorePlugin.load(
                contextFactory,
                new dev.turboism.plugin.core.CorePluginServices(
                    runtimeSettings,
                    pluginManagement,
                    dev.turboism.ui.panel.NativePanelTabFloatingBridge::toggle,
                    log
                ),
                log
            ));
        } catch (Exception failure) {
            close();
            throw new IllegalStateException("Runtime-owned core failed to load", failure);
        }
        final List<LoadedPluginSummary> summaries = new ArrayList<>(external.loaded());
        summaries.add(PreviewPluginSummaryFactory.active(loaded.get(loaded.size() - 1)));
        return new LoadReport(summaries, external.failures(), external.dependencyCycles());
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
            contextFactory.close();
            editorLifecycleEvents.close();
            projectFileLifecycle.close();
            editorObjectLifecycle.close();
            partLifecycle.close();
            parameterLifecycle.close();
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
