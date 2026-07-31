package dev.turboism.plugin.core;

import dev.turboism.plugin.core.service.MainToolbarHomeEntryService;
import dev.turboism.sdk.action.ActionRegistry;
import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.sdk.plugin.PluginLogger;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.plugin.TurboismPlugin;
import dev.turboism.sdk.ui.DialogRequest;

import java.util.function.Consumer;

/** Built-in, non-removable Turboism core plugin. */
public final class MainToolbarPlugin implements TurboismPlugin {

    private PluginContext context;
    private PluginLogger logger;
    private MainToolbarHomeEntryService homeEntryService;
    private final CorePluginServices services;
    private CorePluginManagement plugins;
    private dev.turboism.sdk.runtime.RuntimeSettings settings;

    public MainToolbarPlugin() {
        services = CorePluginServices.consume();
    }

    @Override
    public void init(final PluginContext context) {
        this.context = context;
        this.logger = context.logger();
        final dev.turboism.sdk.runtime.RuntimeSettingsService runtimeSettings = services.settings();
        this.settings = runtimeSettings.read();
        this.plugins = services.plugins();
        this.homeEntryService = new MainToolbarHomeEntryService(
            context.uiHost(), context.mainToolbar(), context.menus(), localization(context),
            runtimeSettings, plugins
        );
        logger.info("Turboism core initialized");
    }

    @Override
    public void enable() {
        registerAction(MainToolbarHomeEntryService.ACTION_ID, MainToolbarHomeEntryService.ACTION_LABEL,
            ignored -> homeEntryService.openTurboismPanel());
        registerSettingsActions();
        registerPluginActions();
        context.disposableScope().register(plugins);
        context.disposableScope().register(homeEntryService.registerTurboismPanel());
        context.disposableScope().register(homeEntryService.registerSettingsMenu());
        context.disposableScope().register(homeEntryService.registerPluginManagementMenu());
        context.disposableScope().register(homeEntryService.registerHomeEntry());
        logger.info("Turboism core enabled");
    }

    @Override public void disable() { logger.warn("Turboism core disable was ignored by runtime policy"); }
    @Override public void shutdown() { logger.info("Turboism core shutdown"); }

    private void registerSettingsActions() {
        registerAction("settings.safe-mode", "Safe Mode", action -> update(action, "safe-mode"));
        registerAction("settings.log-level", "Log level", action -> update(action, "log-level"));
        registerAction("settings.skip-update", "Skip update", action -> update(action, "skip-update"));
        registerAction("settings.skip-splash", "Skip splash", action -> update(action, "skip-splash"));
        registerAction("settings.skip-information", "Skip information", action -> update(action, "skip-information"));
        registerAction("settings.save", "Save settings", ignored -> {
            settings = services.settings().save(settings);
            logger.info("Turboism settings saved; startup changes require restart");
        });
        registerAction("settings.clean-empty-docks", "Clean empty docks", ignored ->
            logger.info(services.settings().cleanEmptyDocks().message()));
    }

    private void registerPluginActions() {
        registerAction(MainToolbarHomeEntryService.INSTALL_ACTION_ID, "Install plugin", ignored ->
            plugins.requestInstall(this::report));
        for (CorePluginManagement.PluginInfo plugin : plugins.plugins()) {
            if (plugin.core()) continue;
            registerAction("turboism.core.plugins.enable." + plugin.id(), "Enable " + plugin.name(), ignored ->
                report(plugins.setEnabled(plugin.id(), true)));
            registerAction("turboism.core.plugins.disable." + plugin.id(), "Disable " + plugin.name(), ignored ->
                report(plugins.setEnabled(plugin.id(), false)));
            registerAction("turboism.core.plugins.uninstall." + plugin.id(), "Uninstall " + plugin.name(), ignored -> {
                if (context.uiHost().confirmDialog(new DialogRequest(
                    "turboism.core.plugins.uninstall.confirm", "Uninstall plugin",
                    "Uninstall " + plugin.name() + "? Plugin settings and data will be kept."
                ))) report(plugins.uninstall(plugin.id()));
            });
        }
    }

    private void report(final CorePluginManagement.OperationResult result) {
        if (result.accepted()) logger.info(result.message()); else logger.warn(result.message());
    }


    private void update(final ActionRegistry.ActionContext action, final String field) {
        final dev.turboism.sdk.action.UiActionEvent.Value value = action.uiEvent()
            .orElseThrow(() -> new IllegalArgumentException("settings action requires a UI event")).value();
        settings = switch (field) {
            case "safe-mode" -> new dev.turboism.sdk.runtime.RuntimeSettings(
                ((dev.turboism.sdk.action.UiActionEvent.ToggleValue) value).value(), settings.logLevel(),
                settings.skipStartupUpdateCheck(), settings.skipStartupSplash(), settings.skipStartupInformation());
            case "log-level" -> new dev.turboism.sdk.runtime.RuntimeSettings(
                settings.safeMode(), ((dev.turboism.sdk.action.UiActionEvent.SelectionValue) value).value(),
                settings.skipStartupUpdateCheck(), settings.skipStartupSplash(), settings.skipStartupInformation());
            case "skip-update" -> new dev.turboism.sdk.runtime.RuntimeSettings(
                settings.safeMode(), settings.logLevel(),
                ((dev.turboism.sdk.action.UiActionEvent.ToggleValue) value).value(),
                settings.skipStartupSplash(), settings.skipStartupInformation());
            case "skip-splash" -> new dev.turboism.sdk.runtime.RuntimeSettings(
                settings.safeMode(), settings.logLevel(), settings.skipStartupUpdateCheck(),
                ((dev.turboism.sdk.action.UiActionEvent.ToggleValue) value).value(), settings.skipStartupInformation());
            case "skip-information" -> new dev.turboism.sdk.runtime.RuntimeSettings(
                settings.safeMode(), settings.logLevel(), settings.skipStartupUpdateCheck(), settings.skipStartupSplash(),
                ((dev.turboism.sdk.action.UiActionEvent.ToggleValue) value).value());
            default -> throw new IllegalArgumentException("unknown settings field: " + field);
        };
    }

    private static dev.turboism.sdk.i18n.PluginLocalization localization(final PluginContext context) {
        try {
            return context.localization();
        } catch (UnsupportedOperationException unavailable) {
            return new dev.turboism.sdk.i18n.PluginLocalization() {
                @Override public java.util.Locale locale() { return java.util.Locale.ENGLISH; }
                @Override public String text(final String key) {
                    return switch (key) {
                        case "main-toolbar.settings-menu.label" -> "Settings";
                        case "main-toolbar.plugins-menu.label" -> "Plugin Management";
                        default -> key;
                    };
                }
                @Override public String format(final String key, final Object... arguments) { return text(key); }
                @Override public boolean contains(final String key) { return true; }
            };
        }
    }

    private void registerAction(
        final String id,
        final String label,
        final Consumer<ActionRegistry.ActionContext> handler
    ) {
        final Registration registration = context.actions().register(id, new ActionRegistry.Action() {
            @Override public String id() { return id; }
            @Override public String label() { return label; }
            @Override public Consumer<ActionRegistry.ActionContext> handler() { return handler; }
        });
        context.disposableScope().register(registration);
    }
}
