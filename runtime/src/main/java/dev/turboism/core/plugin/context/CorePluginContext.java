package dev.turboism.core.plugin.context;

import dev.turboism.adapter.RuntimeHostAdapters;
import dev.turboism.adapter.cubism.HostSnapshotSource;
import dev.turboism.adapter.cubism.service.read.M12ReadSnapshotSource;
import dev.turboism.adapter.host.RuntimeHostAdapterAccess;
import dev.turboism.core.event.RuntimeEventBroker;
import dev.turboism.core.runtime.RuntimeScheduler;
import dev.turboism.diagnostics.CubismFacadeAuditEvent;
import dev.turboism.failure.RuntimeFailureSink;
import dev.turboism.sdk.action.ActionRegistry;
import dev.turboism.sdk.config.PluginConfigRegistry;
import dev.turboism.sdk.cubism.CubismFacade;
import dev.turboism.sdk.cubism.export.ExportSettingsContributionService;
import dev.turboism.sdk.cubism.mesh.MeshToolRegistry;
import dev.turboism.sdk.cubism.service.query.ModelHierarchyQueryService;
import dev.turboism.sdk.cubism.service.query.SelectionQueryService;
import dev.turboism.sdk.cubism.service.read.CubismReadCapabilityService;
import dev.turboism.sdk.diagnostics.DiagnosticReport;
import dev.turboism.sdk.event.EventBus;
import dev.turboism.sdk.hostread.AsyncHostReadService;
import dev.turboism.sdk.i18n.PluginLocalization;
import dev.turboism.sdk.mcp.McpConnectionService;
import dev.turboism.sdk.menu.MenuRegistry;
import dev.turboism.sdk.permission.PluginPermission;
import dev.turboism.sdk.plugin.DisposableScope;
import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.sdk.plugin.PluginDescriptor;
import dev.turboism.sdk.plugin.PluginLogger;
import dev.turboism.sdk.plugin.PluginPaths;
import dev.turboism.sdk.plugin.PluginService;
import dev.turboism.sdk.plugin.PluginServiceDirectory;
import dev.turboism.sdk.script.ScriptService;
import dev.turboism.sdk.storage.PluginStorage;
import dev.turboism.sdk.task.PluginTaskScheduler;
import dev.turboism.sdk.ui.UiScheduler;
import dev.turboism.sdk.ui.UserFileAccessService;
import dev.turboism.sdk.ui.context.ContextMenuRegistry;
import dev.turboism.sdk.ui.filter.PaletteFilterRegistry;
import dev.turboism.sdk.ui.toolbar.MainToolbarRegistry;
import dev.turboism.sdk.ui.toolbar.PaletteToolbarRegistry;
import dev.turboism.ui.UiHostStateSource;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * The runtime's implementation of the SDK {@link PluginContext} — the single object through which
 * one loaded plugin reaches every service it is allowed to use.
 *
 * <p>Construction is driven by a {@code Dependencies} record, so a plugin never wires up its own
 * services and never gets a reference to anything the runtime did not hand it. The context holds
 * only SDK-facing types; native Editor objects stay behind the runtime services it exposes.
 */
public final class CorePluginContext implements PluginContext {

    private final Dependencies dependencies;
    private final CubismContextServices cubismServices;
    private final HostCapabilityBundle hostCapabilities;
    private final List<PluginCapabilityBundle> capabilityBundles;
    private volatile ScriptService scriptService = ScriptService.unavailable();
    private volatile McpConnectionService mcpConnectionService = McpConnectionService.unavailable();
    private volatile dev.turboism.sdk.performance.PerformanceProbeService performanceStatsService;
    private volatile dev.turboism.sdk.cubism.worktime.ProjectWorkTimeService projectWorkTimeService;
    private final CorePluginInstallableService<MeshToolRegistry> meshTools =
            new CorePluginInstallableService<>("mesh tools");
    private final CorePluginInstallableService<dev.turboism.sdk.cubism.modeling.ModelingToolRegistry> modelingTools =
            new CorePluginInstallableService<>("modeling tools");
    private final RuntimePluginServiceDirectory serviceDirectory = new RuntimePluginServiceDirectory();

    public CorePluginContext(final Dependencies dependencies) {
        this(dependencies, PluginContextEnvironment.safeMode().build());
    }

    /** Production composition seam for a verified, fail-closed host-session view. */
    public CorePluginContext(final Dependencies dependencies, final RuntimeHostAdapterAccess hostAccess) {
        this(dependencies, PluginContextEnvironment.builder(hostAccess).build());
    }

