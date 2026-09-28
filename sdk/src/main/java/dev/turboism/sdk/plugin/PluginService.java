package dev.turboism.sdk.plugin;

import dev.turboism.sdk.Incubating;

/**
 * One optional service slot on {@link PluginContext}.
 *
 * <p>Members name the getter they describe, so
 * {@code context.services().get(PluginStorage.class) != null} tells the plugin that
 * {@code context.storage()} resolves to an installed service. Guaranteed core members of
 * {@link PluginContext} ({@code descriptor}, {@code logger}, {@code paths}, {@code cubism},
 * {@code permissions}, {@code eventBus}, {@code actions}, {@code menus}, {@code uiScheduler},
 * {@code diagnostics}, {@code disposableScope}) have no member because they cannot be absent.</p>
 *
 * <p>Presence means the getter returns a usable service object; it does not imply the plugin holds
 * the permissions that service's operations require, and it does not guarantee individual
 * version-routed members succeed on the active host.</p>
 */
public enum PluginService {

    /** {@link PluginContext#localization()} */
    LOCALIZATION(dev.turboism.sdk.i18n.PluginLocalization.class),

    /** {@link PluginContext#tasks()} */
    TASKS(dev.turboism.sdk.task.PluginTaskScheduler.class),

    /** {@link PluginContext#hostReads()} */
    HOST_READS(dev.turboism.sdk.hostread.AsyncHostReadService.class),

    /** {@link PluginContext#storage()} */
    STORAGE(dev.turboism.sdk.storage.PluginStorage.class),

    /** {@link PluginContext#scripts()} */
    SCRIPTS(dev.turboism.sdk.script.ScriptService.class),

    /** {@link PluginContext#userFiles()} */
    USER_FILES(dev.turboism.sdk.ui.UserFileAccessService.class),

    /** {@link PluginContext#parameterQuery()} */
    PARAMETER_QUERY(dev.turboism.sdk.cubism.service.query.ParameterQueryService.class),

    /** {@link PluginContext#selectionQuery()} */
    SELECTION_QUERY(dev.turboism.sdk.cubism.service.query.SelectionQueryService.class),

    /** {@link PluginContext#modelHierarchyQuery()} */
    MODEL_HIERARCHY_QUERY(dev.turboism.sdk.cubism.service.query.ModelHierarchyQueryService.class),

    /** {@link PluginContext#cubismRead()} */
    CUBISM_READ(dev.turboism.sdk.cubism.service.read.CubismReadCapabilityService.class),

    /** {@link PluginContext#modelObjects()} */
    MODEL_OBJECTS(dev.turboism.sdk.cubism.model.ModelObjectService.class),

    /** {@link PluginContext#cubismClipMasks()} */
    CUBISM_CLIP_MASKS(dev.turboism.sdk.cubism.service.clipmask.CubismClipMaskService.class),

    /** {@link PluginContext#recentFiles()} */
    RECENT_FILES(dev.turboism.sdk.cubism.recentfile.RecentFileService.class),

    /** {@link PluginContext#screenshots()} */
    SCREENSHOTS(dev.turboism.sdk.cubism.screenshot.ScreenshotCaptureService.class),

    /** {@link PluginContext#recentPreviews()} */
    RECENT_PREVIEWS(dev.turboism.sdk.cubism.recentpreview.RecentPreviewContributionService.class),

    /** {@link PluginContext#physicsEditor()} */
    PHYSICS_EDITOR(dev.turboism.sdk.cubism.physics.PhysicsEditorService.class),

    /** {@link PluginContext#fileChooserHistory()} */
    FILE_CHOOSER_HISTORY(dev.turboism.sdk.cubism.filechooser.FileChooserHistoryService.class),

    /** {@link PluginContext#meshMirrorAxis()} */
    MESH_MIRROR_AXIS(dev.turboism.sdk.cubism.mesh.MeshMirrorAxisService.class),

    /** {@link PluginContext#meshEdit()} */
    MESH_EDIT(dev.turboism.sdk.cubism.mesh.MeshEditService.class),

