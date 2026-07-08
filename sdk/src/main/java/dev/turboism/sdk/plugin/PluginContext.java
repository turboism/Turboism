package dev.turboism.sdk.plugin;

import dev.turboism.sdk.action.ActionRegistry;
import dev.turboism.sdk.config.PluginConfigRegistry;
import dev.turboism.sdk.cubism.CubismFacade;
import dev.turboism.sdk.cubism.service.query.ModelHierarchyQueryService;
import dev.turboism.sdk.cubism.service.query.ParameterQueryService;
import dev.turboism.sdk.cubism.service.query.SelectionQueryService;
import dev.turboism.sdk.diagnostics.DiagnosticReport;
import dev.turboism.sdk.event.EventBus;
import dev.turboism.sdk.menu.MenuRegistry;
import dev.turboism.sdk.permission.PluginPermission;
import dev.turboism.sdk.ui.UiScheduler;
import dev.turboism.sdk.ui.context.ContextMenuRegistry;
import dev.turboism.sdk.ui.toolbar.MainToolbarRegistry;
import dev.turboism.sdk.ui.toolbar.PaletteToolbarRegistry;

import java.util.List;

/**
 * Runtime context provided to a plugin during {@link TurboismPlugin#init(PluginContext)}.
 */
public interface PluginContext {

    PluginDescriptor descriptor();

    PluginLogger logger();

    PluginPaths paths();

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

    default ContextMenuRegistry contextMenu() {
        throw new UnsupportedOperationException("contextMenu registry is not available");
    }

    default PluginConfigRegistry config() {
        throw new UnsupportedOperationException("config registry is not available");
    }

    UiScheduler uiScheduler();

    DiagnosticReport diagnostics();

    DisposableScope disposableScope();
}