    public CorePluginContext(
            final Dependencies dependencies,
            final RuntimeHostAdapterAccess hostAccess,
            final PluginLocalization localization) {
        this(
                dependencies,
                PluginContextEnvironment.builder(hostAccess)
                        .localization(localization)
                        .build());
    }

    public CorePluginContext(
            final Dependencies dependencies,
            final RuntimeHostAdapterAccess hostAccess,
            final PluginLocalization localization,
            final PluginTaskScheduler taskScheduler) {
        this(
                dependencies,
                PluginContextEnvironment.builder(hostAccess)
                        .localization(localization)
                        .taskScheduler(taskScheduler)
                        .build());
    }

    public CorePluginContext(
            final Dependencies dependencies,
            final RuntimeHostAdapterAccess hostAccess,
            final PluginLocalization localization,
            final PluginTaskScheduler taskScheduler,
            final PluginStorage pluginStorage) {
        this(
                dependencies,
                PluginContextEnvironment.builder(hostAccess)
                        .localization(localization)
                        .taskScheduler(taskScheduler)
                        .pluginStorage(pluginStorage)
                        .build());
    }

    public CorePluginContext(
            final Dependencies dependencies,
            final RuntimeHostAdapterAccess hostAccess,
            final PluginLocalization localization,
            final PluginTaskScheduler taskScheduler,
            final PluginStorage pluginStorage,
            final UserFileAccessService userFileAccessService) {
        this(
                dependencies,
                PluginContextEnvironment.builder(hostAccess)
                        .localization(localization)
                        .taskScheduler(taskScheduler)
                        .pluginStorage(pluginStorage)
                        .userFiles(userFileAccessService)
                        .build());
    }

    public CorePluginContext(
            final Dependencies dependencies,
            final RuntimeHostAdapterAccess hostAccess,
            final PluginLocalization localization,
            final PluginTaskScheduler taskScheduler,
            final PluginStorage pluginStorage,
            final UserFileAccessService userFileAccessService,
            final AsyncHostReadService asyncHostReadService) {
        this(
                dependencies,
                PluginContextEnvironment.builder(hostAccess)
                        .localization(Objects.requireNonNull(localization, "localization"))
                        .taskScheduler(Objects.requireNonNull(taskScheduler, "taskScheduler"))
                        .pluginStorage(Objects.requireNonNull(pluginStorage, "pluginStorage"))
                        .userFiles(Objects.requireNonNull(userFileAccessService, "userFileAccessService"))
                        .hostReads(Objects.requireNonNull(asyncHostReadService, "asyncHostReadService"))
                        .build());
    }

    public CorePluginContext(
            final Dependencies dependencies,
            final RuntimeHostAdapterAccess hostAccess,
            final PluginLocalization localization,
            final PluginTaskScheduler taskScheduler,
            final PluginStorage pluginStorage,
            final UserFileAccessService userFileAccessService,
            final AsyncHostReadService asyncHostReadService,
            final dev.turboism.sdk.runtime.RuntimeSettingsService runtimeSettings) {
        this(
                dependencies,
                PluginContextEnvironment.builder(hostAccess)
                        .localization(Objects.requireNonNull(localization, "localization"))
                        .taskScheduler(Objects.requireNonNull(taskScheduler, "taskScheduler"))
                        .pluginStorage(Objects.requireNonNull(pluginStorage, "pluginStorage"))
                        .userFiles(Objects.requireNonNull(userFileAccessService, "userFileAccessService"))
                        .hostReads(Objects.requireNonNull(asyncHostReadService, "asyncHostReadService"))
                        .runtimeSettings(runtimeSettings)
                        .build());
    }

    public CorePluginContext(
            final Dependencies dependencies,
            final RuntimeHostAdapterAccess hostAccess,
            final PluginLocalization localization,
            final PluginTaskScheduler taskScheduler,
            final PluginStorage pluginStorage,
            final UserFileAccessService userFileAccessService,
            final AsyncHostReadService asyncHostReadService,
            final dev.turboism.sdk.runtime.RuntimeSettingsService runtimeSettings,
            final dev.turboism.sdk.cubism.filechooser.FileChooserHistoryService fileChooserHistory) {
        this(
                dependencies,
                PluginContextEnvironment.builder(hostAccess)
                        .localization(Objects.requireNonNull(localization, "localization"))
                        .taskScheduler(Objects.requireNonNull(taskScheduler, "taskScheduler"))
                        .pluginStorage(Objects.requireNonNull(pluginStorage, "pluginStorage"))
                        .userFiles(Objects.requireNonNull(userFileAccessService, "userFileAccessService"))
                        .hostReads(Objects.requireNonNull(asyncHostReadService, "asyncHostReadService"))
                        .runtimeSettings(runtimeSettings)
                        .fileChooserHistory(fileChooserHistory)
                        .build());
    }

