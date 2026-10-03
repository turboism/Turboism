package dev.turboism.sdk.plugin;

import dev.turboism.sdk.Incubating;

/**
 * One optional service slot on {@link PluginContext}.
 *
 * <p>Members name the service interface they describe, so
 * {@code context.services().find(PluginStorage.class).isPresent()} tells the plugin that a
 * usable {@code PluginStorage} is installed. Guaranteed core members of
 * {@link PluginContext} ({@code descriptor}, {@code logger}, {@code paths}, {@code cubism},
 * {@code permissions}, {@code eventBus}, {@code actions}, {@code menus}, {@code uiScheduler},
 * {@code diagnostics}, {@code disposableScope}) have no member because they cannot be absent.</p>
 *
 * <p>Presence means the directory returns a usable service object; it does not imply the
 * plugin holds the permissions that service's operations require, and it does not guarantee
 * individual version-routed members succeed on the active host.</p>
 *
 * <p>The catalog itself is stable, while a member's service type keeps its own maturity:
 * members whose constant carries {@link Incubating} resolve to incubating service types and
 * may still change between framework versions.</p>
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
    @Incubating
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

    /** {@link dev.turboism.sdk.cubism.service.clipmask.CubismClipMaskService} */
    CUBISM_CLIP_MASKS(dev.turboism.sdk.cubism.service.clipmask.CubismClipMaskService.class),

    /** {@link dev.turboism.sdk.cubism.recentfile.RecentFileService} */
    RECENT_FILES(dev.turboism.sdk.cubism.recentfile.RecentFileService.class),

    /** {@link dev.turboism.sdk.cubism.screenshot.ScreenshotCaptureService} */
    SCREENSHOTS(dev.turboism.sdk.cubism.screenshot.ScreenshotCaptureService.class),

    /** {@link dev.turboism.sdk.cubism.recentpreview.RecentPreviewContributionService} */
    RECENT_PREVIEWS(dev.turboism.sdk.cubism.recentpreview.RecentPreviewContributionService.class),

    /** {@link dev.turboism.sdk.cubism.physics.PhysicsEditorService} */
    PHYSICS_EDITOR(dev.turboism.sdk.cubism.physics.PhysicsEditorService.class),

    /** {@link dev.turboism.sdk.cubism.filechooser.FileChooserHistoryService} */
    FILE_CHOOSER_HISTORY(dev.turboism.sdk.cubism.filechooser.FileChooserHistoryService.class),

    /** {@link dev.turboism.sdk.cubism.mesh.MeshMirrorAxisService} */
    MESH_MIRROR_AXIS(dev.turboism.sdk.cubism.mesh.MeshMirrorAxisService.class),

    /** {@link dev.turboism.sdk.cubism.mesh.MeshEditService} */
    MESH_EDIT(dev.turboism.sdk.cubism.mesh.MeshEditService.class),

    /** {@link dev.turboism.sdk.cubism.mesh.MeshEditParticipation} */
    MESH_EDIT_PARTICIPATION(dev.turboism.sdk.cubism.mesh.MeshEditParticipation.class),

    /** {@link dev.turboism.sdk.cubism.mesh.MeshMirrorCounterparts} */
    MESH_MIRROR_COUNTERPARTS(dev.turboism.sdk.cubism.mesh.MeshMirrorCounterparts.class),

    /** {@link dev.turboism.sdk.cubism.mesh.MeshMirrorToolEligibility} */
    MESH_MIRROR_TOOL_ELIGIBILITY(dev.turboism.sdk.cubism.mesh.MeshMirrorToolEligibility.class),

    /** {@link dev.turboism.sdk.cubism.mesh.MeshMirrorMoveParticipation} */
    MESH_MIRROR_MOVE_PARTICIPATION(dev.turboism.sdk.cubism.mesh.MeshMirrorMoveParticipation.class),

    /** {@link dev.turboism.sdk.cubism.mesh.MeshEditUiService} */
    MESH_EDIT_UI(dev.turboism.sdk.cubism.mesh.MeshEditUiService.class),

    /** Directory-only custom mesh tools; no {@link PluginContext} accessor. */
    @Incubating
    MESH_TOOLS(dev.turboism.sdk.cubism.mesh.MeshToolRegistry.class),
    /** Directory-only ordinary modeling tools; no {@link PluginContext} accessor. */
    @Incubating
    MODELING_TOOLS(dev.turboism.sdk.cubism.modeling.ModelingToolRegistry.class),

    /** {@link dev.turboism.sdk.cubism.command.EditorCommandService} */
    EDITOR_COMMANDS(dev.turboism.sdk.cubism.command.EditorCommandService.class),

    /** {@link dev.turboism.sdk.action.ActionCatalogService} */
    ACTION_CATALOG(dev.turboism.sdk.action.ActionCatalogService.class),

    /** {@link dev.turboism.sdk.cubism.backup.EditorAutoBackupService} */
    BACKUP(dev.turboism.sdk.cubism.backup.EditorAutoBackupService.class),

    /** {@link dev.turboism.sdk.ui.toolbar.MainToolbarRegistry} */
    MAIN_TOOLBAR(dev.turboism.sdk.ui.toolbar.MainToolbarRegistry.class),

    /** {@link dev.turboism.sdk.ui.toolbar.PaletteToolbarRegistry} */
    PALETTE_TOOLBAR(dev.turboism.sdk.ui.toolbar.PaletteToolbarRegistry.class),

    /** {@link dev.turboism.sdk.ui.filter.PaletteFilterRegistry} */
    PALETTE_FILTER(dev.turboism.sdk.ui.filter.PaletteFilterRegistry.class),

    /** {@link dev.turboism.sdk.ui.table.SceneTableService} */
    SCENE_TABLE(dev.turboism.sdk.ui.table.SceneTableService.class),

    /** {@link dev.turboism.sdk.ui.UiHostCapabilityService} */
    UI_HOST(dev.turboism.sdk.ui.UiHostCapabilityService.class),

    /** {@link dev.turboism.sdk.ui.dialog.HostDialogAutomationService} */
    HOST_DIALOGS(dev.turboism.sdk.ui.dialog.HostDialogAutomationService.class),

    /** {@link dev.turboism.sdk.appearance.AppearanceService} */
    APPEARANCE(dev.turboism.sdk.appearance.AppearanceService.class),

    /** {@link dev.turboism.sdk.ui.workspace.WorkspaceService} */
    WORKSPACE(dev.turboism.sdk.ui.workspace.WorkspaceService.class),

    /** {@link dev.turboism.sdk.ui.workspace.layout.WorkspaceLayoutService} */
    WORKSPACE_LAYOUT(dev.turboism.sdk.ui.workspace.layout.WorkspaceLayoutService.class),

    /** {@link dev.turboism.sdk.ui.context.ContextMenuRegistry} */
    CONTEXT_MENU(dev.turboism.sdk.ui.context.ContextMenuRegistry.class),

    /** {@link PluginContext#config()} */
    CONFIG(dev.turboism.sdk.config.PluginConfigRegistry.class),

    /** {@link dev.turboism.sdk.runtime.CubismLogService} */
    CUBISM_LOG(dev.turboism.sdk.runtime.CubismLogService.class),

    /** {@link dev.turboism.sdk.runtime.RuntimeSettingsService} */
    RUNTIME_SETTINGS(dev.turboism.sdk.runtime.RuntimeSettingsService.class),

    /** {@link dev.turboism.sdk.mcp.McpConnectionService} */
    @Incubating
    MCP_CONNECTIONS(dev.turboism.sdk.mcp.McpConnectionService.class),

    /** {@link dev.turboism.sdk.performance.PerformanceProbeService} */
    PERFORMANCE_STATS(dev.turboism.sdk.performance.PerformanceProbeService.class),

    /** {@link dev.turboism.sdk.cubism.warp.WarpAltMirrorParticipation} */
    WARP_ALT_MIRROR_PARTICIPATION(dev.turboism.sdk.cubism.warp.WarpAltMirrorParticipation.class),

    /** {@link dev.turboism.sdk.ui.viewcontext.ViewContextMenuRegistry} */
    VIEW_CONTEXT_MENU(dev.turboism.sdk.ui.viewcontext.ViewContextMenuRegistry.class),

    /** {@link dev.turboism.sdk.ui.resource.UiResourceService} */
    UI_RESOURCES(dev.turboism.sdk.ui.resource.UiResourceService.class),

    /** {@link dev.turboism.sdk.cubism.export.ExportSettingsContributionService} */
    EXPORT_SETTINGS(dev.turboism.sdk.cubism.export.ExportSettingsContributionService.class);

    private final Class<?> type;

    PluginService(final Class<?> type) {
        this.type = type;
    }

    /** The service interface this member names, usable with {@link PluginServiceDirectory#find}. */
    public Class<?> type() {
        return type;
    }

    /**
     * Returns the member naming {@code serviceType}, or empty when no member maps it.
     *
     * @param serviceType the service interface to look up
     * @return the matching member, or empty
     */
    public static java.util.Optional<PluginService> forType(final Class<?> serviceType) {
        for (final PluginService member : values()) {
            if (member.type == serviceType) {
                return java.util.Optional.of(member);
            }
        }
        return java.util.Optional.empty();
    }
}
