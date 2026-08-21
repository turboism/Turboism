package dev.turboism.sdk.plugin;

import dev.turboism.sdk.PreviewApi;
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
import dev.turboism.sdk.cubism.mesh.MeshEditUiService;
import dev.turboism.sdk.cubism.model.ModelObjectService;
import dev.turboism.sdk.cubism.physics.PhysicsEditorService;
import dev.turboism.sdk.cubism.command.EditorCommandService;
import dev.turboism.sdk.diagnostics.DiagnosticReport;
import dev.turboism.sdk.event.EventBus;
import dev.turboism.sdk.i18n.PluginLocalization;
import dev.turboism.sdk.hostread.AsyncHostReadService;
import dev.turboism.sdk.menu.MenuRegistry;
import dev.turboism.sdk.permission.PluginPermission;
import dev.turboism.sdk.storage.PluginStorage;
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
 * <p>Optional surfaces keep throwing defaults unless their SDK contract defines an existing
 * unavailable singleton; those surfaces return that singleton here for safe-mode compatibility.</p>
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
        throw new UnsupportedOperationException("localization service is not available");
    }

    default PluginTaskScheduler tasks() {
        throw new UnsupportedOperationException("task scheduler is not available");
    }

    default AsyncHostReadService hostReads() {
        throw new UnsupportedOperationException("async host read service is not available");
    }

    default PluginStorage storage() {
        throw new UnsupportedOperationException("storage service is not available");
    }

    default UserFileAccessService userFiles() {
        throw new UnsupportedOperationException("user file access service is not available");
    }

    CubismFacade cubism();

    default ParameterQueryService parameterQuery() {
        throw new UnsupportedOperationException("parameterQuery service is not available");
    }

    default SelectionQueryService selectionQuery() {
        throw new UnsupportedOperationException("selectionQuery service is not available");
    }

    default ModelHierarchyQueryService modelHierarchyQuery() {
        throw new UnsupportedOperationException("modelHierarchyQuery service is not available");
    }

    default CubismReadCapabilityService cubismRead() {
        throw new UnsupportedOperationException("cubismRead service is not available");
    }

    @PreviewApi
    default ModelObjectService modelObjects() {
        return ModelObjectService.unavailable();
    }

    @PreviewApi
    default CubismClipMaskService cubismClipMasks() {
        throw new UnsupportedOperationException("clipMask service is not available");
    }

    @PreviewApi
    default RecentFileService recentFiles() {
        return RecentFileService.unavailable();
    }

    @PreviewApi
    default ScreenshotCaptureService screenshots() {
        return ScreenshotCaptureService.unavailable();
    }

    @PreviewApi
    default RecentPreviewContributionService recentPreviews() {
        return RecentPreviewContributionService.unavailable();
    }

    default PhysicsEditorService physicsEditor() {
        return PhysicsEditorService.unavailable();
    }

    @PreviewApi
    default FileChooserHistoryService fileChooserHistory() {
        return FileChooserHistoryService.unavailable();
    }

    @PreviewApi
    default MeshMirrorAxisService meshMirrorAxis() {
        throw new UnsupportedOperationException("meshMirrorAxis service is not available");
    }

    @PreviewApi
    default MeshEditUiService meshEditUi() {
        throw new UnsupportedOperationException("meshEditUi service is not available");
    }

    @PreviewApi
    default EditorCommandService editorCommands() {
        return EditorCommandService.unavailable();
    }

    @PreviewApi
    default EditorAutoBackupService backup() {
        return EditorAutoBackupService.unavailable();
    }

    List<PluginPermission> permissions();

    EventBus eventBus();

    ActionRegistry actions();

    MenuRegistry menus();

    default MainToolbarRegistry mainToolbar() {
        throw new UnsupportedOperationException("mainToolbar registry is not available");
    }

    default PaletteToolbarRegistry paletteToolbar() {
        throw new UnsupportedOperationException("paletteToolbar registry is not available");
    }

    default PaletteFilterRegistry paletteFilter() {
        throw new UnsupportedOperationException("paletteFilter registry is not available");
    }

    default SceneTableService sceneTable() {
        return SceneTableService.unavailable();
    }

    default UiHostCapabilityService uiHost() {
        throw new UnsupportedOperationException("uiHost service is not available");
    }

    @PreviewApi
    default HostDialogAutomationService hostDialogs() {
        throw new UnsupportedOperationException("host dialog automation service is not available");
    }

    @PreviewApi
    default AppearanceService appearance() {
        return AppearanceService.unavailable();
    }


    @PreviewApi
    default WorkspaceService workspace() {
        return WorkspaceService.unavailable();
    }

    @PreviewApi
    default WorkspaceLayoutService workspaceLayout() {
        return WorkspaceLayoutService.unavailable();
    }

    default ContextMenuRegistry contextMenu() {
        throw new UnsupportedOperationException("contextMenu registry is not available");
    }

    default PluginConfigRegistry config() {
        throw new UnsupportedOperationException("config registry is not available");
    }


    default CubismLogService cubismLog() {
        return CubismLogService.unavailable();
    }

    default RuntimeSettingsService runtimeSettings() {
        throw new UnsupportedOperationException("runtime settings service is not available");
    }

    UiScheduler uiScheduler();

    default PerformanceProbeService performanceStats() {
        return PerformanceProbeService.unavailable();
    }

    DiagnosticReport diagnostics();

    DisposableScope disposableScope();
}