    /** {@link PluginContext#meshEditParticipation()} */
    MESH_EDIT_PARTICIPATION(dev.turboism.sdk.cubism.mesh.MeshEditParticipation.class),

    /** {@link PluginContext#meshMirrorCounterparts()} */
    MESH_MIRROR_COUNTERPARTS(dev.turboism.sdk.cubism.mesh.MeshMirrorCounterparts.class),

    /** {@link PluginContext#meshMirrorToolEligibility()} */
    MESH_MIRROR_TOOL_ELIGIBILITY(dev.turboism.sdk.cubism.mesh.MeshMirrorToolEligibility.class),

    /** {@link PluginContext#meshMirrorMoveParticipation()} */
    MESH_MIRROR_MOVE_PARTICIPATION(dev.turboism.sdk.cubism.mesh.MeshMirrorMoveParticipation.class),

    /** {@link PluginContext#meshEditUi()} */
    MESH_EDIT_UI(dev.turboism.sdk.cubism.mesh.MeshEditUiService.class),

    /** {@link PluginContext#editorCommands()} */
    EDITOR_COMMANDS(dev.turboism.sdk.cubism.command.EditorCommandService.class),

    /** {@link PluginContext#backup()} */
    BACKUP(dev.turboism.sdk.cubism.backup.EditorAutoBackupService.class),

    /** {@link PluginContext#mainToolbar()} */
    MAIN_TOOLBAR(dev.turboism.sdk.ui.toolbar.MainToolbarRegistry.class),

    /** {@link PluginContext#paletteToolbar()} */
    PALETTE_TOOLBAR(dev.turboism.sdk.ui.toolbar.PaletteToolbarRegistry.class),

    /** {@link PluginContext#paletteFilter()} */
    PALETTE_FILTER(dev.turboism.sdk.ui.filter.PaletteFilterRegistry.class),

    /** {@link PluginContext#sceneTable()} */
    SCENE_TABLE(dev.turboism.sdk.ui.table.SceneTableService.class),

    /** {@link PluginContext#uiHost()} */
    UI_HOST(dev.turboism.sdk.ui.UiHostCapabilityService.class),

    /** {@link PluginContext#hostDialogs()} */
    HOST_DIALOGS(dev.turboism.sdk.ui.dialog.HostDialogAutomationService.class),

    /** {@link PluginContext#appearance()} */
    APPEARANCE(dev.turboism.sdk.appearance.AppearanceService.class),

    /** {@link PluginContext#workspace()} */
    WORKSPACE(dev.turboism.sdk.ui.workspace.WorkspaceService.class),

    /** {@link PluginContext#workspaceLayout()} */
    WORKSPACE_LAYOUT(dev.turboism.sdk.ui.workspace.layout.WorkspaceLayoutService.class),

    /** {@link PluginContext#contextMenu()} */
    CONTEXT_MENU(dev.turboism.sdk.ui.context.ContextMenuRegistry.class),

    /** {@link PluginContext#config()} */
    CONFIG(dev.turboism.sdk.config.PluginConfigRegistry.class),

    /** {@link PluginContext#cubismLog()} */
    CUBISM_LOG(dev.turboism.sdk.runtime.CubismLogService.class),

    /** {@link PluginContext#runtimeSettings()} */
    RUNTIME_SETTINGS(dev.turboism.sdk.runtime.RuntimeSettingsService.class),

    /** {@link PluginContext#mcpConnections()} */
    MCP_CONNECTIONS(dev.turboism.sdk.mcp.McpConnectionService.class),

    /** {@link PluginContext#performanceStats()} */
    PERFORMANCE_STATS(dev.turboism.sdk.performance.PerformanceProbeService.class),

    /** {@link PluginContext#warpAltMirrorParticipation()} */
    WARP_ALT_MIRROR_PARTICIPATION(dev.turboism.sdk.cubism.warp.WarpAltMirrorParticipation.class),

    /** {@link PluginContext#viewContextMenu()} */
    VIEW_CONTEXT_MENU(dev.turboism.sdk.ui.viewcontext.ViewContextMenuRegistry.class),

