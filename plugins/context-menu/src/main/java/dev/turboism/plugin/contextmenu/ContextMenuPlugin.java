package dev.turboism.plugin.contextmenu;

import dev.turboism.plugin.contextmenu.b1.application.ContextMenuApplication;
import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.sdk.plugin.TurboismPlugin;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.ui.context.ContextMenuRegistry;

import java.util.Objects;

/** SDK-only migration shell; it intentionally contributes no host capability or UI. */
public final class ContextMenuPlugin implements TurboismPlugin {

    private ContextMenuApplication b1Application = new ContextMenuApplication();
    private PluginContext context;
    private boolean enabled;
    private Registration dockMenuRegistration;
    private Registration floatingMenuRegistration;

    @Override
    public void init(final PluginContext context) {
        this.context = Objects.requireNonNull(context, "context");
        context.logger().info("Context Menu migration shell initialized");
    }

    @Override
    public void enable() {
        b1Application.enable();
        requireContext();
        dockMenuRegistration = context.contextMenu().contribute(new ContextMenuRegistry.ContextMenuContribution(
            "turboism.panel-tab.float",
            context.localization().text("context-menu.panel-tab.float"),
            null,
            "panel.docked",
            100,
            ContextMenuRegistry.Target.PANEL_TAB,
            ContextMenuRegistry.Operation.TOGGLE_PANEL_FLOATING
        ));
        floatingMenuRegistration = context.contextMenu().contribute(new ContextMenuRegistry.ContextMenuContribution(
            "turboism.panel-tab.dock",
            context.localization().text("context-menu.panel-tab.dock"),
            null,
            "panel.floating",
            100,
            ContextMenuRegistry.Target.PANEL_TAB,
            ContextMenuRegistry.Operation.TOGGLE_PANEL_FLOATING
        ));
        context.disposableScope().register(dockMenuRegistration);
        context.disposableScope().register(floatingMenuRegistration);
        enabled = true;
    }

    @Override
    public void disable() {
        b1Application.disable();
        closeMenus();
        enabled = false;
    }

    @Override
    public void shutdown() {
        b1Application.shutdown();
        closeMenus();
        enabled = false;
        context = null;
    }

    boolean isEnabled() {
        return enabled;
    }

    private void closeMenus() {
        if (floatingMenuRegistration != null) {
            floatingMenuRegistration.close();
            floatingMenuRegistration = null;
        }
        if (dockMenuRegistration != null) {
            dockMenuRegistration.close();
            dockMenuRegistration = null;
        }
    }

    private void requireContext() {
        if (context == null) {
            throw new IllegalStateException("Context Menu migration shell must be initialized before enable.");
        }
    }
}
