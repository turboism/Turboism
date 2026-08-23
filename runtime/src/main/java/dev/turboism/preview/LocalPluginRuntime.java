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
    private final dev.turboism.plugin.core.CubismJvmSettingsService cubismJvmSettings;
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
        this.cubismJvmSettings = resources.cubismJvmSettings();
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

    /**
     * Loads every discovered plugin, then the runtime-owned core plugin, exactly once per runtime
     * instance.
     *
     * <p>External plugin failures are reported in the returned {@link LoadReport} and do not stop
     * the load. A failure of the runtime-owned core is different in kind: the whole runtime is
     * closed before the exception propagates, so no half-initialized runtime is left behind.</p>
     *
     * @return the load outcome, with the core plugin appended to the loaded summaries
     * @throws IllegalStateException if the runtime has already been started or is closed, or if
     *     the runtime-owned core plugin failed to load
     */
    public synchronized LoadReport loadAll() {
        ensureCanStart();
        final LoadReport external = loadCoordinator.loadAll();
        try {
            loaded.add(BuiltinCorePlugin.load(
                contextFactory,
                new dev.turboism.plugin.core.CorePluginServices(
                    runtimeSettings,
                    cubismJvmSettings,
                    dev.turboism.ui.settings.ProcessSettingsContributions.forHost(
                        contextFactory.hostAccessIdentity()
                    ),
                    pluginManagement,
                    dev.turboism.ui.panel.NativePanelTabFloatingBridge::toggle,
                    log
                ),
                log
            ));
        } catch (Exception failure) {
            log.error(
                dev.turboism.plugin.core.CorePluginManagement.CORE_PLUGIN_ID,
                "Plugin lifecycle: built-in load failed",
                failure
            );
            close();
            throw new IllegalStateException("Runtime-owned core failed to load", failure);
        }
        final List<LoadedPluginSummary> summaries = new ArrayList<>(external.loaded());
        summaries.add(PreviewPluginSummaryFactory.active(loaded.get(loaded.size() - 1)));
        return new LoadReport(summaries, external.failures(), external.dependencyCycles());
    }

    /**
     * @return summaries of the plugins currently held live by this runtime, in load order and
     *     unsorted; empty after {@link #close()} has cleared them. For report output prefer
     *     {@link #reportSummaries()}, which survives shutdown and is sorted.
     */
    public synchronized List<LoadedPluginSummary> loadedPlugins() {
        return loaded.stream().map(PreviewPluginSummaryFactory::active).toList();
    }


    /** Immutable point-in-time report evidence for one preview report write. */
    synchronized LocalPluginRuntimeReportSnapshot reportSnapshot() {
        return new LocalPluginRuntimeReportSnapshot(
            closed.get() ? closedSummaries : currentSummaries(), failureCollector.snapshot()
        );
    }

    /**
     * @return sorted plugin summaries suitable for a report. Before shutdown these describe the
     *     live plugins; once closed, the summaries captured during shutdown are returned instead,
     *     so shutdown evidence remains readable after the plugins themselves are gone.
     */
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

    /**
     * One thing that went wrong for a plugin, recorded against its summary.
     *
     * @param code stable diagnostic code; the part callers should branch on
     * @param phase lifecycle phase during which it happened, such as disable or unload
     * @param message human-readable detail, for reports only
     */
    public record PluginSummaryFailure(String code, String phase, String message) {
    }

    /**
     * Report-safe description of one plugin: its identity, its lifecycle outcome, and the evidence
     * gathered while shutting it down.
     *
     * <p>Carries scalars and copied lists only — never a live plugin object or classloader — so a
     * summary can outlive the plugin it describes, which is exactly what {@link #reportSummaries()}
     * relies on after {@link #close()}. The four {@code *State} components record how far each
     * shutdown step got.</p>
     *
     * @param id plugin identifier
     * @param name plugin display name
     * @param version plugin version string
     * @param state lifecycle state at the time the summary was taken
     * @param jar path of the plugin JAR this instance was loaded from
     * @param capabilities unmodifiable copy of the capabilities the plugin declared
     * @param permissionIds unmodifiable copy of the permission IDs the plugin declared
     * @param localization report view of the plugin's localization bundles
     * @param disableState outcome of the disable step
     * @param shutdownState outcome of the shutdown step
     * @param unloadState outcome of the unload step
     * @param scopeCleanupState outcome of releasing the plugin's disposable scope
     * @param classloaderCleanupState outcome of releasing the plugin's classloader
     * @param failures unmodifiable copy of the failures recorded for this plugin
     * @param cleanupEvidence evidence collected about what the plugin left behind
     * @throws NullPointerException if {@code cleanupEvidence} is null
     */
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

    /**
     * A plugin that could not be loaded at all, and therefore has no summary.
     *
     * @param pluginId identifier of the plugin, as far as it could be determined
     * @param jar path of the JAR that failed to load
     * @param code stable diagnostic code for the failure
     * @param message human-readable detail, for reports only
     */
    public record PluginFailure(String pluginId, Path jar, String code, String message) {
    }

    /**
     * Outcome of a whole load pass: what came up, what did not, and what could not be ordered.
     *
     * <p>All three components are defensively copied. A non-empty {@code failures} or
     * {@code dependencyCycles} does not mean the runtime is unusable — the plugins in
     * {@code loaded} are live regardless.</p>
     *
     * @param loaded unmodifiable copy of the summaries of successfully loaded plugins
     * @param failures unmodifiable copy of the plugins that failed to load
     * @param dependencyCycles unmodifiable copy of the dependency cycles detected while ordering
     *     the load, whose members could not be loaded
     */
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