    public CorePluginContext(
            final Dependencies dependencies,
            final RuntimeHostAdapterAccess hostAccess,
            final PluginLocalization localization,
            final PluginTaskScheduler taskScheduler,
            final PluginStorage pluginStorage,
            final UserFileAccessService userFileAccessService,
            final AsyncHostReadService asyncHostReadService,
            final dev.turboism.sdk.runtime.RuntimeSettingsService runtimeSettings,
            final dev.turboism.sdk.cubism.filechooser.FileChooserHistoryService fileChooserHistory,
            final ExportSettingsContributionService exportSettings) {
        this(
                dependencies,
                PluginContextEnvironment.builder(hostAccess)
                        .localization(Objects.requireNonNull(localization, "localization"))
                        .taskScheduler(Objects.requireNonNull(taskScheduler, "taskScheduler"))
                        .pluginStorage(Objects.requireNonNull(pluginStorage, "pluginStorage"))
                        .userFiles(Objects.requireNonNull(userFileAccessService, "userFileAccessService"))
                        .hostReads(Objects.requireNonNull(asyncHostReadService, "asyncHostReadService"))
                        .runtimeSettings(runtimeSettings)
                        .fileChooserHistory(fileChooserHistory)
                        .exportSettings(Objects.requireNonNull(exportSettings, "exportSettings"))
                        .build());
    }

    CorePluginContext(final Dependencies dependencies, final RuntimeHostAdapters hostAdapters) {
        this(dependencies, PluginContextEnvironment.builder(hostAdapters).build());
    }

    CorePluginContext(
            final Dependencies dependencies,
            final RuntimeHostAdapters hostAdapters,
            final PluginLocalization localization) {
        this(
                dependencies,
                PluginContextEnvironment.builder(hostAdapters)
                        .localization(localization)
                        .build());
    }

    CorePluginContext(
            final Dependencies dependencies,
            final RuntimeHostAdapters hostAdapters,
            final PluginLocalization localization,
            final PluginTaskScheduler taskScheduler) {
        this(
                dependencies,
                PluginContextEnvironment.builder(hostAdapters)
                        .localization(localization)
                        .taskScheduler(taskScheduler)
                        .build());
    }

    CorePluginContext(
            final Dependencies dependencies,
            final RuntimeHostAdapters hostAdapters,
            final PluginLocalization localization,
            final PluginTaskScheduler taskScheduler,
            final PluginStorage pluginStorage) {
        this(
                dependencies,
                PluginContextEnvironment.builder(hostAdapters)
                        .localization(localization)
                        .taskScheduler(taskScheduler)
                        .pluginStorage(pluginStorage)
                        .build());
    }

    CorePluginContext(
            final Dependencies dependencies,
            final RuntimeHostAdapters hostAdapters,
            final PluginLocalization localization,
            final PluginTaskScheduler taskScheduler,
            final PluginStorage pluginStorage,
            final UserFileAccessService userFileAccessService) {
        this(
                dependencies,
                PluginContextEnvironment.builder(hostAdapters)
                        .localization(localization)
                        .taskScheduler(taskScheduler)
                        .pluginStorage(pluginStorage)
                        .userFiles(userFileAccessService)
                        .build());
    }

    CorePluginContext(
            final Dependencies dependencies,
            final RuntimeHostAdapters hostAdapters,
            final PluginLocalization localization,
            final PluginTaskScheduler taskScheduler,
            final PluginStorage pluginStorage,
            final UserFileAccessService userFileAccessService,
            final AsyncHostReadService asyncHostReadService) {
        this(
                dependencies,
                PluginContextEnvironment.builder(hostAdapters)
                        .localization(localization)
                        .taskScheduler(taskScheduler)
                        .pluginStorage(pluginStorage)
                        .userFiles(userFileAccessService)
                        .hostReads(asyncHostReadService)
                        .build());
    }

    /** Test composition seam: package-private {@link RuntimeHostAdapters} view with an injected file-chooser history service. */
    CorePluginContext(
            final Dependencies dependencies,
            final RuntimeHostAdapters hostAdapters,
            final PluginLocalization localization,
            final PluginTaskScheduler taskScheduler,
            final PluginStorage pluginStorage,
            final UserFileAccessService userFileAccessService,
            final AsyncHostReadService asyncHostReadService,
            final dev.turboism.sdk.cubism.filechooser.FileChooserHistoryService fileChooserHistory) {
        this(
                dependencies,
                PluginContextEnvironment.builder(hostAdapters)
                        .localization(localization)
                        .taskScheduler(taskScheduler)
                        .pluginStorage(pluginStorage)
                        .userFiles(userFileAccessService)
                        .hostReads(asyncHostReadService)
                        .fileChooserHistory(fileChooserHistory)
                        .build());
    }

