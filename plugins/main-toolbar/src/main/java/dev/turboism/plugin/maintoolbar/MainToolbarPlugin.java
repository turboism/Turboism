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

    private dev.turboism.sdk.runtime.RuntimeSettings settings;
    @Override
    public void init(final PluginContext context) {
        this.context = context;
        this.logger = context.logger();
        this.settings = context.runtimeSettings().read();
        this.homeEntryService = new MainToolbarHomeEntryService(
            context.uiHost(),
            context.mainToolbar(),
            context.menus(),
            context.localization(),
            context.runtimeSettings()
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
        registerSettingsActions();
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

    private void registerSettingsActions() {
        registerAction("settings.safe-mode", "Safe Mode", action -> update(action, "safe-mode"));
        registerAction("settings.log-level", "Log level", action -> update(action, "log-level"));
        registerAction("settings.skip-update", "Skip update", action -> update(action, "skip-update"));
        registerAction("settings.skip-splash", "Skip splash", action -> update(action, "skip-splash"));
        registerAction("settings.skip-information", "Skip information", action -> update(action, "skip-information"));
        registerAction("settings.save", "Save settings", ignored -> {
            settings = context.runtimeSettings().save(settings);
            logger.info("Turboism settings saved; startup changes require restart");
        });
        registerAction("settings.clean-empty-docks", "Clean empty docks", ignored ->
            logger.info(context.runtimeSettings().cleanEmptyDocks().message())
        );
    }

    private void update(final ActionRegistry.ActionContext action, final String field) {
        final dev.turboism.sdk.action.UiActionEvent.Value value = action.uiEvent()
            .orElseThrow(() -> new IllegalArgumentException("settings action requires a UI event"))
            .value();
        settings = switch (field) {
            case "safe-mode" -> new dev.turboism.sdk.runtime.RuntimeSettings(
                ((dev.turboism.sdk.action.UiActionEvent.ToggleValue) value).value(), settings.logLevel(),
                settings.skipStartupUpdateCheck(), settings.skipStartupSplash(), settings.skipStartupInformation()
            );
            case "log-level" -> new dev.turboism.sdk.runtime.RuntimeSettings(
                settings.safeMode(), ((dev.turboism.sdk.action.UiActionEvent.SelectionValue) value).value(),
                settings.skipStartupUpdateCheck(), settings.skipStartupSplash(), settings.skipStartupInformation()
            );
            case "skip-update" -> new dev.turboism.sdk.runtime.RuntimeSettings(
                settings.safeMode(), settings.logLevel(),
                ((dev.turboism.sdk.action.UiActionEvent.ToggleValue) value).value(),
                settings.skipStartupSplash(), settings.skipStartupInformation()
            );
            case "skip-splash" -> new dev.turboism.sdk.runtime.RuntimeSettings(
                settings.safeMode(), settings.logLevel(), settings.skipStartupUpdateCheck(),
                ((dev.turboism.sdk.action.UiActionEvent.ToggleValue) value).value(),
                settings.skipStartupInformation()
            );
            case "skip-information" -> new dev.turboism.sdk.runtime.RuntimeSettings(
                settings.safeMode(), settings.logLevel(), settings.skipStartupUpdateCheck(),
                settings.skipStartupSplash(),
                ((dev.turboism.sdk.action.UiActionEvent.ToggleValue) value).value()
            );
            default -> throw new IllegalArgumentException("unknown settings field: " + field);
        };
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
