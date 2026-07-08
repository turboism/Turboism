package dev.turboism.plugin.uitheme;

import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.sdk.plugin.PluginLogger;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.plugin.TurboismPlugin;
import dev.turboism.sdk.ui.context.ContextMenuRegistry;

public final class UiThemePlugin implements TurboismPlugin {

    private PluginContext context;
    private PluginLogger logger;

    @Override
    public void init(PluginContext context) {
        this.context = context;
        this.logger = context.logger();
        logger.info("UiThemePlugin initialized");
    }

    @Override
    public void enable() {
        registerContextMenu(
            new ContextMenuRegistry.ContextMenuContribution(
                "ui-theme.toggle",
                "Toggle Theme",
                null,
                "workspace",
                40
            )
        );
        registerContextMenu(
            new ContextMenuRegistry.ContextMenuContribution(
                "ui-theme.apply",
                "Apply Theme",
                null,
                "workspace",
                41
            )
        );
        logger.info("UiThemePlugin enabled: 2 context-menu contributions enrolled in disposable scope");
    }

    @Override
    public void disable() {
        logger.info("UiThemePlugin disabled");
    }

    @Override
    public void shutdown() {
        logger.info("UiThemePlugin shutdown");
    }

    private void registerContextMenu(ContextMenuRegistry.ContextMenuContribution contribution) {
        Registration registration = context.contextMenu().contribute(contribution);
        context.disposableScope().register(registration);
    }
}
