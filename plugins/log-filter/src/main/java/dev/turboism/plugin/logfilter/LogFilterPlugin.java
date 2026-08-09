package dev.turboism.plugin.logfilter;

import dev.turboism.plugin.logfilter.b1.application.LogFilterSettingsBinding;
import dev.turboism.plugin.logfilter.service.LogFilterPaletteService;
import dev.turboism.sdk.i18n.PluginLocalization;
import dev.turboism.sdk.action.ActionRegistry;
import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.sdk.plugin.PluginLogger;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.plugin.TurboismPlugin;

import java.util.function.Consumer;

public final class LogFilterPlugin implements TurboismPlugin {

    private static final String TOGGLE_LEVEL_ACTION_ID = "log-filter.toggle-level";
    private static final String TOGGLE_LEVEL_ACTION_LABEL_KEY = "log-filter.toggle-level.label";

    private LogFilterSettingsBinding b1Application = new LogFilterSettingsBinding();
    private PluginContext context;
    private PluginLogger logger;
    private LogFilterPaletteService paletteService;
    private PluginLocalization localization;

    @Override
    public void init(final PluginContext context) {
        this.context = context;
        b1Application.init(context.config());
        this.logger = context.logger();
        try {
            this.localization = context.localization();
            this.paletteService = new LogFilterPaletteService(context.uiHost(), localization);
        } catch (UnsupportedOperationException unavailable) {
            this.localization = null;
            this.paletteService = new LogFilterPaletteService(context.uiHost());
        }
        logger.info("LogFilterPlugin initialized");
    }

    @Override
    public void enable() {
        b1Application.enable();
        registerAction(TOGGLE_LEVEL_ACTION_ID, localization == null ? "Toggle Log Filter Level" : localization.text(TOGGLE_LEVEL_ACTION_LABEL_KEY), ignored -> paletteService.toggleFilterLevel());
        context.disposableScope().register(paletteService.registerPaletteToolbar());
        logger.info("LogFilterPlugin enabled: log palette toolbar contribution enrolled in disposable scope");
    }

    @Override
    public void disable() {
        b1Application.disable();
        logger.info("LogFilterPlugin disabled");
    }

    @Override
    public void shutdown() {
        b1Application.shutdown();
        logger.info("LogFilterPlugin shutdown");
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
