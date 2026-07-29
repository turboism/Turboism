package dev.turboism.plugin.maintoolbar;

import dev.turboism.plugin.maintoolbar.service.MainToolbarHomeEntryService;
import dev.turboism.sdk.action.ActionRegistry;
import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.sdk.plugin.PluginLogger;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.plugin.TurboismPlugin;

import java.util.function.Consumer;

public final class MainToolbarPlugin implements TurboismPlugin {

    private PluginContext context;
    private PluginLogger logger;
    private MainToolbarHomeEntryService homeEntryService;

    @Override
    public void init(final PluginContext context) {
        this.context = context;
        this.logger = context.logger();
        this.homeEntryService = new MainToolbarHomeEntryService(
            context.uiHost(),
            context.mainToolbar(),
            context.menus(),
            context.localization()
        );
        logger.info("MainToolbarPlugin initialized");
    }

    @Override
    public void enable() {
        registerAction(
            MainToolbarHomeEntryService.ACTION_ID,
            MainToolbarHomeEntryService.ACTION_LABEL,
            ignored -> homeEntryService.openTurboismPanel()
        );
        logger.debug("MainToolbarPlugin registered settings action");
        context.disposableScope().register(homeEntryService.registerTurboismPanel());
        logger.debug("MainToolbarPlugin registered Turboism panel contribution");
        context.disposableScope().register(homeEntryService.registerSettingsMenu());
        logger.debug("MainToolbarPlugin registered Turboism settings menu contribution");
        context.disposableScope().register(homeEntryService.registerHomeEntry());
        logger.debug("MainToolbarPlugin registered main toolbar contribution");
        logger.info("MainToolbarPlugin enabled: Turboism panel, settings action, menu, and toolbar contribution enrolled in disposable scope");
    }

    @Override
    public void disable() {
        logger.info("MainToolbarPlugin disabled");
    }

    @Override
    public void shutdown() {
        logger.info("MainToolbarPlugin shutdown");
    }

    private void registerAction(
        final String id,
        final String label,
        final Consumer<ActionRegistry.ActionContext> handler
    ) {
        final Registration registration = context.actions().register(id, new ActionRegistry.Action() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public String label() {
                return label;
            }

            @Override
            public Consumer<ActionRegistry.ActionContext> handler() {
                return handler;
            }
        });
        context.disposableScope().register(registration);
    }
}
