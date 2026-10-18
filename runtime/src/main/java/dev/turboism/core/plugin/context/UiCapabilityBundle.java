package dev.turboism.core.plugin.context;

import dev.turboism.adapter.RuntimeHostAdapters;
import dev.turboism.adapter.host.RuntimeHostAdapterAccess;
import dev.turboism.permissions.CubismPermissionGate;
import dev.turboism.permissions.PermissionChecker;
import dev.turboism.recentfile.RuntimeRecentFileService;
import dev.turboism.recentpreview.RuntimeRecentPreviewContributionService;
import dev.turboism.screenshot.RuntimeScreenshotCaptureService;
import dev.turboism.sdk.action.ActionCatalogService;
import dev.turboism.sdk.appearance.AppearanceService;
import dev.turboism.sdk.cubism.recentfile.RecentFileService;
import dev.turboism.sdk.cubism.recentpreview.RecentPreviewContributionService;
import dev.turboism.sdk.cubism.screenshot.ScreenshotCaptureService;
import dev.turboism.sdk.i18n.PluginLocalization;
import dev.turboism.sdk.plugin.PluginService;
import dev.turboism.sdk.ui.UiHostCapabilityService;
import dev.turboism.sdk.ui.context.ContextMenuRegistry;
import dev.turboism.sdk.ui.dialog.HostDialogAutomationService;
import dev.turboism.sdk.ui.filter.PaletteFilterRegistry;
import dev.turboism.sdk.ui.resource.UiResourceService;
import dev.turboism.sdk.ui.table.SceneTableService;
import dev.turboism.sdk.ui.toolbar.MainToolbarRegistry;
import dev.turboism.sdk.ui.toolbar.PaletteToolbarRegistry;
import dev.turboism.sdk.ui.workspace.WorkspaceService;
import dev.turboism.sdk.ui.workspace.layout.WorkspaceLayoutService;
import dev.turboism.ui.RuntimeUiHostCapabilityService;
import dev.turboism.ui.action.RuntimeActionCatalogService;
import dev.turboism.ui.appearance.RuntimeAppearanceService;
import dev.turboism.ui.dialog.RuntimeHostDialogAutomationService;
import dev.turboism.ui.filter.RuntimePaletteFilterRegistry;
import dev.turboism.ui.toolbar.RuntimeMainToolbarRegistry;
import dev.turboism.ui.toolbar.RuntimePaletteToolbarRegistry;
import dev.turboism.ui.workspace.RuntimeWorkspaceService;
import dev.turboism.ui.workspace.layout.RuntimeWorkspaceLayoutService;
import dev.turboism.ui.workspace.layout.WorkspaceLayoutCoordinator;
import java.util.Objects;

/**
 * Immutable capability bundle holding UI, workspace, appearance, dialog and toolbar
 * contribution services for a plugin context.
 */