    /** {@link PluginContext#uiResources()} */
    UI_RESOURCES(dev.turboism.sdk.ui.resource.UiResourceService.class),

    /** {@link PluginContext#exportSettings()} */
    EXPORT_SETTINGS(dev.turboism.sdk.cubism.export.ExportSettingsContributionService.class);

    private final Class<?> type;

    PluginService(final Class<?> type) {
        this.type = type;
    }

    /** The service interface this member names, usable with {@link PluginServiceDirectory#get}. */
    @Incubating
    public Class<?> type() {
        return type;
    }

    /**
     * Resolves this member's service on {@code context} to the installed instance, or
     * {@code null} when the context exposes only the service's unavailable sentinel.
     *
     * @param context the plugin context to read through
     * @return the installed service object, never its unavailable sentinel
     */
    @SuppressWarnings("deprecation") // Bridges the deprecated pre-directory accessors.
    Object resolve(final PluginContext context) {
        return switch (this) {
            case LOCALIZATION -> available(context.localization(), dev.turboism.sdk.i18n.PluginLocalization.unavailable());
            case TASKS -> available(context.tasks(), dev.turboism.sdk.task.PluginTaskScheduler.unavailable());
            case HOST_READS -> available(context.hostReads(), dev.turboism.sdk.hostread.AsyncHostReadService.unavailable());
            case STORAGE -> available(context.storage(), dev.turboism.sdk.storage.PluginStorage.unavailable());
            case SCRIPTS -> available(context.scripts(), dev.turboism.sdk.script.ScriptService.unavailable());
            case USER_FILES -> available(context.userFiles(), dev.turboism.sdk.ui.UserFileAccessService.unavailable());
            case PARAMETER_QUERY -> available(context.parameterQuery(), dev.turboism.sdk.cubism.service.query.ParameterQueryService.unavailable());
            case SELECTION_QUERY -> available(context.selectionQuery(), dev.turboism.sdk.cubism.service.query.SelectionQueryService.unavailable());
            case MODEL_HIERARCHY_QUERY -> available(context.modelHierarchyQuery(), dev.turboism.sdk.cubism.service.query.ModelHierarchyQueryService.unavailable());
            case CUBISM_READ -> available(context.cubismRead(), dev.turboism.sdk.cubism.service.read.CubismReadCapabilityService.unavailable());
            case MODEL_OBJECTS -> available(context.modelObjects(), dev.turboism.sdk.cubism.model.ModelObjectService.unavailable());
            case CUBISM_CLIP_MASKS -> available(context.cubismClipMasks(), dev.turboism.sdk.cubism.service.clipmask.CubismClipMaskService.unavailable());
            case RECENT_FILES -> available(context.recentFiles(), dev.turboism.sdk.cubism.recentfile.RecentFileService.unavailable());
            case SCREENSHOTS -> available(context.screenshots(), dev.turboism.sdk.cubism.screenshot.ScreenshotCaptureService.unavailable());
            case RECENT_PREVIEWS -> available(context.recentPreviews(), dev.turboism.sdk.cubism.recentpreview.RecentPreviewContributionService.unavailable());
            case PHYSICS_EDITOR -> available(context.physicsEditor(), dev.turboism.sdk.cubism.physics.PhysicsEditorService.unavailable());
            case FILE_CHOOSER_HISTORY -> available(context.fileChooserHistory(), dev.turboism.sdk.cubism.filechooser.FileChooserHistoryService.unavailable());
            case MESH_MIRROR_AXIS -> available(context.meshMirrorAxis(), dev.turboism.sdk.cubism.mesh.MeshMirrorAxisService.unavailable());
            case MESH_EDIT -> available(context.meshEdit(), dev.turboism.sdk.cubism.mesh.MeshEditService.unavailable());
            case MESH_EDIT_PARTICIPATION -> available(context.meshEditParticipation(), dev.turboism.sdk.cubism.mesh.MeshEditParticipation.unavailable());
            case MESH_MIRROR_COUNTERPARTS -> available(context.meshMirrorCounterparts(), dev.turboism.sdk.cubism.mesh.MeshMirrorCounterparts.unavailable());
            case MESH_MIRROR_TOOL_ELIGIBILITY -> available(context.meshMirrorToolEligibility(), dev.turboism.sdk.cubism.mesh.MeshMirrorToolEligibility.unavailable());
            case MESH_MIRROR_MOVE_PARTICIPATION -> available(context.meshMirrorMoveParticipation(), dev.turboism.sdk.cubism.mesh.MeshMirrorMoveParticipation.unavailable());
            case MESH_EDIT_UI -> available(context.meshEditUi(), dev.turboism.sdk.cubism.mesh.MeshEditUiService.unavailable());
            case EDITOR_COMMANDS -> available(context.editorCommands(), dev.turboism.sdk.cubism.command.EditorCommandService.unavailable());
            case BACKUP -> available(context.backup(), dev.turboism.sdk.cubism.backup.EditorAutoBackupService.unavailable());
            case MAIN_TOOLBAR -> available(context.mainToolbar(), dev.turboism.sdk.ui.toolbar.MainToolbarRegistry.unavailable());
            case PALETTE_TOOLBAR -> available(context.paletteToolbar(), dev.turboism.sdk.ui.toolbar.PaletteToolbarRegistry.unavailable());
            case PALETTE_FILTER -> available(context.paletteFilter(), dev.turboism.sdk.ui.filter.PaletteFilterRegistry.unavailable());
            case SCENE_TABLE -> available(context.sceneTable(), dev.turboism.sdk.ui.table.SceneTableService.unavailable());
            case UI_HOST -> available(context.uiHost(), dev.turboism.sdk.ui.UiHostCapabilityService.unavailable());
            case HOST_DIALOGS -> available(context.hostDialogs(), dev.turboism.sdk.ui.dialog.HostDialogAutomationService.unavailable());
            case APPEARANCE -> available(context.appearance(), dev.turboism.sdk.appearance.AppearanceService.unavailable());
            case WORKSPACE -> available(context.workspace(), dev.turboism.sdk.ui.workspace.WorkspaceService.unavailable());
            case WORKSPACE_LAYOUT -> available(context.workspaceLayout(), dev.turboism.sdk.ui.workspace.layout.WorkspaceLayoutService.unavailable());
            case CONTEXT_MENU -> available(context.contextMenu(), dev.turboism.sdk.ui.context.ContextMenuRegistry.unavailable());
            case CONFIG -> available(context.config(), dev.turboism.sdk.config.PluginConfigRegistry.unavailable());
            case CUBISM_LOG -> available(context.cubismLog(), dev.turboism.sdk.runtime.CubismLogService.unavailable());
            case RUNTIME_SETTINGS -> available(context.runtimeSettings(), dev.turboism.sdk.runtime.RuntimeSettingsService.unavailable());
            case MCP_CONNECTIONS -> available(context.mcpConnections(), dev.turboism.sdk.mcp.McpConnectionService.unavailable());
            case PERFORMANCE_STATS -> available(context.performanceStats(), dev.turboism.sdk.performance.PerformanceProbeService.unavailable());
            case WARP_ALT_MIRROR_PARTICIPATION -> available(context.warpAltMirrorParticipation(), dev.turboism.sdk.cubism.warp.WarpAltMirrorParticipation.unavailable());
            case VIEW_CONTEXT_MENU -> available(context.viewContextMenu(), dev.turboism.sdk.ui.viewcontext.ViewContextMenuRegistry.unavailable());
            case UI_RESOURCES -> available(context.uiResources(), dev.turboism.sdk.ui.resource.UiResourceService.unavailable());
            case EXPORT_SETTINGS -> available(context.exportSettings(), dev.turboism.sdk.cubism.export.ExportSettingsContributionService.unavailable());
        };
    }

    private static <T> T available(final T service, final T unavailable) {
        return service == unavailable ? null : service;
    }
}
