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

    private final Path home;
    private final SharedAsyncHostReadLane hostReadLane;
    private final RuntimeFailureCollector failureCollector;
    private final PreviewPluginLoadCoordinator loadCoordinator;
    private final PreviewPluginShutdown shutdown;
    private final ParameterLifecycleCoordinator parameterLifecycle;
    private final PartLifecycleCoordinator partLifecycle;
    private final EditorObjectLifecycleCoordinator editorObjectLifecycle;
    private final ProjectFileLifecycleCoordinator projectFileLifecycle;
    private final EditorLifecycleCoordinator editorLifecycleEvents;
    // Live management view: the core is appended before external plugins while the management
    // service streams this list on other threads, so publication must be concurrency-safe.
    private final List<LoadedPlugin> loaded =
        new java.util.concurrent.CopyOnWriteArrayList<>();
    private final dev.turboism.pluginmanagement.RuntimePluginManagementService pluginManagement;
    private final PreviewPluginContextFactory contextFactory;
    private final dev.turboism.sdk.runtime.RuntimeSettingsService runtimeSettings;
    private final dev.turboism.internal.core.CubismJvmSettingsService cubismJvmSettings;
    private final dev.turboism.internal.core.MeshTriangulationSettingsService
        meshTriangulationSettings;
    private final dev.turboism.internal.core.AtlasTileBboxSettingsService
        atlasTileBboxSettings;
    private final dev.turboism.internal.core.AtlasCacheReuseSettingsService
        atlasCacheReuseSettings;
    private final dev.turboism.internal.core.CoreUpdateService updateService;
    private final PreviewLog log;
    private final PluginLifecyclePolicy lifecyclePolicy;
    private final PluginLifecycleLane lifecycleLane;
    private final RetainedPluginGenerations retention;
    /** Composition-supplied shell factory; {@code null} is the supported headless mode. */
    private final dev.turboism.internal.core.ShellAdmission shellAdmission;
    private CoreShellRuntime coreShell;
    private List<LoadedPluginSummary> closedSummaries = List.of();
    private final AtomicBoolean started = new AtomicBoolean(false);
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private dev.turboism.cleanup.RetryableCleanup cleanup;

    /**
     * Composition convenience: the framework shell is admitted by default. The lazily
     * resolved default keeps a runtime built without the shell classes linkable only
     * through the explicit headless overload below.
     */
    public LocalPluginRuntime(
        final Path home,
        final RuntimeScheduler scheduler,
        final RuntimeHostAdapterAccess hostAccess,
        final PreviewLog log
    ) {
        this(home, scheduler, hostAccess, log, CoreShellRuntime.frameworkAdmission());
    }

    /**
     * Composition seam for the framework shell: a {@code null} {@code shellAdmission}
     * runs the runtime headless — external plugins still load, no shell is admitted
     * and no shell implementation class is resolved.
     */
    public LocalPluginRuntime(
        final Path home,
        final RuntimeScheduler scheduler,
        final RuntimeHostAdapterAccess hostAccess,
        final PreviewLog log,
        final dev.turboism.internal.core.ShellAdmission shellAdmission
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
            null,
            CubismHostLocale.resolve(),
            null,
            shellAdmission
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

    /**
     * Production composition seam with the locale resolved once at startup. The
     * {@code shellAdmission} is supplied by composition; {@code null} runs the runtime
     * headless — external plugins still load, no shell is admitted and no shell
     * implementation class is resolved.
     */
    LocalPluginRuntime(
        final Path home,
        final RuntimeScheduler scheduler,
        final RuntimeHostAdapterAccess hostAccess,
        final PreviewLog log,
        final ParameterLifecycleCoordinator parameterLifecycle,
        final dev.turboism.sdk.cubism.filechooser.FileChooserHistoryService fileChooserHistory,
        final Locale effectiveLocale,
        final dev.turboism.internal.core.ShellAdmission shellAdmission
    ) {
        this(
            home, scheduler, hostAccess, log, new RuntimeFailureCollector(),
            (pluginId, phase) -> { }, parameterLifecycle, hostAccess.partLifecycle(),
            hostAccess.editorObjectLifecycle(), hostAccess.projectFileLifecycle(),
            hostAccess.editorLifecycleEvents(), fileChooserHistory, effectiveLocale, null,
            shellAdmission
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
            editorLifecycleEvents, fileChooserHistory, CubismHostLocale.resolve(), null, null
        );
    }

    /**
     * Package-private lifecycle-policy seam retained for lifecycle tests: short deadlines keep
     * bounded-load/close assertions fast without touching production defaults.
     */
    LocalPluginRuntime(
        final Path home,
        final RuntimeScheduler scheduler,
        final RuntimeHostAdapterAccess hostAccess,
        final PreviewLog log,
        final PluginCloseHook pluginCloseHook,
        final PluginLifecyclePolicy lifecyclePolicy
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
            null,
            CubismHostLocale.resolve(),
            lifecyclePolicy,
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
        final dev.turboism.sdk.cubism.filechooser.FileChooserHistoryService fileChooserHistory,
        final Locale effectiveLocale,
        final PluginLifecyclePolicy lifecyclePolicy,
        final dev.turboism.internal.core.ShellAdmission shellAdmission
    ) {
        this.home = Objects.requireNonNull(home, "home").toAbsolutePath().normalize();
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
            effectiveLocale, lifecyclePolicy
        );
        this.hostReadLane = resources.hostReadLane();
        this.failureCollector = resources.failureCollector();
        this.loadCoordinator = resources.loadCoordinator();
        this.shutdown = resources.shutdown();
        this.pluginManagement = resources.pluginManagement();
        this.contextFactory = resources.contextFactory();
        this.runtimeSettings = resources.runtimeSettings();
        this.cubismJvmSettings = resources.cubismJvmSettings();
        this.meshTriangulationSettings = resources.meshTriangulationSettings();
        this.atlasTileBboxSettings = resources.atlasTileBboxSettings();
        this.atlasCacheReuseSettings = resources.atlasCacheReuseSettings();
        this.updateService = resources.updateService();
        this.lifecyclePolicy = resources.lifecyclePolicy();
        this.lifecycleLane = resources.lifecycleLane();
        this.retention = resources.retention();
        this.shellAdmission = shellAdmission;
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
     * Binds the host-level export-settings authority for every plugin created from now on.
     *
     * <p>The Preview runtime calls this before {@link #loadAll()}, because a plugin that is already
     * loaded cannot be reached by the native dialog. Without this binding each plugin's contribution
     * registry stays plugin-private and the Editor keeps its native export-settings dialog.</p>
     *
     * @param authority host-level export-settings policy
     */
    void bindExportSettingsAuthority(
        final dev.turboism.exportsettings.RuntimeExportSettingsAuthority authority
    ) {
        contextFactory.bindExportSettingsAuthority(authority);
    }

    /**
     * Starts the runtime-owned framework shell first — when a {@code ShellAdmission} was
     * wired by composition — then loads every discovered external plugin, exactly once
     * per runtime instance. A {@code null} admission is the supported headless mode:
     * external plugins still load and no shell implementation class is resolved.
     *
     * <p>The shell is admitted on the bounded lifecycle lane under
     * {@link PluginLifecyclePolicy#loadTimeout} before any external plugin, so a blocking
     * or non-returning plugin cannot prevent management and diagnostic surfaces from
     * coming up; a shell that misses its deadline is fenced and its generation retained
     * rather than half-started. External plugins load sequentially in resolved dependency
     * order on the same lane; failures are reported in the returned {@link LoadReport}
     * and do not stop the load. The shell is not a plugin: it never appears in the
     * report and never produces plugin lifecycle events. A failure of the runtime-owned
     * shell is different in kind: the whole runtime is closed before the exception
     * propagates, so no half-initialized runtime is left behind.</p>
     *
     * @return the load outcome for the discovered plugins
     * @throws IllegalStateException if the runtime has already been started or is closed, or if
     *     the runtime-owned shell failed to start
     */
    public synchronized LoadReport loadAll() {
        ensureCanStart();
        if (shellAdmission != null) {
            try {
                coreShell = CoreShellRuntime.start(
                    contextFactory,
                    shutdown,
                    shellAdmission,
                    new dev.turboism.internal.core.ShellServices(
                        runtimeSettings,
                        cubismJvmSettings,
                        meshTriangulationSettings,
                        atlasTileBboxSettings,
                        atlasCacheReuseSettings,
                        dev.turboism.ui.settings.ProcessSettingsContributions.forHost(
                            contextFactory.hostAccessIdentity()
                        ),
                        pluginManagement,
                        dev.turboism.ui.panel.NativePanelTabFloatingBridge::toggle,
                        log,
                        updateService
                    ),
                    lifecyclePolicy,
                    lifecycleLane,
                    retention,
                    log
                );
            } catch (Exception failure) {
                log.error(
                    dev.turboism.internal.core.CorePluginManagement.CORE_PLUGIN_ID,
                    "Shell startup failed",
                    failure
                );
                close();
                throw new IllegalStateException("Runtime-owned shell failed to start", failure);
            }
        } else {
            log.info(
                "plugins",
                "Plugin lifecycle: no shell admission wired; running headless"
            );
        }
        return loadCoordinator.loadAll();
    }

    /**
     * @return summaries of the plugins currently held live by this runtime, in load order and
     *     unsorted; empty after {@link #close()} has cleared them. For report output prefer
     *     {@link #reportSummaries()}, which survives shutdown and is sorted.
     */
    public synchronized List<LoadedPluginSummary> loadedPlugins() {
        return loaded.stream().map(PreviewPluginSummaryFactory::active).toList();
    }

    StartupEnvironment startupEnvironment() {
        return new StartupEnvironment(
            contextFactory.graalConfiguration(),
            dev.turboism.script.RuntimeScriptService.discoveredScriptCount(home)
        );
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
        // Stop admission immediately, but do not confuse it with completed cleanup.
        closed.set(true);
        if (cleanup == null) {
            cleanup = new dev.turboism.cleanup.RetryableCleanup(
                "Local plugin runtime cleanup failed",
                () -> {
                    // Immediate non-blocking fence of every live plugin before any close
                    // wait: even a closeAll that dies mid-sequence leaves no plugin able
                    // to admit new work while the shell drain barrier holds teardown.
                    shutdown.fence(loaded);
                },
                () -> {
                    closedSummaries = PreviewPluginSummaryFactory.sorted(shutdown.closeAll(loaded));
                    loaded.clear();
                },
                () -> {
                    // The shell was admitted before external plugins, so it leaves only
                    // after they have drained: its close is admitted to the lifecycle
                    // lane here, while admissions are still open.
                    if (coreShell != null) {
                        coreShell.close();
                        coreShell = null;
                    }
                },
                () -> {
                    // No new lifecycle admissions, then the retention watcher finishes
                    // reclaiming fenced generations on the lane; only when that set drains
                    // does the lane shut down, so cleanup that can still complete is not
                    // abandoned while the JVM lives.
                    lifecycleLane.stopAdmission();
                    retention.retire(lifecycleLane::shutdown);
                },
                updateService::close,
                this::closeJvmSettings,
                contextFactory::close,
                editorLifecycleEvents::close,
                projectFileLifecycle::close,
                editorObjectLifecycle::close,
                partLifecycle::close,
                parameterLifecycle::close,
                this::closeHostReadLane
            );
        }
        cleanup.close();
    }

    private void closeJvmSettings() throws Exception {
        if (cubismJvmSettings instanceof AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (Exception | Error failure) {
                shutdown.tryLogStableFailure("runtime", "GRAAL_RUNTIME_CLOSE_FAILED");
                throw failure;
            }
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
        } catch (RuntimeException | Error failure) {
            shutdown.tryLogStableFailure("runtime", "HOST_READ_LANE_CLOSE_FAILED");
            throw failure;
        }
    }

    record StartupEnvironment(
        dev.turboism.graal.GraalHostConfiguration graalConfiguration,
        int discoveredScriptCount
    ) {
        StartupEnvironment {
            graalConfiguration = Objects.requireNonNull(
                graalConfiguration, "graalConfiguration"
            );
            if (discoveredScriptCount < 0) {
                throw new IllegalArgumentException(
                    "discoveredScriptCount must not be negative"
                );
            }
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
        CleanupEvidenceCollector cleanupEvidence,
        dev.turboism.core.event.RuntimeEventBroker.Owner eventOwner,
        dev.turboism.core.plugin.context.CorePluginContext context,
        PluginGenerationGuard guard
    ) {
        LoadedPlugin {
            entrypoints = List.copyOf(entrypoints);
            eventOwner = Objects.requireNonNull(eventOwner, "eventOwner");
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
