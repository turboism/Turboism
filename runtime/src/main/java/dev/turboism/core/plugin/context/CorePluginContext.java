package dev.turboism.core.plugin.context;

import dev.turboism.adapter.RuntimeHostAdapters;
import dev.turboism.adapter.cubism.HostSnapshotSource;
import dev.turboism.adapter.cubism.mesh.AuthorizedMeshEditUiService;
import dev.turboism.adapter.cubism.mesh.AuthorizedMeshMirrorAxisService;
import dev.turboism.adapter.cubism.mesh.RuntimeMeshEditUiService;
import dev.turboism.adapter.cubism.mesh.RuntimeMeshMirrorAxisService;
import dev.turboism.adapter.cubism.service.read.M12ReadSnapshotSource;
import dev.turboism.adapter.host.HostSessionSnapshotSource;
import dev.turboism.adapter.host.RuntimeHostAdapterAccess;
import dev.turboism.config.RuntimePluginConfigRegistry;
import dev.turboism.core.action.RuntimeActionRegistry;
import dev.turboism.core.diagnostics.StartupReport;
import dev.turboism.core.event.PluginEventBus;
import dev.turboism.core.event.RuntimeEventBroker;
import dev.turboism.core.menu.RuntimeMenuRegistry;
import dev.turboism.core.runtime.RuntimeScheduler;
import dev.turboism.diagnostics.CubismFacadeAuditEvent;
import dev.turboism.failure.RuntimeFailureSink;
import dev.turboism.permissions.CubismPermissionGate;
import dev.turboism.permissions.PermissionChecker;
import dev.turboism.recentfile.RuntimeRecentFileService;
import dev.turboism.recentpreview.RuntimeRecentPreviewContributionService;
import dev.turboism.screenshot.RuntimeScreenshotCaptureService;
import dev.turboism.sdk.action.ActionRegistry;
import dev.turboism.sdk.appearance.AppearanceService;
import dev.turboism.sdk.config.PluginConfigRegistry;
import dev.turboism.sdk.cubism.CubismFacade;
import dev.turboism.sdk.cubism.export.ExportSettingsContributionService;
import dev.turboism.sdk.cubism.mesh.MeshEditUiService;
import dev.turboism.sdk.cubism.mesh.MeshMirrorAxisService;
import dev.turboism.sdk.cubism.recentfile.RecentFileService;
import dev.turboism.sdk.cubism.recentpreview.RecentPreviewContributionService;
import dev.turboism.sdk.cubism.screenshot.ScreenshotCaptureService;
import dev.turboism.sdk.cubism.service.query.ModelHierarchyQueryService;
import dev.turboism.sdk.cubism.service.query.ParameterQueryService;
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
import dev.turboism.sdk.script.ScriptService;
import dev.turboism.sdk.storage.PluginStorage;
import dev.turboism.sdk.task.PluginTaskScheduler;
import dev.turboism.sdk.ui.UiHostCapabilityService;
import dev.turboism.sdk.ui.UiScheduler;
import dev.turboism.sdk.ui.UserFileAccessService;
import dev.turboism.sdk.ui.context.ContextMenuRegistry;
import dev.turboism.sdk.ui.filter.PaletteFilterRegistry;
import dev.turboism.sdk.ui.resource.UiResourceService;
import dev.turboism.sdk.ui.table.SceneTableService;
import dev.turboism.sdk.ui.toolbar.MainToolbarRegistry;
import dev.turboism.sdk.ui.toolbar.PaletteToolbarRegistry;
import dev.turboism.ui.RuntimeUiHostCapabilityService;
import dev.turboism.ui.UiHostStateSource;
import dev.turboism.ui.appearance.RuntimeAppearanceService;
import dev.turboism.ui.context.RuntimeContextMenuRegistry;
import dev.turboism.ui.dialog.RuntimeHostDialogAutomationService;
import dev.turboism.ui.filter.RuntimePaletteFilterRegistry;
import dev.turboism.ui.toolbar.RuntimeMainToolbarRegistry;
import dev.turboism.ui.toolbar.RuntimePaletteToolbarRegistry;
import java.time.Clock;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
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
    private final MainToolbarRegistry mainToolbarRegistry;
    private final PaletteToolbarRegistry paletteToolbarRegistry;
    private final PaletteFilterRegistry paletteFilterRegistry;
    private final ContextMenuRegistry contextMenuRegistry;
    private final PluginConfigRegistry pluginConfigRegistry;
    private final UiHostCapabilityService uiHostCapabilityService;
    private final UiResourceService uiResourceService;
    private final dev.turboism.sdk.ui.dialog.HostDialogAutomationService hostDialogAutomationService;
    private final AppearanceService appearanceService;
    private final PluginLocalization localization;
    private final PluginTaskScheduler taskScheduler;
    private final PluginStorage pluginStorage;
    private volatile ScriptService scriptService = ScriptService.unavailable();
    private volatile McpConnectionService mcpConnectionService = McpConnectionService.unavailable();
    private volatile ExportSettingsContributionService exportSettingsContributionService =
            ExportSettingsContributionService.unavailable();
    private final UserFileAccessService userFileAccessService;
    private final AsyncHostReadService asyncHostReadService;
    private final MeshMirrorAxisService meshMirrorAxisService;
    private final MeshEditUiService meshEditUiService;
    private final dev.turboism.sdk.cubism.mesh.MeshEditService meshEditService;
    private final dev.turboism.sdk.cubism.mesh.MeshEditParticipation meshEditParticipationService;
    private final dev.turboism.sdk.cubism.mesh.MeshMirrorCounterparts meshMirrorCounterpartsService;
    private final dev.turboism.sdk.cubism.mesh.MeshMirrorToolEligibility meshMirrorToolEligibilityService;
    private final dev.turboism.sdk.cubism.mesh.MeshMirrorMoveParticipation meshMirrorMoveParticipationService;
    private final dev.turboism.sdk.cubism.warp.WarpAltMirrorParticipation warpAltMirrorParticipationService;
    private final dev.turboism.sdk.ui.viewcontext.ViewContextMenuRegistry viewContextMenuRegistry;
    private final dev.turboism.sdk.ui.workspace.WorkspaceService workspaceService;
    private final dev.turboism.sdk.ui.workspace.layout.WorkspaceLayoutService workspaceLayoutService;
    private final dev.turboism.adapter.cubism.backup.AutoBackupCoordinator backupCoordinator;
    private final CubismEditorApiAvailabilityInterceptor editorApiAvailability;

    private final SceneTableService sceneTableService;
    private final dev.turboism.sdk.runtime.CubismLogService cubismLogService;
    private final RecentFileService recentFileService;
    private final ScreenshotCaptureService screenshotCaptureService;
    private final RecentPreviewContributionService recentPreviewContributionService;
    private dev.turboism.sdk.runtime.RuntimeSettingsService runtimeSettings;

    private volatile dev.turboism.sdk.performance.PerformanceProbeService performanceStatsService;
    private dev.turboism.sdk.cubism.filechooser.FileChooserHistoryService fileChooserHistory;

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
        Objects.requireNonNull(dependencies, "dependencies");
        final PluginContextEnvironment env = Objects.requireNonNull(environment, "environment");
        final RuntimeHostAdapterAccess hostAccess = env.hostAccess();
        this.dependencies = hostAccess == null
                ? dependencies
                : dependencies.withHostSnapshotSource(
                        dependencies.hostSnapshotSource() instanceof HostSessionSnapshotSource
                                ? dependencies.hostSnapshotSource()
                                : HostSessionSnapshotSource.forSession(
                                        hostAccess.adapters().projectWorkspace(), hostAccess::currentHostSelection));
        final RuntimeHostAdapters adapters = env.hostAdapters();
        this.uiResourceService = adapters.uiResources();
        final CubismServicesFactory servicesFactory = env.servicesFactory();
        this.editorApiAvailability = new CubismEditorApiAvailabilityInterceptor(
                servicesFactory instanceof DefaultCubismServicesFactory defaultFactory
                        ? defaultFactory.cubismEditorVersion()
                        : java.util.Optional::empty,
                servicesFactory instanceof DefaultCubismServicesFactory defaultFactory
                        ? defaultFactory.admittedCubismCapabilities()
                        : java.util.Set::of,
                servicesFactory instanceof DefaultCubismServicesFactory defaultFactory
                        ? defaultFactory.admittedCubismGeneration()
                        : java.util.Optional::empty);
        this.cubismServices = servicesFactory.create(
                this.dependencies,
                env.taskScheduler() instanceof dev.turboism.task.RuntimePluginTaskScheduler runtimeTasks
                        ? runtimeTasks
                        : null);
        this.backupCoordinator = this.cubismServices.backupService()
                        instanceof dev.turboism.adapter.cubism.backup.AutoBackupCoordinator coordinator
                ? coordinator
                : null;
        this.mainToolbarRegistry = this.dependencies.mainToolbar();
        this.paletteToolbarRegistry = this.dependencies.paletteToolbar();
        this.paletteFilterRegistry = this.dependencies.paletteFilter();
        this.contextMenuRegistry = this.dependencies.contextMenu();
        this.pluginConfigRegistry = this.dependencies.config();
        this.localization = env.localization();
        bindContributionLocalization(
                this.mainToolbarRegistry, this.paletteToolbarRegistry, this.paletteFilterRegistry, this.localization);
        this.taskScheduler = env.taskScheduler();
        this.pluginStorage = env.pluginStorage();
        this.userFileAccessService = env.userFileAccessService();
        this.asyncHostReadService = env.asyncHostReadService();
        final RuntimeMeshMirrorAxisService sharedMeshMirrorAxis =
                hostAccess == null ? new RuntimeMeshMirrorAxisService() : hostAccess.meshMirrorAxisService();
        final RuntimeMeshEditUiService sharedMeshEditUi =
                hostAccess == null ? new RuntimeMeshEditUiService() : hostAccess.meshEditUiService();
        final PermissionChecker meshPermissionChecker = PermissionChecker.from(new CubismPermissionGate(
                this.dependencies.descriptor().id(),
                this.dependencies.permissions(),
                this.dependencies.cubismAuditSink(),
                this.dependencies.clock()));
        this.meshMirrorAxisService = new AuthorizedMeshMirrorAxisService(sharedMeshMirrorAxis, meshPermissionChecker);
        this.meshEditUiService = new AuthorizedMeshEditUiService(
                sharedMeshEditUi, meshPermissionChecker, this.dependencies.disposableScope());
        this.meshEditService = new dev.turboism.adapter.cubism.mesh.AuthorizedMeshEditService(
                new dev.turboism.adapter.cubism.mesh.RuntimeMeshEditService(), meshPermissionChecker);
        this.meshEditParticipationService = new dev.turboism.adapter.cubism.mesh.AuthorizedMeshEditParticipation(
                dev.turboism.adapter.cubism.mesh.NativeMeshMirrorBridge.participation(),
                meshPermissionChecker,
                this.dependencies.disposableScope());
        this.meshMirrorCounterpartsService = new dev.turboism.adapter.cubism.mesh.AuthorizedMeshMirrorCounterparts(
                dev.turboism.adapter.cubism.mesh.NativeMeshMirrorBridge.counterparts(),
                meshPermissionChecker,
                this.dependencies.disposableScope());
        this.meshMirrorToolEligibilityService =
                new dev.turboism.adapter.cubism.mesh.AuthorizedMeshMirrorToolEligibility(
                        dev.turboism.adapter.cubism.mesh.NativeMeshMirrorBridge.toolEligibility(),
                        meshPermissionChecker,
                        this.dependencies.disposableScope());
        this.meshMirrorMoveParticipationService =
                new dev.turboism.adapter.cubism.mesh.AuthorizedMeshMirrorMoveParticipation(
                        dev.turboism.adapter.cubism.mesh.NativeMeshMirrorBridge.moveParticipation(),
                        meshPermissionChecker,
                        this.dependencies.disposableScope());
        this.warpAltMirrorParticipationService =
                new dev.turboism.adapter.cubism.warpalt.AuthorizedWarpAltMirrorParticipation(
                        dev.turboism.adapter.cubism.warpalt.NativeWarpAltMirrorBridge.moveParticipation(),
                        meshPermissionChecker,
                        this.dependencies.disposableScope());
        this.viewContextMenuRegistry = new dev.turboism.adapter.cubism.warpalt.AuthorizedViewContextMenuRegistry(
                dev.turboism.adapter.cubism.warpalt.RuntimeViewContextMenuRegistry.getInstance(),
                meshPermissionChecker);
        this.sceneTableService = hostAccess == null ? SceneTableService.unavailable() : hostAccess.sceneTable();
        this.cubismLogService =
                hostAccess == null ? dev.turboism.sdk.runtime.CubismLogService.unavailable() : hostAccess.cubismLog();
        if (hostAccess == null) {
            this.appearanceService = AppearanceService.unavailable();
        } else {
            final String pluginId = this.dependencies.descriptor().id();
            final long pluginGeneration = 0L;
            final RuntimeAppearanceService appearance = new RuntimeAppearanceService(
                    pluginId,
                    pluginGeneration,
                    PermissionChecker.from(new CubismPermissionGate(
                            pluginId,
                            this.dependencies.permissions(),
                            this.dependencies.cubismAuditSink(),
                            this.dependencies.clock())),
                    hostAccess.appearanceCoordinator());
            this.appearanceService = appearance;
            this.dependencies
                    .disposableScope()
                    .register(() -> hostAccess.appearanceCoordinator().restore(pluginId, pluginGeneration));
        }
        final PermissionChecker uiPermissionChecker = PermissionChecker.from(new CubismPermissionGate(
                this.dependencies.descriptor().id(),
                this.dependencies.permissions(),
                this.dependencies.cubismAuditSink(),
                this.dependencies.clock()));
        this.recentFileService = hostAccess == null
                ? RecentFileService.unavailable()
                : new RuntimeRecentFileService(adapters.recentFiles(), uiPermissionChecker);
        this.screenshotCaptureService = hostAccess == null
                ? ScreenshotCaptureService.unavailable()
                : new RuntimeScreenshotCaptureService(adapters.screenshots(), uiPermissionChecker);
        this.recentPreviewContributionService = hostAccess == null
                ? RecentPreviewContributionService.unavailable()
                : new RuntimeRecentPreviewContributionService(adapters.recentPreviews(), uiPermissionChecker);
        this.hostDialogAutomationService = new RuntimeHostDialogAutomationService(uiPermissionChecker);
        final dev.turboism.sdk.ui.workspace.WorkspaceService workspace = hostAccess == null
                ? dev.turboism.sdk.ui.workspace.WorkspaceService.unavailable()
                : new dev.turboism.ui.workspace.RuntimeWorkspaceService(
                        uiPermissionChecker, hostAccess.workspaceCoordinator());
        this.workspaceService =
                editorApiAvailability.wrapForTesting(workspace, dev.turboism.sdk.ui.workspace.WorkspaceService.class);
        if (workspace instanceof dev.turboism.ui.workspace.RuntimeWorkspaceService runtimeWorkspace) {
            this.dependencies.disposableScope().register(runtimeWorkspace);
        }
        final dev.turboism.ui.workspace.layout.WorkspaceLayoutCoordinator layoutCoordinator =
                hostAccess == null ? null : hostAccess.workspaceLayoutCoordinator();
        this.workspaceLayoutService = hostAccess == null || layoutCoordinator == null
                ? dev.turboism.sdk.ui.workspace.layout.WorkspaceLayoutService.unavailable()
                : new dev.turboism.ui.workspace.layout.RuntimeWorkspaceLayoutService(
                        uiPermissionChecker, layoutCoordinator);
        if (layoutCoordinator != null) {
            this.dependencies
                    .disposableScope()
                    .register((dev.turboism.ui.workspace.layout.RuntimeWorkspaceLayoutService)
                            this.workspaceLayoutService);
        }
        this.uiHostCapabilityService = hostAccess == null
                ? new RuntimeUiHostCapabilityService(
                        uiPermissionChecker,
                        this.dependencies.descriptor().id(),
                        this.dependencies.uiHostStateSource(),
                        this.dependencies.disposableScope(),
                        adapters.statusToolbar(),
                        adapters.uiSurface(),
                        this.localization,
                        dev.turboism.ui.settings.ProcessSettingsContributions.forHost(hostAccess),
                        this.dependencies.logger())
                : new RuntimeUiHostCapabilityService(
                        uiPermissionChecker,
                        this.dependencies.descriptor().id(),
                        this.dependencies.uiHostStateSource(),
                        this.dependencies.disposableScope(),
                        adapters.statusToolbar(),
                        adapters.uiSurface(),
                        this.localization,
                        dev.turboism.ui.settings.ProcessSettingsContributions.forHost(hostAccess),
                        hostAccess.editorUiContributions(),
                        hostAccess.embeddedPanelActivation(),
                        (contributionId, callback) -> this.dependencies
                                .runtimeScheduler()
                                .dispatch(
                                        new dev.turboism.core.runtime.PluginTask(
                                                "ui.overlay-button.click",
                                                this.dependencies.descriptor().id(),
                                                contributionId,
                                                "none"),
                                        callback),
                        this.dependencies.logger());
        if (hostAccess != null) {
            UiContributionContextBinder.bind(
                    this.dependencies.menus(),
                    this.mainToolbarRegistry,
                    this.paletteToolbarRegistry,
                    this.paletteFilterRegistry,
                    this.contextMenuRegistry,
                    hostAccess.editorUiContributions());
            this.dependencies
                    .disposableScope()
                    .register(hostAccess
                            .editorUiActionRouter()
                            .register(this.dependencies.descriptor().id(), this.dependencies.actions()));
        }
        this.runtimeSettings = env.runtimeSettings();
        this.fileChooserHistory = env.fileChooserHistory() == null
                ? null
                : editorApiAvailability.wrapForTesting(
                        env.fileChooserHistory(), dev.turboism.sdk.cubism.filechooser.FileChooserHistoryService.class);
        if (env.exportSettings() != null) {
            this.exportSettingsContributionService =
                    editorApiAvailability.wrapForTesting(env.exportSettings(), ExportSettingsContributionService.class);
        }
        if (env.scriptService() != null) {
            this.scriptService = env.scriptService();
        }
        if (env.mcpConnectionService() != null) {
            this.mcpConnectionService = env.mcpConnectionService();
        }
    }

    private static void bindContributionLocalization(
            final MainToolbarRegistry mainToolbar,
            final PaletteToolbarRegistry paletteToolbar,
            final PaletteFilterRegistry paletteFilter,
            final PluginLocalization localization) {
        if (mainToolbar instanceof RuntimeMainToolbarRegistry runtimeMainToolbar) {
            if (localization == null) {
                runtimeMainToolbar.lockWithoutLocalization();
            } else {
                runtimeMainToolbar.bindLocalization(localization);
            }
        }
        if (paletteToolbar instanceof RuntimePaletteToolbarRegistry runtimePaletteToolbar) {
            if (localization == null) {
                runtimePaletteToolbar.lockWithoutLocalization();
            } else {
                runtimePaletteToolbar.bindLocalization(localization);
            }
        }
        if (paletteFilter instanceof RuntimePaletteFilterRegistry runtimePaletteFilter) {
            if (localization == null) {
                runtimePaletteFilter.lockWithoutLocalization();
            } else {
                runtimePaletteFilter.bindLocalization(localization);
            }
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
        return localization == null ? PluginContext.super.localization() : localization;
    }

    @Override
    public PluginTaskScheduler tasks() {
        return taskScheduler == null ? PluginContext.super.tasks() : taskScheduler;
    }

    @Override
    public AsyncHostReadService hostReads() {
        return asyncHostReadService == null ? PluginContext.super.hostReads() : asyncHostReadService;
    }

    @Override
    public PluginStorage storage() {
        return pluginStorage == null ? PluginContext.super.storage() : pluginStorage;
    }

    @Override
    public ScriptService scripts() {
        return scriptService;
    }

    /** Runtime composition seam; plugins cannot link to this implementation type. */
    public void installScriptService(final ScriptService service) {
        this.scriptService = Objects.requireNonNull(service, "service");
    }

    /** Runtime composition seam; plugins cannot replace their permission-scoped MCP view. */
    public void installMcpConnectionService(final McpConnectionService service) {
        this.mcpConnectionService = Objects.requireNonNull(service, "service");
    }

    @Override
    public McpConnectionService mcpConnections() {
        return mcpConnectionService;
    }

    @Override
    public UserFileAccessService userFiles() {
        return userFileAccessService == null ? PluginContext.super.userFiles() : userFileAccessService;
    }

    @Override
    public ExportSettingsContributionService exportSettings() {
        return exportSettingsContributionService;
    }

    @Override
    public CubismFacade cubism() {
        return cubismServices.cubismFacade();
    }

    @Override
    public ParameterQueryService parameterQuery() {
        return cubismServices.parameterQueryService();
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
    public dev.turboism.sdk.cubism.service.clipmask.CubismClipMaskService cubismClipMasks() {
        return cubismServices.cubismClipMaskService();
    }

    @Override
    public dev.turboism.sdk.cubism.model.ModelObjectService modelObjects() {
        return cubismServices.modelObjectService();
    }

    @Override
    public dev.turboism.sdk.cubism.physics.PhysicsEditorService physicsEditor() {
        return cubismServices.physicsEditorService();
    }

    @Override
    public MeshMirrorAxisService meshMirrorAxis() {
        return meshMirrorAxisService;
    }

    @Override
    public MeshEditUiService meshEditUi() {
        return meshEditUiService;
    }

    @Override
    public dev.turboism.sdk.cubism.mesh.MeshEditService meshEdit() {
        return meshEditService;
    }

    @Override
    public dev.turboism.sdk.cubism.mesh.MeshEditParticipation meshEditParticipation() {
        return meshEditParticipationService;
    }

    @Override
    public dev.turboism.sdk.cubism.mesh.MeshMirrorCounterparts meshMirrorCounterparts() {
        return meshMirrorCounterpartsService;
    }

    @Override
    public dev.turboism.sdk.cubism.mesh.MeshMirrorToolEligibility meshMirrorToolEligibility() {
        return meshMirrorToolEligibilityService;
    }

    @Override
    public dev.turboism.sdk.cubism.mesh.MeshMirrorMoveParticipation meshMirrorMoveParticipation() {
        return meshMirrorMoveParticipationService;
    }

    @Override
    public dev.turboism.sdk.cubism.warp.WarpAltMirrorParticipation warpAltMirrorParticipation() {
        return warpAltMirrorParticipationService;
    }

    @Override
    public dev.turboism.sdk.ui.viewcontext.ViewContextMenuRegistry viewContextMenu() {
        return viewContextMenuRegistry;
    }

    @Override
    public dev.turboism.sdk.cubism.command.EditorCommandService editorCommands() {
        return cubismServices.editorCommandService();
    }

    @Override
    public dev.turboism.sdk.cubism.backup.EditorAutoBackupService backup() {
        return cubismServices.backupService();
    }

    /** Stops plugin-owned backup work before plugin lifecycle shutdown clears plugin state. */
    public void quiesceBackupOperations() {
        if (backupCoordinator != null) {
            backupCoordinator.close();
        }
    }

    @Override
    public List<PluginPermission> permissions() {
        return dependencies.permissions();
    }

    /**
     * Reports which optional services this context installed, derived from the installation
     * fields rather than by calling the getters: {@code performanceStats()} lazily constructs
     * its service, and several slots hold {@code unavailable()} sentinels behind the
     * version-gating proxy. Every field that can carry an {@code unavailable()} sentinel is
     * probed through {@link #installed}; only members whose accessors unconditionally expose a
     * real runtime object (the {@code Authorized*} delegates, {@code uiHost}, {@code hostDialogs}
     * and {@code performanceStats}) are reported without a probe. Recomputed per call so late
     * {@code installScriptService} / {@code installMcpConnectionService} installs are visible.
     */
    @Deprecated
    @Override
    public Set<PluginService> availableServices() {
        final EnumSet<PluginService> available = EnumSet.noneOf(PluginService.class);
        if (installed(localization, PluginLocalization.unavailable())) {
            available.add(PluginService.LOCALIZATION);
        }
        if (installed(taskScheduler, PluginTaskScheduler.unavailable())) {
            available.add(PluginService.TASKS);
        }
        if (installed(asyncHostReadService, AsyncHostReadService.unavailable())) {
            available.add(PluginService.HOST_READS);
        }
        if (installed(pluginStorage, PluginStorage.unavailable())) {
            available.add(PluginService.STORAGE);
        }
        if (installed(scriptService, ScriptService.unavailable())) {
            available.add(PluginService.SCRIPTS);
        }
        if (installed(userFileAccessService, UserFileAccessService.unavailable())) {
            available.add(PluginService.USER_FILES);
        }
        if (installed(cubismServices.parameterQueryService(), null)) {
            available.add(PluginService.PARAMETER_QUERY);
        }
        if (installed(cubismServices.selectionQueryService(), null)) {
            available.add(PluginService.SELECTION_QUERY);
        }
        if (installed(cubismServices.modelHierarchyQueryService(), null)) {
            available.add(PluginService.MODEL_HIERARCHY_QUERY);
        }
        if (installed(cubismServices.cubismReadCapabilityService(), null)) {
            available.add(PluginService.CUBISM_READ);
        }
        if (installed(
                cubismServices.modelObjectService(), dev.turboism.sdk.cubism.model.ModelObjectService.unavailable())) {
            available.add(PluginService.MODEL_OBJECTS);
        }
        if (installed(cubismServices.cubismClipMaskService(), null)) {
            available.add(PluginService.CUBISM_CLIP_MASKS);
        }
        if (installed(recentFileService, RecentFileService.unavailable())) {
            available.add(PluginService.RECENT_FILES);
        }
        if (installed(screenshotCaptureService, ScreenshotCaptureService.unavailable())) {
            available.add(PluginService.SCREENSHOTS);
        }
        if (installed(recentPreviewContributionService, RecentPreviewContributionService.unavailable())) {
            available.add(PluginService.RECENT_PREVIEWS);
        }
        if (installed(
                cubismServices.physicsEditorService(),
                dev.turboism.sdk.cubism.physics.PhysicsEditorService.unavailable())) {
            available.add(PluginService.PHYSICS_EDITOR);
        }
        if (installed(
                fileChooserHistory, dev.turboism.sdk.cubism.filechooser.FileChooserHistoryService.unavailable())) {
            available.add(PluginService.FILE_CHOOSER_HISTORY);
        }
        available.add(PluginService.MESH_MIRROR_AXIS);
        available.add(PluginService.MESH_EDIT);
        available.add(PluginService.MESH_EDIT_PARTICIPATION);
        available.add(PluginService.MESH_MIRROR_COUNTERPARTS);
        available.add(PluginService.MESH_MIRROR_TOOL_ELIGIBILITY);
        available.add(PluginService.MESH_MIRROR_MOVE_PARTICIPATION);
        available.add(PluginService.WARP_ALT_MIRROR_PARTICIPATION);
        available.add(PluginService.VIEW_CONTEXT_MENU);
        available.add(PluginService.MESH_EDIT_UI);
        if (installed(
                cubismServices.editorCommandService(),
                dev.turboism.sdk.cubism.command.EditorCommandService.unavailable())) {
            available.add(PluginService.EDITOR_COMMANDS);
        }
        if (installed(
                cubismServices.backupService(), dev.turboism.sdk.cubism.backup.EditorAutoBackupService.unavailable())) {
            available.add(PluginService.BACKUP);
        }
        if (installed(mainToolbarRegistry, MainToolbarRegistry.unavailable())) {
            available.add(PluginService.MAIN_TOOLBAR);
        }
        if (installed(paletteToolbarRegistry, PaletteToolbarRegistry.unavailable())) {
            available.add(PluginService.PALETTE_TOOLBAR);
        }
        if (installed(paletteFilterRegistry, PaletteFilterRegistry.unavailable())) {
            available.add(PluginService.PALETTE_FILTER);
        }
        if (installed(sceneTableService, SceneTableService.unavailable())) {
            available.add(PluginService.SCENE_TABLE);
        }
        available.add(PluginService.UI_HOST);
        if (installed(uiResourceService, UiResourceService.unavailable())) {
            available.add(PluginService.UI_RESOURCES);
        }
        available.add(PluginService.HOST_DIALOGS);
        if (installed(appearanceService, AppearanceService.unavailable())) {
            available.add(PluginService.APPEARANCE);
        }
        if (installed(workspaceService, dev.turboism.sdk.ui.workspace.WorkspaceService.unavailable())) {
            available.add(PluginService.WORKSPACE);
        }
        if (installed(
                workspaceLayoutService, dev.turboism.sdk.ui.workspace.layout.WorkspaceLayoutService.unavailable())) {
            available.add(PluginService.WORKSPACE_LAYOUT);
        }
        if (installed(contextMenuRegistry, ContextMenuRegistry.unavailable())) {
            available.add(PluginService.CONTEXT_MENU);
        }
        if (installed(pluginConfigRegistry, PluginConfigRegistry.unavailable())) {
            available.add(PluginService.CONFIG);
        }
        if (installed(cubismLogService, dev.turboism.sdk.runtime.CubismLogService.unavailable())) {
            available.add(PluginService.CUBISM_LOG);
        }
        if (installed(runtimeSettings, dev.turboism.sdk.runtime.RuntimeSettingsService.unavailable())) {
            available.add(PluginService.RUNTIME_SETTINGS);
        }
        if (installed(mcpConnectionService, McpConnectionService.unavailable())) {
            available.add(PluginService.MCP_CONNECTIONS);
        }
        if (installed(exportSettingsContributionService, ExportSettingsContributionService.unavailable())) {
            available.add(PluginService.EXPORT_SETTINGS);
        }
        available.add(PluginService.PERFORMANCE_STATS);
        return Collections.unmodifiableSet(available);
    }

    /**
     * Whether a service slot resolves to a usable instance: non-null and not an instance of the
     * {@code unavailable()} sentinel's implementation class, looking through the version-gating
     * proxy. Sentinel implementations are compared by class because several
     * {@code unavailable()} factories return a fresh anonymous instance per call rather than a
     * singleton.
     */
    private static boolean installed(final Object service, final Object unavailableSentinel) {
        final Object resolved = CubismEditorApiAvailabilityInterceptor.unwrap(service);
        return resolved != null
                && (unavailableSentinel == null || resolved.getClass() != unavailableSentinel.getClass());
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
    public MainToolbarRegistry mainToolbar() {
        return mainToolbarRegistry;
    }

    @Override
    public PaletteToolbarRegistry paletteToolbar() {
        return paletteToolbarRegistry;
    }

    @Override
    public PaletteFilterRegistry paletteFilter() {
        return paletteFilterRegistry;
    }

    @Override
    public RecentFileService recentFiles() {
        return recentFileService;
    }

    @Override
    public ScreenshotCaptureService screenshots() {
        return screenshotCaptureService;
    }

    @Override
    public RecentPreviewContributionService recentPreviews() {
        return recentPreviewContributionService;
    }

    @Override
    public SceneTableService sceneTable() {
        return sceneTableService;
    }

    @Override
    public UiHostCapabilityService uiHost() {
        return uiHostCapabilityService;
    }

    @Override
    public UiResourceService uiResources() {
        return uiResourceService;
    }

    @Override
    public dev.turboism.sdk.ui.dialog.HostDialogAutomationService hostDialogs() {
        return hostDialogAutomationService;
    }

    @Override
    public AppearanceService appearance() {
        return appearanceService;
    }

    @Override
    public dev.turboism.sdk.ui.workspace.WorkspaceService workspace() {
        return workspaceService;
    }

    @Override
    public dev.turboism.sdk.ui.workspace.layout.WorkspaceLayoutService workspaceLayout() {
        return workspaceLayoutService;
    }

    @Override
    public ContextMenuRegistry contextMenu() {
        return contextMenuRegistry;
    }

    @Override
    public PluginConfigRegistry config() {
        return pluginConfigRegistry;
    }

    @Override
    public dev.turboism.sdk.runtime.CubismLogService cubismLog() {
        return cubismLogService;
    }

    @Override
    public dev.turboism.sdk.runtime.RuntimeSettingsService runtimeSettings() {
        return runtimeSettings == null ? PluginContext.super.runtimeSettings() : runtimeSettings;
    }

    @Override
    public dev.turboism.sdk.cubism.filechooser.FileChooserHistoryService fileChooserHistory() {
        return fileChooserHistory == null ? PluginContext.super.fileChooserHistory() : fileChooserHistory;
    }

    @Override
    public UiScheduler uiScheduler() {
        return dependencies.uiScheduler();
    }

    @Override
    public dev.turboism.sdk.performance.PerformanceProbeService performanceStats() {
        synchronized (this) {
            if (performanceStatsService == null) {
                final dev.turboism.sdk.performance.PerformanceProbeService shared = dependencies
                        .eventBroker()
                        .observationBaseline(dev.turboism.sdk.performance.PerformanceProbeService.class)
                        .get();
                final dev.turboism.permissions.PermissionChecker permissionChecker =
                        dev.turboism.permissions.PermissionChecker.from(dependencies.permissions());
                final dev.turboism.performance.RuntimePerformanceProbeService owned = shared == null
                        ? new dev.turboism.performance.RuntimePerformanceProbeService(
                                dependencies.descriptor().id(), permissionChecker, dependencies.clock())
                        : null;
                performanceStatsService = dev.turboism.performance.PermissionCheckedPerformanceProbeService.bind(
                        shared == null ? owned : shared, permissionChecker, dependencies.disposableScope(), owned);
            }
            return performanceStatsService;
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
                    defaultServices(
                            descriptor,
                            permissionsFromDescriptor(descriptor),
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

        private static List<PluginPermission> permissionsFromDescriptor(PluginDescriptor descriptor) {
            return descriptor.permissions().stream()
                    .<PluginPermission>map(ref -> new DescriptorPermission(
                            ref.id(), ref.scope(), ref.reason().orElse("")))
                    .toList();
        }

        private record DescriptorPermission(String id, String scope, String reason) implements PluginPermission {}

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
                DefaultServices services,
                RuntimeEventBroker eventBroker) {
            this(
                    descriptor,
                    logger,
                    paths,
                    permissionsFromDescriptor(descriptor),
                    services.eventBus,
                    services.actions,
                    services.menus,
                    services.mainToolbar,
                    services.paletteToolbar,
                    services.paletteFilter,
                    services.contextMenu,
                    services.config,
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

        private static DefaultServices defaultServices(
                PluginDescriptor descriptor,
                List<PluginPermission> permissions,
                PluginPaths paths,
                RuntimeScheduler runtimeScheduler,
                DisposableScope disposableScope,
                Consumer<CubismFacadeAuditEvent> cubismAuditSink,
                Clock clock,
                PluginLogger logger,
                RuntimeFailureSink failureSink,
                RuntimeEventBroker eventBroker,
                dev.turboism.core.event.PluginEventOwnerKey eventOwner,
                ClassLoader pluginClassLoader) {
            PermissionChecker checker = PermissionChecker.from(
                    new CubismPermissionGate(descriptor.id(), permissions, cubismAuditSink, clock));
            Consumer<StartupReport.DiagnosticProblem> diagnosticSink =
                    problem -> logger.warn(problem.code() + ": " + problem.message() + " @ " + problem.path());
            return new DefaultServices(
                    new PluginEventBus(eventBroker, eventOwner, checker, pluginClassLoader),
                    new RuntimeActionRegistry(runtimeScheduler, diagnosticSink, descriptor.id(), checker, eventBroker),
                    new RuntimeMenuRegistry(runtimeScheduler, descriptor.id(), checker),
                    new RuntimeMainToolbarRegistry(checker, runtimeScheduler, descriptor.id()),
                    new RuntimePaletteToolbarRegistry(checker, runtimeScheduler, descriptor.id()),
                    new RuntimePaletteFilterRegistry(checker, runtimeScheduler, descriptor.id()),
                    new RuntimeContextMenuRegistry(checker, descriptor.id()),
                    legacyConfig(
                            checker,
                            runtimeScheduler,
                            paths.configDir(),
                            descriptor.id(),
                            diagnosticSink,
                            failureSink,
                            disposableScope));
        }

        private static RuntimePluginConfigRegistry legacyConfig(
                final PermissionChecker checker,
                final RuntimeScheduler runtimeScheduler,
                final java.nio.file.Path configDir,
                final String pluginId,
                final Consumer<StartupReport.DiagnosticProblem> diagnosticSink,
                final RuntimeFailureSink failureSink,
                final DisposableScope disposableScope) {
            final RuntimePluginConfigRegistry config = new RuntimePluginConfigRegistry(
                    checker, runtimeScheduler, configDir, pluginId, diagnosticSink, failureSink);
            try {
                disposableScope.register(config);
                return config;
            } catch (RuntimeException | Error failure) {
                config.close();
                throw failure;
            }
        }

        private record DefaultServices(
                EventBus eventBus,
                ActionRegistry actions,
                MenuRegistry menus,
                MainToolbarRegistry mainToolbar,
                PaletteToolbarRegistry paletteToolbar,
                PaletteFilterRegistry paletteFilter,
                ContextMenuRegistry contextMenu,
                PluginConfigRegistry config) {}

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
