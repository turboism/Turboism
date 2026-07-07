package dev.turboism.sdk.plugin;

import dev.turboism.sdk.action.ActionRegistry;
import dev.turboism.sdk.diagnostics.DiagnosticReport;
import dev.turboism.sdk.event.EventBus;
import dev.turboism.sdk.menu.MenuRegistry;
import dev.turboism.sdk.permission.PluginPermission;
import dev.turboism.sdk.ui.UiScheduler;

import java.nio.file.Path;
import java.util.List;

/**
 * Runtime context provided to a plugin during {@link TurboismPlugin#init(PluginContext)}.
 */
public interface PluginContext {

    PluginDescriptor descriptor();

    PluginLogger logger();

    PluginPaths paths();

    List<PluginPermission> permissions();

    EventBus eventBus();

    ActionRegistry actions();

    MenuRegistry menus();

    UiScheduler uiScheduler();

    DiagnosticReport diagnostics();

    DisposableScope disposableScope();
}