    CorePluginContext(final Dependencies dependencies, final CubismServicesFactory cubismServicesFactory) {
        this(
                dependencies,
                PluginContextEnvironment.safeMode()
                        .servicesFactory(Objects.requireNonNull(cubismServicesFactory, "cubismServicesFactory"))
                        .build());
    }

    /**
     * Primary environment-based composition constructor for {@link CorePluginContext}.
     *
     * @param dependencies immutable plugin dependencies and registries
     * @param environment assembled host environment and scoped services
     */
    public CorePluginContext(final Dependencies dependencies, final PluginContextEnvironment environment) {
        final CorePluginContextComposition.Composition composition =
                CorePluginContextComposition.compose(dependencies, environment);
        this.dependencies = composition.dependencies();
        this.cubismServices = composition.cubismServices();
        this.hostCapabilities = composition.hostCapabilities();
        this.capabilityBundles = composition.capabilityBundles();
        final PluginContextEnvironment env = Objects.requireNonNull(environment, "environment");
        if (env.scriptService() != null) {
            this.scriptService = env.scriptService();
        }
        if (env.mcpConnectionService() != null) {
            this.mcpConnectionService = env.mcpConnectionService();
        }
        installServiceDirectory();
    }

    /**
     * Registers every {@link PluginService} slot this context can resolve into the runtime
     * service directory. Suppliers read the installation fields lazily, so late installs
     * ({@link #installScriptService}, {@link #installMcpConnectionService}) and
     * scope-sealed unloads (mesh/modeling tools) stay visible without re-registration.
     * Probing mirrors the retired {@code availableServices()}: sentinel-backed slots are
     * installed only when they do not resolve to an {@code unavailable()} instance, and
     * {@code PERFORMANCE_STATS} reports installed without forcing its lazy construction.
     */
    private void installServiceDirectory() {
        serviceDirectory.installWhenPresent(
                PluginService.MODELING_TOOLS, () -> disposableScope().isSealed() ? null : modelingTools.current());
        serviceDirectory.installWhenPresent(
                PluginService.MESH_TOOLS, () -> disposableScope().isSealed() ? null : meshTools.current());
        serviceDirectory.install(PluginService.SCRIPTS, () -> scriptService, ScriptService::unavailable);
        serviceDirectory.install(
                PluginService.MCP_CONNECTIONS, () -> mcpConnectionService, McpConnectionService::unavailable);
        serviceDirectory.install(PluginService.CONFIG, dependencies::config, PluginConfigRegistry::unavailable);
        serviceDirectory.installAlways(PluginService.PERFORMANCE_STATS, this::performanceProbeService);
        serviceDirectory.install(
                PluginService.PROJECT_WORK_TIME,
                this::projectWorkTimeService,
                dev.turboism.sdk.cubism.worktime.ProjectWorkTimeService::unavailable);

        for (final PluginCapabilityBundle bundle : capabilityBundles) {
            bundle.registerServices(serviceDirectory);
        }
    }

    @Override
    public PluginDescriptor descriptor() {
        return dependencies.descriptor();
    }

    @Override
    public PluginLogger logger() {
        return dependencies.logger();
    }

    @Override
    public PluginPaths paths() {
        return dependencies.paths();
    }

    @Override
    public PluginLocalization localization() {
        return hostCapabilities.localization() == null
                ? PluginContext.super.localization()
                : hostCapabilities.localization();
    }

    @Override
    public PluginTaskScheduler tasks() {
        return hostCapabilities.taskScheduler() == null
                ? PluginContext.super.tasks()
                : hostCapabilities.taskScheduler();
    }

    @Override
    public AsyncHostReadService hostReads() {
        return hostCapabilities.asyncHostReadService() == null
                ? PluginContext.super.hostReads()
                : hostCapabilities.asyncHostReadService();
    }

    @Override
    public PluginStorage storage() {
        return hostCapabilities.pluginStorage() == null
                ? PluginContext.super.storage()
                : hostCapabilities.pluginStorage();
    }

    @Override
    public ScriptService scripts() {
        return scriptService;
    }

    /** Runtime composition seam; plugins cannot link to this implementation type. */
    public void installScriptService(final ScriptService service) {
        this.scriptService = Objects.requireNonNull(service, "service");
    }

