package dev.turboism.sdk.plugin;

import dev.turboism.sdk.performance.PerformanceProbeService;
import dev.turboism.sdk.action.ActionRegistry;
import dev.turboism.sdk.appearance.AppearanceService;
import dev.turboism.sdk.config.PluginConfigRegistry;
import dev.turboism.sdk.cubism.CubismFacade;
import dev.turboism.sdk.cubism.filechooser.FileChooserHistoryService;
import dev.turboism.sdk.cubism.recentfile.RecentFileService;
import dev.turboism.sdk.cubism.recentpreview.RecentPreviewContributionService;
import dev.turboism.sdk.cubism.backup.EditorAutoBackupService;
import dev.turboism.sdk.cubism.screenshot.ScreenshotCaptureService;
import dev.turboism.sdk.cubism.service.query.ModelHierarchyQueryService;
import dev.turboism.sdk.cubism.service.query.ParameterQueryService;
import dev.turboism.sdk.cubism.service.query.SelectionQueryService;
import dev.turboism.sdk.cubism.service.read.CubismReadCapabilityService;
import dev.turboism.sdk.cubism.mesh.MeshMirrorAxisService;
import dev.turboism.sdk.cubism.mesh.MeshEditParticipation;
import dev.turboism.sdk.cubism.mesh.MeshEditService;
import dev.turboism.sdk.cubism.mesh.MeshEditUiService;
import dev.turboism.sdk.cubism.mesh.MeshMirrorCounterparts;
import dev.turboism.sdk.cubism.mesh.MeshMirrorMoveParticipation;
import dev.turboism.sdk.cubism.mesh.MeshMirrorToolEligibility;
import dev.turboism.sdk.cubism.model.ModelObjectService;
import dev.turboism.sdk.cubism.physics.PhysicsEditorService;
import dev.turboism.sdk.cubism.command.EditorCommandService;
import dev.turboism.sdk.diagnostics.DiagnosticReport;
import dev.turboism.sdk.event.EventBus;
import dev.turboism.sdk.i18n.PluginLocalization;
import dev.turboism.sdk.hostread.AsyncHostReadService;
import dev.turboism.sdk.menu.MenuRegistry;
import dev.turboism.sdk.mcp.McpConnectionService;
import dev.turboism.sdk.permission.PluginPermission;
import dev.turboism.sdk.storage.PluginStorage;
import dev.turboism.sdk.script.ScriptService;
import dev.turboism.sdk.task.PluginTaskScheduler;
import dev.turboism.sdk.runtime.CubismLogService;
import dev.turboism.sdk.runtime.RuntimeSettingsService;
import dev.turboism.sdk.ui.UiHostCapabilityService;
import dev.turboism.sdk.ui.UserFileAccessService;
import dev.turboism.sdk.ui.UiScheduler;
import dev.turboism.sdk.ui.dialog.HostDialogAutomationService;
import dev.turboism.sdk.ui.context.ContextMenuRegistry;
import dev.turboism.sdk.ui.filter.PaletteFilterRegistry;
import dev.turboism.sdk.ui.toolbar.MainToolbarRegistry;
import dev.turboism.sdk.ui.toolbar.PaletteToolbarRegistry;
import dev.turboism.sdk.ui.table.SceneTableService;
import dev.turboism.sdk.ui.workspace.WorkspaceService;
import dev.turboism.sdk.ui.workspace.layout.WorkspaceLayoutService;

import java.util.List;
import dev.turboism.sdk.cubism.service.clipmask.CubismClipMaskService;

/**
 * Runtime context provided to a plugin during {@link TurboismPlugin#init(PluginContext)}.
 *
 * <p>Optional surfaces follow a single unavailability contract: every optional accessor returns
 * the service's {@code unavailable()} singleton, and each service exposes {@code isAvailable()}
 * for probing ({@code false} only on the sentinel). Accessors never throw
 * {@link UnsupportedOperationException} themselves; what the sentinel does on a domain call is
 * defined by each service's contract (structured failure result, empty value, or a stable
 * exception).</p>
 */
public interface PluginContext {

    PluginDescriptor descriptor();

    /**
     * Returns the framework logger scoped to {@link #descriptor() the current plugin}.
     *
     * <p>Every record automatically carries the plugin descriptor id and is written to Turboism's
     * session log and, when available, Cubism's host logger.</p>
     *
     * @return the current plugin's logger
     */
    PluginLogger logger();

    PluginPaths paths();

    default PluginLocalization localization() {
        return PluginLocalization.unavailable();
    }

    default PluginTaskScheduler tasks() {
        return PluginTaskScheduler.unavailable();
    }