record UiCapabilityBundle(
        MainToolbarRegistry mainToolbarRegistry,
        PaletteToolbarRegistry paletteToolbarRegistry,
        PaletteFilterRegistry paletteFilterRegistry,
        ContextMenuRegistry contextMenuRegistry,
        UiHostCapabilityService uiHostCapabilityService,
        UiResourceService uiResourceService,
        HostDialogAutomationService hostDialogAutomationService,
        AppearanceService appearanceService,
        WorkspaceService workspaceService,
        WorkspaceLayoutService workspaceLayoutService,
        SceneTableService sceneTableService,
        RecentFileService recentFileService,
        ScreenshotCaptureService screenshotCaptureService,
        RecentPreviewContributionService recentPreviewContributionService,
        ActionCatalogService actionCatalogService)
        implements PluginCapabilityBundle {

    UiCapabilityBundle {
        Objects.requireNonNull(mainToolbarRegistry, "mainToolbarRegistry");
        Objects.requireNonNull(paletteToolbarRegistry, "paletteToolbarRegistry");
        Objects.requireNonNull(paletteFilterRegistry, "paletteFilterRegistry");
        Objects.requireNonNull(contextMenuRegistry, "contextMenuRegistry");
        Objects.requireNonNull(uiHostCapabilityService, "uiHostCapabilityService");
        Objects.requireNonNull(uiResourceService, "uiResourceService");
        Objects.requireNonNull(hostDialogAutomationService, "hostDialogAutomationService");
        Objects.requireNonNull(appearanceService, "appearanceService");
        Objects.requireNonNull(workspaceService, "workspaceService");
        Objects.requireNonNull(workspaceLayoutService, "workspaceLayoutService");
        Objects.requireNonNull(sceneTableService, "sceneTableService");
        Objects.requireNonNull(recentFileService, "recentFileService");
        Objects.requireNonNull(screenshotCaptureService, "screenshotCaptureService");
        Objects.requireNonNull(recentPreviewContributionService, "recentPreviewContributionService");
        Objects.requireNonNull(actionCatalogService, "actionCatalogService");
    }

    static UiCapabilityBundle create(
            final RuntimeHostAdapterAccess hostAccess,
            final RuntimeHostAdapters adapters,
            final CorePluginContext.Dependencies dependencies,
            final PluginLocalization localization,
            final CubismEditorApiAvailabilityInterceptor editorApiAvailability) {
        final MainToolbarRegistry mainToolbar = dependencies.mainToolbar();
        final PaletteToolbarRegistry paletteToolbar = dependencies.paletteToolbar();
        final PaletteFilterRegistry paletteFilter = dependencies.paletteFilter();
        final ContextMenuRegistry contextMenu = dependencies.contextMenu();
        final UiResourceService uiResourceService = adapters.uiResources();

        bindContributionLocalization(mainToolbar, paletteToolbar, paletteFilter, localization);

        final SceneTableService sceneTableService =
                hostAccess == null ? SceneTableService.unavailable() : hostAccess.sceneTable();

        final AppearanceService appearanceService;
        if (hostAccess == null) {
            appearanceService = AppearanceService.unavailable();
        } else {
            final String pluginId = dependencies.descriptor().id();
            final long pluginGeneration = 0L;
            final RuntimeAppearanceService appearance = new RuntimeAppearanceService(
                    pluginId,
                    pluginGeneration,
                    PermissionChecker.from(new CubismPermissionGate(
                            pluginId,
                            dependencies.permissions(),
                            dependencies.cubismAuditSink(),
                            dependencies.clock())),
                    hostAccess.appearanceCoordinator());
            appearanceService = appearance;
            dependencies
                    .disposableScope()
                    .register(() -> hostAccess.appearanceCoordinator().restore(pluginId, pluginGeneration));
        }

        final PermissionChecker uiPermissionChecker = PermissionChecker.from(new CubismPermissionGate(
                dependencies.descriptor().id(),
                dependencies.permissions(),
                dependencies.cubismAuditSink(),
                dependencies.clock()));

        final RecentFileService recentFileService = hostAccess == null
                ? RecentFileService.unavailable()
                : new RuntimeRecentFileService(adapters.recentFiles(), uiPermissionChecker);
        final ScreenshotCaptureService screenshotCaptureService = hostAccess == null
                ? ScreenshotCaptureService.unavailable()
                : new RuntimeScreenshotCaptureService(adapters.screenshots(), uiPermissionChecker);
        final RecentPreviewContributionService recentPreviewContributionService = hostAccess == null
                ? RecentPreviewContributionService.unavailable()
                : new RuntimeRecentPreviewContributionService(adapters.recentPreviews(), uiPermissionChecker);
        final HostDialogAutomationService hostDialogAutomationService =
                new RuntimeHostDialogAutomationService(uiPermissionChecker);

        final WorkspaceService rawWorkspace = hostAccess == null
                ? WorkspaceService.unavailable()
                : new RuntimeWorkspaceService(uiPermissionChecker, hostAccess.workspaceCoordinator());
        final WorkspaceService workspaceService =
                editorApiAvailability.wrapForTesting(rawWorkspace, WorkspaceService.class);
        if (rawWorkspace instanceof RuntimeWorkspaceService runtimeWorkspace) {
            dependencies.disposableScope().register(runtimeWorkspace);
        }

        final WorkspaceLayoutCoordinator layoutCoordinator =
                hostAccess == null ? null : hostAccess.workspaceLayoutCoordinator();
        final WorkspaceLayoutService workspaceLayoutService = hostAccess == null || layoutCoordinator == null
                ? WorkspaceLayoutService.unavailable()
                : new RuntimeWorkspaceLayoutService(uiPermissionChecker, layoutCoordinator);
        if (layoutCoordinator != null) {
            dependencies.disposableScope().register((RuntimeWorkspaceLayoutService) workspaceLayoutService);
        }

        final UiHostCapabilityService uiHostCapabilityService = hostAccess == null
                ? new RuntimeUiHostCapabilityService(
                        uiPermissionChecker,
                        dependencies.descriptor().id(),
                        dependencies.uiHostStateSource(),
                        dependencies.disposableScope(),
                        adapters.statusToolbar(),
                        adapters.uiSurface(),
                        localization,
                        dev.turboism.ui.settings.ProcessSettingsContributions.forHost(hostAccess),
                        dependencies.logger())
                : new RuntimeUiHostCapabilityService(
                        uiPermissionChecker,
                        dependencies.descriptor().id(),
                        dependencies.uiHostStateSource(),
                        dependencies.disposableScope(),
                        adapters.statusToolbar(),
                        adapters.uiSurface(),
                        localization,
                        dev.turboism.ui.settings.ProcessSettingsContributions.forHost(hostAccess),
                        hostAccess.editorUiContributions(),
                        hostAccess.embeddedPanelActivation(),
                        (contributionId, callback) -> dependencies
                                .runtimeScheduler()
                                .dispatch(
                                        new dev.turboism.core.runtime.PluginTask(
                                                "ui.overlay-button.click",
                                                dependencies.descriptor().id(),
                                                contributionId,
                                                "none"),
                                        callback),
                        dependencies.logger());

        if (hostAccess != null) {
            UiContributionContextBinder.bind(
                    dependencies.menus(),
                    mainToolbar,
                    paletteToolbar,
                    paletteFilter,
                    contextMenu,
                    hostAccess.editorUiContributions());
            dependencies
                    .disposableScope()
                    .register(hostAccess
                            .editorUiActionRouter()
                            .register(dependencies.descriptor().id(), dependencies.actions()));
        }

        final ActionCatalogService actionCatalogService = hostAccess == null
                ? ActionCatalogService.unavailable()
                : new RuntimeActionCatalogService(
                        dependencies.descriptor().id(),
                        hostAccess.editorUiActionRouter(),
                        new CubismPermissionGate(
                                dependencies.descriptor().id(),
                                dependencies.permissions(),
                                dependencies.cubismAuditSink(),
                                dependencies.clock()),
                        dependencies.logger());

        return new UiCapabilityBundle(
                mainToolbar,
                paletteToolbar,
                paletteFilter,
                contextMenu,
                uiHostCapabilityService,
                uiResourceService,
                hostDialogAutomationService,
                appearanceService,
                workspaceService,
                workspaceLayoutService,
                sceneTableService,
                recentFileService,
                screenshotCaptureService,
                recentPreviewContributionService,
                actionCatalogService);
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
    public void registerServices(final RuntimePluginServiceDirectory directory) {
        directory.install(PluginService.MAIN_TOOLBAR, () -> mainToolbarRegistry, MainToolbarRegistry::unavailable);
        directory.install(
                PluginService.PALETTE_TOOLBAR, () -> paletteToolbarRegistry, PaletteToolbarRegistry::unavailable);
        directory.install(
                PluginService.PALETTE_FILTER, () -> paletteFilterRegistry, PaletteFilterRegistry::unavailable);
        directory.install(PluginService.CONTEXT_MENU, () -> contextMenuRegistry, ContextMenuRegistry::unavailable);
        directory.install(PluginService.SCENE_TABLE, () -> sceneTableService, SceneTableService::unavailable);
        directory.installWhenPresent(PluginService.UI_HOST, () -> uiHostCapabilityService);
        directory.install(PluginService.UI_RESOURCES, () -> uiResourceService, UiResourceService::unavailable);
        directory.installWhenPresent(PluginService.HOST_DIALOGS, () -> hostDialogAutomationService);
        directory.install(PluginService.APPEARANCE, () -> appearanceService, AppearanceService::unavailable);
        directory.install(PluginService.WORKSPACE, () -> workspaceService, WorkspaceService::unavailable);
        directory.install(
                PluginService.WORKSPACE_LAYOUT, () -> workspaceLayoutService, WorkspaceLayoutService::unavailable);
        directory.install(PluginService.ACTION_CATALOG, () -> actionCatalogService, ActionCatalogService::unavailable);
        directory.install(PluginService.RECENT_FILES, () -> recentFileService, RecentFileService::unavailable);
        directory.install(
                PluginService.SCREENSHOTS, () -> screenshotCaptureService, ScreenshotCaptureService::unavailable);
        directory.install(
                PluginService.RECENT_PREVIEWS,
                () -> recentPreviewContributionService,
                RecentPreviewContributionService::unavailable);
    }
}
