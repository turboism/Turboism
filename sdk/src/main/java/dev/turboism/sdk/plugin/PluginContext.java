package dev.turboism.sdk.plugin;

import dev.turboism.sdk.action.ActionRegistry;
import dev.turboism.sdk.appearance.AppearanceService;
import dev.turboism.sdk.config.PluginConfigRegistry;
import dev.turboism.sdk.cubism.CubismFacade;
import dev.turboism.sdk.cubism.service.query.ModelHierarchyQueryService;
import dev.turboism.sdk.cubism.service.query.ParameterQueryService;
import dev.turboism.sdk.cubism.service.query.SelectionQueryService;
import dev.turboism.sdk.cubism.service.read.CubismReadCapabilityService;
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

import java.util.List;

/**
 * Runtime context provided to a plugin during {@link TurboismPlugin#init(PluginContext)}.
 */
public interface PluginContext {

    PluginDescriptor descriptor();

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

    default PhysicsEditorService physicsEditor() {
        return PhysicsEditorService.unavailable();
    }

    default EditorCommandService editorCommands() {
        return EditorCommandService.unavailable();
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

    default HostDialogAutomationService hostDialogs() {
        throw new UnsupportedOperationException("host dialog automation service is not available");
    }

    default AppearanceService appearance() {
        return AppearanceService.unavailable();
    }


    default WorkspaceService workspace() {
        return WorkspaceService.unavailable();
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

    DiagnosticReport diagnostics();

    DisposableScope disposableScope();
}