    default AsyncHostReadService hostReads() {
        return AsyncHostReadService.unavailable();
    }

    default PluginStorage storage() {
        return PluginStorage.unavailable();
    }

    default ScriptService scripts() {
        return ScriptService.unavailable();
    }

    default UserFileAccessService userFiles() {
        return UserFileAccessService.unavailable();
    }

    CubismFacade cubism();

    default ParameterQueryService parameterQuery() {
        return ParameterQueryService.unavailable();
    }

    default SelectionQueryService selectionQuery() {
        return SelectionQueryService.unavailable();
    }

    default ModelHierarchyQueryService modelHierarchyQuery() {
        return ModelHierarchyQueryService.unavailable();
    }

    default CubismReadCapabilityService cubismRead() {
        return CubismReadCapabilityService.unavailable();
    }

    default ModelObjectService modelObjects() {
        return ModelObjectService.unavailable();
    }

    default CubismClipMaskService cubismClipMasks() {
        return CubismClipMaskService.unavailable();
    }

    default RecentFileService recentFiles() {
        return RecentFileService.unavailable();
    }

    default ScreenshotCaptureService screenshots() {
        return ScreenshotCaptureService.unavailable();
    }

    default RecentPreviewContributionService recentPreviews() {
        return RecentPreviewContributionService.unavailable();
    }

    default PhysicsEditorService physicsEditor() {
        return PhysicsEditorService.unavailable();
    }

    default FileChooserHistoryService fileChooserHistory() {
        return FileChooserHistoryService.unavailable();
    }

    default MeshMirrorAxisService meshMirrorAxis() {
        return MeshMirrorAxisService.unavailable();
    }

    default MeshEditService meshEdit() {
        return MeshEditService.unavailable();
    }

    default MeshEditParticipation meshEditParticipation() {
        return MeshEditParticipation.unavailable();
    }

    default MeshMirrorCounterparts meshMirrorCounterparts() {
        return MeshMirrorCounterparts.unavailable();
    }

    default MeshMirrorToolEligibility meshMirrorToolEligibility() {
        return MeshMirrorToolEligibility.unavailable();
    }

    default MeshMirrorMoveParticipation meshMirrorMoveParticipation() {
        return MeshMirrorMoveParticipation.unavailable();
    }

    default MeshEditUiService meshEditUi() {
        return MeshEditUiService.unavailable();
    }

    default EditorCommandService editorCommands() {
        return EditorCommandService.unavailable();
    }

    default EditorAutoBackupService backup() {
        return EditorAutoBackupService.unavailable();
    }

    List<PluginPermission> permissions();

    EventBus eventBus();

    ActionRegistry actions();

    MenuRegistry menus();

    default MainToolbarRegistry mainToolbar() {
        return MainToolbarRegistry.unavailable();
    }

    default PaletteToolbarRegistry paletteToolbar() {
        return PaletteToolbarRegistry.unavailable();
    }

    default PaletteFilterRegistry paletteFilter() {
        return PaletteFilterRegistry.unavailable();
    }

    default SceneTableService sceneTable() {
        return SceneTableService.unavailable();
    }

    default UiHostCapabilityService uiHost() {
        return UiHostCapabilityService.unavailable();
    }

    default HostDialogAutomationService hostDialogs() {
        return HostDialogAutomationService.unavailable();
    }

    default AppearanceService appearance() {
        return AppearanceService.unavailable();
    }


    default WorkspaceService workspace() {
        return WorkspaceService.unavailable();
    }

    default WorkspaceLayoutService workspaceLayout() {
        return WorkspaceLayoutService.unavailable();
    }

    default ContextMenuRegistry contextMenu() {
        return ContextMenuRegistry.unavailable();
    }

    default PluginConfigRegistry config() {
        return PluginConfigRegistry.unavailable();
    }


    default CubismLogService cubismLog() {
        return CubismLogService.unavailable();
    }

    default RuntimeSettingsService runtimeSettings() {
        return RuntimeSettingsService.unavailable();
    }

    /**
     * Returns the process-local authenticated MCP connection exchange.
     *
     * <p>The runtime permission-scopes publication and reads independently. Authorization material
     * obtained through this service must not be logged or persisted by plugins.</p>
     *
     * @return the current plugin's MCP connection service
     */
    default McpConnectionService mcpConnections() {
        return McpConnectionService.unavailable();
    }

    UiScheduler uiScheduler();

    default PerformanceProbeService performanceStats() {
        return PerformanceProbeService.unavailable();
    }

    DiagnosticReport diagnostics();

    DisposableScope disposableScope();
}