    /** Installs and owns this generation's directory-only mesh-tool service exactly once. */
    public void installMeshTools(final MeshToolRegistry service) {
        meshTools.install(service, disposableScope());
    }

    /** Installs the independently verified, directory-only ordinary tool service once. */
    public void installModelingTools(final dev.turboism.sdk.cubism.modeling.ModelingToolRegistry service) {
        modelingTools.install(service, disposableScope());
    }

    @Override
    public PluginServiceDirectory services() {
        return serviceDirectory;
    }

    /** Runtime composition seam; plugins cannot replace their permission-scoped MCP view. */
    public void installMcpConnectionService(final McpConnectionService service) {
        this.mcpConnectionService = Objects.requireNonNull(service, "service");
    }

    @Override
    public UserFileAccessService userFiles() {
        return hostCapabilities.userFileAccessService() == null
                ? PluginContext.super.userFiles()
                : hostCapabilities.userFileAccessService();
    }

    @Override
    public CubismFacade cubism() {
        return cubismServices.cubismFacade();
    }

    @Override
    public SelectionQueryService selectionQuery() {
        return cubismServices.selectionQueryService();
    }

    @Override
    public ModelHierarchyQueryService modelHierarchyQuery() {
        return cubismServices.modelHierarchyQueryService();
    }

    @Override
    public CubismReadCapabilityService cubismRead() {
        return cubismServices.cubismReadCapabilityService();
    }

    @Override
    public dev.turboism.sdk.cubism.model.ModelObjectService modelObjects() {
        return cubismServices.modelObjectService();
    }

    /** Stops plugin-owned backup work before plugin lifecycle shutdown clears plugin state. */
    public void quiesceBackupOperations() {
        if (cubismServices.backupService()
                instanceof dev.turboism.adapter.cubism.backup.AutoBackupCoordinator coordinator) {
            coordinator.close();
        }
    }

    @Override
    public List<PluginPermission> permissions() {
        return dependencies.permissions();
    }

    @Override
    public EventBus eventBus() {
        return dependencies.eventBus();
    }

    @Override
    public ActionRegistry actions() {
        return dependencies.actions();
    }

    @Override
    public MenuRegistry menus() {
        return dependencies.menus();
    }

    @Override
    public PluginConfigRegistry config() {
        return dependencies.config();
    }

    @Override
    public UiScheduler uiScheduler() {
        return dependencies.uiScheduler();
    }

    /**
     * Lazily binds the shared per-plugin performance probe. Kept private: plugins resolve it
     * through {@link #services()} ({@link PluginService#PERFORMANCE_STATS}).
     */
    private dev.turboism.sdk.performance.PerformanceProbeService performanceProbeService() {
        synchronized (this) {
            if (performanceStatsService == null) {
                performanceStatsService = CorePluginPermissionScopedServices.performanceProbe(dependencies);
            }
            return performanceStatsService;
        }
    }

    /**
     * Lazily binds the runtime-shared work-time tracker to this plugin's permission scope.
     * Plugins resolve it through {@link #services()} ({@link PluginService#PROJECT_WORK_TIME}).
     */
    private dev.turboism.sdk.cubism.worktime.ProjectWorkTimeService projectWorkTimeService() {
        synchronized (this) {
            if (projectWorkTimeService == null) {
                projectWorkTimeService = CorePluginPermissionScopedServices.projectWorkTime(dependencies);
            }
            return projectWorkTimeService;
        }
    }

    @Override
    public DiagnosticReport diagnostics() {
        return dependencies.diagnostics();
    }

    @Override
    public DisposableScope disposableScope() {
        return dependencies.disposableScope();
    }

