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

    /** Returns the current plugin's descriptor as read from plugin meta. */
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

    /** Returns the persistent and runtime paths available to the plugin. */
    PluginPaths paths();

    /** Returns the plugin-scoped localization catalog. */
    default PluginLocalization localization() {
        return PluginLocalization.unavailable();
    }

    /** Returns the plugin's task scheduler. */
    default PluginTaskScheduler tasks() {
        return PluginTaskScheduler.unavailable();
    }

    /** Returns the asynchronous host read service. */
    default AsyncHostReadService hostReads() {
        return AsyncHostReadService.unavailable();
    }

    /** Returns the plugin's bounded storage service. */
    default PluginStorage storage() {
        return PluginStorage.unavailable();
    }

    /** Returns the script discovery and execution service. */
    default ScriptService scripts() {
        return ScriptService.unavailable();
    }

    /** Returns the mediated user file access service. */
    default UserFileAccessService userFiles() {
        return UserFileAccessService.unavailable();
    }

    /** Returns the Cubism-facing facade for the current plugin. */
    CubismFacade cubism();

    /** Returns the parameter query service. */
    default ParameterQueryService parameterQuery() {
        return ParameterQueryService.unavailable();
    }

    /** Returns the selection query service. */
    default SelectionQueryService selectionQuery() {
        return SelectionQueryService.unavailable();
    }

    /** Returns the model hierarchy query service. */
    default ModelHierarchyQueryService modelHierarchyQuery() {
        return ModelHierarchyQueryService.unavailable();
    }

    /** Returns the grouped Cubism read service. */
    default CubismReadCapabilityService cubismRead() {
        return CubismReadCapabilityService.unavailable();
    }

    /** Returns the model-object authoring service. */
    default ModelObjectService modelObjects() {
        return ModelObjectService.unavailable();
    }

    /** Returns the clip-mask service. */
    default CubismClipMaskService cubismClipMasks() {
        return CubismClipMaskService.unavailable();
    }

    /** Returns the Recent Files menu projection. */
    default RecentFileService recentFiles() {
        return RecentFileService.unavailable();
    }

    /** Returns the asynchronous preview capture service for recent project files. */
    default ScreenshotCaptureService screenshots() {
        return ScreenshotCaptureService.unavailable();
    }

    /** Returns the recent-file hover preview contribution service. */
    default RecentPreviewContributionService recentPreviews() {
        return RecentPreviewContributionService.unavailable();
    }

    /** Returns the Physics Settings contribution seam. */
    default PhysicsEditorService physicsEditor() {
        return PhysicsEditorService.unavailable();
    }

    /** Returns the file-chooser history service. */
    default FileChooserHistoryService fileChooserHistory() {
        return FileChooserHistoryService.unavailable();
    }

    /** Returns the mesh mirror-axis service. */
    default MeshMirrorAxisService meshMirrorAxis() {
        return MeshMirrorAxisService.unavailable();
    }

    /** Returns the mesh editing service. */
    default MeshEditService meshEdit() {
        return MeshEditService.unavailable();
    }

    /** Returns the mesh-edit participation service. */
    default MeshEditParticipation meshEditParticipation() {
        return MeshEditParticipation.unavailable();
    }

    /** Returns the mesh mirror-counterpart resolution service. */
    default MeshMirrorCounterparts meshMirrorCounterparts() {
        return MeshMirrorCounterparts.unavailable();
    }

    /** Returns the mesh mirror tool-eligibility service. */
    default MeshMirrorToolEligibility meshMirrorToolEligibility() {
        return MeshMirrorToolEligibility.unavailable();
    }

    /** Returns the mesh mirror move-participation service. */
    default MeshMirrorMoveParticipation meshMirrorMoveParticipation() {
        return MeshMirrorMoveParticipation.unavailable();
    }

    /** Returns the mesh-edit UI service. */
    default MeshEditUiService meshEditUi() {
        return MeshEditUiService.unavailable();
    }

    /** Returns the Editor command execution service. */
    default EditorCommandService editorCommands() {
        return EditorCommandService.unavailable();
    }

    /** Returns the Editor auto-backup service. */
    default EditorAutoBackupService backup() {
        return EditorAutoBackupService.unavailable();
    }

    /** Returns the permissions the plugin declared in its meta. */
    List<PluginPermission> permissions();

    /**
     * Returns the optional services this context actually installed.
     *
     * <p>The default reports an empty set: a context that does not track installations fails
     * closed rather than claiming services it cannot prove. Presence means the corresponding
     * getter resolves to a usable service object; it does not imply the plugin holds the
     * permissions that service's operations require, and version-routed members may still fail
     * on an unsupported host.</p>
     *
     * @return installed optional services; never {@code null}
     */
    default java.util.Set<PluginService> availableServices() {
        return java.util.Set.of();
    }

    /** Returns the typed event bus. */
    EventBus eventBus();

    /** Returns the action registry. */
    ActionRegistry actions();

    /** Returns the menu contribution registry. */
    MenuRegistry menus();

    /** Returns the main-toolbar contribution registry. */
    default MainToolbarRegistry mainToolbar() {
        return MainToolbarRegistry.unavailable();
    }

    /** Returns the palette-toolbar contribution registry. */
    default PaletteToolbarRegistry paletteToolbar() {
        return PaletteToolbarRegistry.unavailable();
    }

    /** Returns the palette filter-box contribution registry. */
    default PaletteFilterRegistry paletteFilter() {
        return PaletteFilterRegistry.unavailable();
    }

    /** Returns the Scene palette table service. */
    default SceneTableService sceneTable() {
        return SceneTableService.unavailable();
    }

    /** Returns the UI-host capability surface. */
    default UiHostCapabilityService uiHost() {
        return UiHostCapabilityService.unavailable();
    }

    /** Declarative native icon references; unavailable until a verified provider is installed. */
    default dev.turboism.sdk.ui.resource.UiResourceService uiResources() {
        return dev.turboism.sdk.ui.resource.UiResourceService.unavailable();
    }

    /** Returns the host dialog automation service. */
    default HostDialogAutomationService hostDialogs() {
        return HostDialogAutomationService.unavailable();
    }

    /** Returns the theme appearance service. */
    default AppearanceService appearance() {
        return AppearanceService.unavailable();
    }


    /** Returns the workspace arrangement service. */
    default WorkspaceService workspace() {
        return WorkspaceService.unavailable();
    }

    /** Returns the workspace dock-layout query service. */
    default WorkspaceLayoutService workspaceLayout() {
        return WorkspaceLayoutService.unavailable();
    }

    /** Returns the context-menu contribution registry. */
    default ContextMenuRegistry contextMenu() {
        return ContextMenuRegistry.unavailable();
    }

    /** Returns the plugin configuration registry. */
    default PluginConfigRegistry config() {
        return PluginConfigRegistry.unavailable();
    }


    /** Returns the Cubism log stream service. */
    default CubismLogService cubismLog() {
        return CubismLogService.unavailable();
    }

    /** Returns the global runtime settings service. */
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

    /** Returns the scheduler for UI-thread work. */
    UiScheduler uiScheduler();

    /** Returns the performance probe service. */
    default PerformanceProbeService performanceStats() {
        return PerformanceProbeService.unavailable();
    }

    /** Returns the plugin's diagnostic report view. */
    DiagnosticReport diagnostics();

    /**
     * Returns the plugin's disposable scope; resources registered into it close in reverse
     * order when the scope closes.
     */
    DisposableScope disposableScope();
}