    /**
     * Production dependencies of a plugin context. The convenience constructor derives
     * {@link #permissions} from {@link PluginDescriptor#permissions()} and constructs the
     * runtime registries from that list. This is the only path intended for plugin loading.
     *
     * <p>The canonical record constructor accepts a caller-supplied {@code permissions} list
     * and pre-built registries; it exists for tests and internal runtime wiring only. Callers
     * outside the runtime package should never use it to load a plugin with permissions that
     * differ from its descriptor.</p>
     */
    public record Dependencies(
            PluginDescriptor descriptor,
            PluginLogger logger,
            PluginPaths paths,
            List<PluginPermission> permissions,
            EventBus eventBus,
            ActionRegistry actions,
            MenuRegistry menus,
            MainToolbarRegistry mainToolbar,
            PaletteToolbarRegistry paletteToolbar,
            PaletteFilterRegistry paletteFilter,
            ContextMenuRegistry contextMenu,
            PluginConfigRegistry config,
            UiScheduler uiScheduler,
            RuntimeScheduler runtimeScheduler,
            DiagnosticReport diagnostics,
            DisposableScope disposableScope,
            HostSnapshotSource hostSnapshotSource,
            M12ReadSnapshotSource m12ReadSnapshotSource,
            UiHostStateSource uiHostStateSource,
            Consumer<CubismFacadeAuditEvent> cubismAuditSink,
            Clock clock,
            RuntimeEventBroker eventBroker) {
        /** Compatibility constructor for internal tests that provide pre-built SDK registries. */
        public Dependencies(
                final PluginDescriptor descriptor,
                final PluginLogger logger,
                final PluginPaths paths,
                final List<PluginPermission> permissions,
                final EventBus eventBus,
                final ActionRegistry actions,
                final MenuRegistry menus,
                final MainToolbarRegistry mainToolbar,
                final PaletteToolbarRegistry paletteToolbar,
                final PaletteFilterRegistry paletteFilter,
                final ContextMenuRegistry contextMenu,
                final PluginConfigRegistry config,
                final UiScheduler uiScheduler,
                final RuntimeScheduler runtimeScheduler,
                final DiagnosticReport diagnostics,
                final DisposableScope disposableScope,
                final HostSnapshotSource hostSnapshotSource,
                final M12ReadSnapshotSource m12ReadSnapshotSource,
                final UiHostStateSource uiHostStateSource,
                final Consumer<CubismFacadeAuditEvent> cubismAuditSink,
                final Clock clock) {
            this(
                    descriptor,
                    logger,
                    paths,
                    permissions,
                    eventBus,
                    actions,
                    menus,
                    mainToolbar,
                    paletteToolbar,
                    paletteFilter,
                    contextMenu,
                    config,
                    uiScheduler,
                    runtimeScheduler,
                    diagnostics,
                    disposableScope,
                    hostSnapshotSource,
                    m12ReadSnapshotSource,
                    uiHostStateSource,
                    cubismAuditSink,
                    clock,
                    new RuntimeEventBroker(runtimeScheduler));
        }

        /**
         * Production convenience constructor: all runtime registries are created from the
         * permissions declared in the plugin descriptor. This guarantees that the runtime
         * permission model cannot diverge from the descriptor.
         */
        public Dependencies(
                PluginDescriptor descriptor,
                PluginLogger logger,
                PluginPaths paths,
                UiScheduler uiScheduler,
                RuntimeScheduler runtimeScheduler,
                DiagnosticReport diagnostics,
                DisposableScope disposableScope,
                HostSnapshotSource hostSnapshotSource,
                Consumer<CubismFacadeAuditEvent> cubismAuditSink,
                Clock clock) {
            this(
                    descriptor,
                    logger,
                    paths,
                    uiScheduler,
                    runtimeScheduler,
                    diagnostics,
                    disposableScope,
                    hostSnapshotSource,
                    M12ReadSnapshotSource.EMPTY,
                    UiHostStateSource.DEFAULT,
                    cubismAuditSink,
                    clock);
        }

        public Dependencies(
                PluginDescriptor descriptor,
                PluginLogger logger,
                PluginPaths paths,
                UiScheduler uiScheduler,
                RuntimeScheduler runtimeScheduler,
                DiagnosticReport diagnostics,
                DisposableScope disposableScope,
                HostSnapshotSource hostSnapshotSource,
                M12ReadSnapshotSource m12ReadSnapshotSource,
                UiHostStateSource uiHostStateSource,
                Consumer<CubismFacadeAuditEvent> cubismAuditSink,
                Clock clock) {
            this(
                    descriptor,
                    logger,
                    paths,
                    uiScheduler,
                    runtimeScheduler,
                    diagnostics,
                    disposableScope,
                    hostSnapshotSource,
                    m12ReadSnapshotSource,
                    uiHostStateSource,
                    cubismAuditSink,
                    clock,
                    RuntimeFailureSink.noop());
        }

        /** Internal composition overload for a preview-session failure collector. */
        public Dependencies(
                PluginDescriptor descriptor,
                PluginLogger logger,
                PluginPaths paths,
                UiScheduler uiScheduler,
                RuntimeScheduler runtimeScheduler,
                DiagnosticReport diagnostics,
                DisposableScope disposableScope,
                HostSnapshotSource hostSnapshotSource,
                M12ReadSnapshotSource m12ReadSnapshotSource,
                UiHostStateSource uiHostStateSource,
                Consumer<CubismFacadeAuditEvent> cubismAuditSink,
                Clock clock,
                RuntimeFailureSink failureSink) {
            this(
                    descriptor,
                    logger,
                    paths,
                    uiScheduler,
                    runtimeScheduler,
                    diagnostics,
                    disposableScope,
                    hostSnapshotSource,
                    m12ReadSnapshotSource,
                    uiHostStateSource,
                    cubismAuditSink,
                    clock,
                    failureSink,
                    new RuntimeEventBroker(runtimeScheduler));
        }

        /** Internal composition overload sharing one event broker across plugin contexts. */
        public Dependencies(
                PluginDescriptor descriptor,
                PluginLogger logger,
                PluginPaths paths,
                UiScheduler uiScheduler,
                RuntimeScheduler runtimeScheduler,
                DiagnosticReport diagnostics,
                DisposableScope disposableScope,
                HostSnapshotSource hostSnapshotSource,
                M12ReadSnapshotSource m12ReadSnapshotSource,
                UiHostStateSource uiHostStateSource,
                Consumer<CubismFacadeAuditEvent> cubismAuditSink,
                Clock clock,
                RuntimeFailureSink failureSink,
                RuntimeEventBroker eventBroker) {
            this(
                    descriptor,
                    logger,
                    paths,
                    uiScheduler,
                    runtimeScheduler,
                    diagnostics,
                    disposableScope,
                    hostSnapshotSource,
                    m12ReadSnapshotSource,
                    uiHostStateSource,
                    cubismAuditSink,
                    clock,
                    failureSink,
                    eventBroker,
                    Objects.requireNonNull(eventBroker, "eventBroker").pluginOwner(descriptor.id()));
        }

        /** Internal composition overload bound to one admitted plugin event generation. */
        public Dependencies(
                PluginDescriptor descriptor,
                PluginLogger logger,
                PluginPaths paths,
                UiScheduler uiScheduler,
                RuntimeScheduler runtimeScheduler,
                DiagnosticReport diagnostics,
                DisposableScope disposableScope,
                HostSnapshotSource hostSnapshotSource,
                M12ReadSnapshotSource m12ReadSnapshotSource,
                UiHostStateSource uiHostStateSource,
                Consumer<CubismFacadeAuditEvent> cubismAuditSink,
                Clock clock,
                RuntimeFailureSink failureSink,
                RuntimeEventBroker eventBroker,
                dev.turboism.core.event.PluginEventOwnerKey eventOwner) {
            this(
                    descriptor,
                    logger,
                    paths,
                    uiScheduler,
                    runtimeScheduler,
                    diagnostics,
                    disposableScope,
                    hostSnapshotSource,
                    m12ReadSnapshotSource,
                    uiHostStateSource,
                    cubismAuditSink,
                    clock,
                    failureSink,
                    eventBroker,
                    eventOwner,
                    null);
        }

        /** Internal composition overload with the exact plugin ClassLoader for callback TCCL. */
        public Dependencies(
                PluginDescriptor descriptor,
                PluginLogger logger,
                PluginPaths paths,
                UiScheduler uiScheduler,
                RuntimeScheduler runtimeScheduler,
                DiagnosticReport diagnostics,
                DisposableScope disposableScope,
                HostSnapshotSource hostSnapshotSource,
                M12ReadSnapshotSource m12ReadSnapshotSource,
                UiHostStateSource uiHostStateSource,
                Consumer<CubismFacadeAuditEvent> cubismAuditSink,
                Clock clock,
                RuntimeFailureSink failureSink,
                RuntimeEventBroker eventBroker,
                dev.turboism.core.event.PluginEventOwnerKey eventOwner,
                ClassLoader pluginClassLoader) {
            this(
                    descriptor,
                    logger,
                    paths,
                    uiScheduler,
                    runtimeScheduler,
                    diagnostics,
                    disposableScope,
                    hostSnapshotSource,
                    m12ReadSnapshotSource,
                    uiHostStateSource,
                    cubismAuditSink,
                    clock,
                    CorePluginServiceFactory.defaultServices(
                            descriptor,
                            CorePluginServiceFactory.permissionsFromDescriptor(descriptor),
                            paths,
                            runtimeScheduler,
                            disposableScope,
                            cubismAuditSink,
                            clock,
                            logger,
                            RuntimeFailureSink.require(failureSink),
                            Objects.requireNonNull(eventBroker, "eventBroker"),
                            Objects.requireNonNull(eventOwner, "eventOwner"),
                            pluginClassLoader),
                    eventBroker);
        }

        private Dependencies(
                PluginDescriptor descriptor,
                PluginLogger logger,
                PluginPaths paths,
                UiScheduler uiScheduler,
                RuntimeScheduler runtimeScheduler,
                DiagnosticReport diagnostics,
                DisposableScope disposableScope,
                HostSnapshotSource hostSnapshotSource,
                M12ReadSnapshotSource m12ReadSnapshotSource,
                UiHostStateSource uiHostStateSource,
                Consumer<CubismFacadeAuditEvent> cubismAuditSink,
                Clock clock,
                CorePluginServiceFactory.DefaultServices services,
                RuntimeEventBroker eventBroker) {
            this(
                    descriptor,
                    logger,
                    paths,
                    CorePluginServiceFactory.permissionsFromDescriptor(descriptor),
                    services.eventBus(),
                    services.actions(),
                    services.menus(),
                    services.mainToolbar(),
                    services.paletteToolbar(),
                    services.paletteFilter(),
                    services.contextMenu(),
                    services.config(),
                    uiScheduler,
                    runtimeScheduler,
                    diagnostics,
                    disposableScope,
                    hostSnapshotSource,
                    m12ReadSnapshotSource,
                    uiHostStateSource,
                    cubismAuditSink,
                    clock,
                    eventBroker);
        }

        /**
         * @param replacement the config registry to use instead of the current one
         * @return a new dependencies record identical to this one except for the config registry;
         *     this record is left unchanged
         * @throws NullPointerException if {@code replacement} is {@code null}
         */
        public Dependencies withConfig(final PluginConfigRegistry replacement) {
            return new Dependencies(
                    descriptor,
                    logger,
                    paths,
                    permissions,
                    eventBus,
                    actions,
                    menus,
                    mainToolbar,
                    paletteToolbar,
                    paletteFilter,
                    contextMenu,
                    Objects.requireNonNull(replacement, "replacement"),
                    uiScheduler,
                    runtimeScheduler,
                    diagnostics,
                    disposableScope,
                    hostSnapshotSource,
                    m12ReadSnapshotSource,
                    uiHostStateSource,
                    cubismAuditSink,
                    clock,
                    eventBroker);
        }

        /**
         * @param replacement the host snapshot source to use instead of the current one
         * @return a new dependencies record identical to this one except for the host snapshot
         *     source; this record is left unchanged
         * @throws NullPointerException if {@code replacement} is {@code null}
         */
        public Dependencies withHostSnapshotSource(final HostSnapshotSource replacement) {
            return new Dependencies(
                    descriptor,
                    logger,
                    paths,
                    permissions,
                    eventBus,
                    actions,
                    menus,
                    mainToolbar,
                    paletteToolbar,
                    paletteFilter,
                    contextMenu,
                    config,
                    uiScheduler,
                    runtimeScheduler,
                    diagnostics,
                    disposableScope,
                    Objects.requireNonNull(replacement, "replacement"),
                    m12ReadSnapshotSource,
                    uiHostStateSource,
                    cubismAuditSink,
                    clock,
                    eventBroker);
        }

        public Dependencies {
            descriptor = Objects.requireNonNull(descriptor, "descriptor");
            logger = Objects.requireNonNull(logger, "logger");
            paths = Objects.requireNonNull(paths, "paths");
            permissions = List.copyOf(Objects.requireNonNull(permissions, "permissions"));
            eventBus = Objects.requireNonNull(eventBus, "eventBus");
            actions = Objects.requireNonNull(actions, "actions");
            menus = Objects.requireNonNull(menus, "menus");
            mainToolbar = Objects.requireNonNull(mainToolbar, "mainToolbar");
            paletteToolbar = Objects.requireNonNull(paletteToolbar, "paletteToolbar");
            paletteFilter = Objects.requireNonNull(paletteFilter, "paletteFilter");
            contextMenu = Objects.requireNonNull(contextMenu, "contextMenu");
            config = Objects.requireNonNull(config, "config");
            uiScheduler = Objects.requireNonNull(uiScheduler, "uiScheduler");
            runtimeScheduler = Objects.requireNonNull(runtimeScheduler, "runtimeScheduler");
            diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
            disposableScope = Objects.requireNonNull(disposableScope, "disposableScope");
            hostSnapshotSource = Objects.requireNonNull(hostSnapshotSource, "hostSnapshotSource");
            m12ReadSnapshotSource = Objects.requireNonNull(m12ReadSnapshotSource, "m12ReadSnapshotSource");
            uiHostStateSource = Objects.requireNonNull(uiHostStateSource, "uiHostStateSource");
            cubismAuditSink = Objects.requireNonNull(cubismAuditSink, "cubismAuditSink");
            clock = Objects.requireNonNull(clock, "clock");
            eventBroker = Objects.requireNonNull(eventBroker, "eventBroker");
        }
    }
}
