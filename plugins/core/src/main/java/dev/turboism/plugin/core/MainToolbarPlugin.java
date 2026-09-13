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
    private Registration panelRegistration;
    private dev.turboism.sdk.cubism.filechooser.FileChooserHistoryService fileChooserHistory;
    private dev.turboism.sdk.cubism.filechooser.FileChooserHistoryService.Registration historyProviderRegistration;
    private CoreWindows windows;

    public MainToolbarPlugin() {
        services = CorePluginServices.consume();
    }

    @Override
    public void init(final PluginContext context) {
        this.context = context;
        this.logger = context.logger();
        final dev.turboism.sdk.runtime.RuntimeSettingsService runtimeSettings = services.settings();
        this.settings = runtimeSettings.read();
        this.fileChooserHistory = context.fileChooserHistory();
        this.plugins = services.plugins();
        this.homeEntryService = new MainToolbarHomeEntryService(
            context.uiHost(), context.mainToolbar(), context.menus(), localization(context),
            runtimeSettings, plugins
        );
        this.windows = new CoreWindows(
            localization(context),
            runtimeSettings,
            services.settingsContributions(),
            plugins,
            services.logs()
        );
        logger.info("Turboism core initialized");
    }

    @Override
    public void enable() {
        registerAction(MainToolbarHomeEntryService.ACTION_ID, localization(context).text(MainToolbarHomeEntryService.ACTION_LABEL_KEY),
            ignored -> windows.showSettings());
        registerAction(MainToolbarHomeEntryService.SETTINGS_ACTION_ID,
            localization(context).text("main-toolbar.settings-menu.label"), ignored -> windows.showSettings());
        registerAction(MainToolbarHomeEntryService.PLUGINS_ACTION_ID,
            localization(context).text("main-toolbar.plugins-menu.label"), ignored -> windows.showPlugins());
        registerAction(MainToolbarHomeEntryService.LOGS_ACTION_ID,
            localization(context).text("main-toolbar.logs-menu.label"), ignored -> windows.showLogs());
        registerAction(MainToolbarHomeEntryService.ABOUT_ACTION_ID,
            localization(context).text("main-toolbar.about-menu.label"), ignored -> windows.showAbout());
        registerSettingsActions();
        context.disposableScope().register(context.uiHost().contributeSettings(
            CubismJvmSettingsContribution.createPath(
                localization(context), services.cubismJvmSettings()
            )
        ));
        context.disposableScope().register(context.uiHost().contributeSettings(
            CubismJvmSettingsContribution.create(localization(context), services.cubismJvmSettings())
        ));
        context.disposableScope().register(context.uiHost().contributeSettings(
            CubismJvmSettingsContribution.createBackupReduction(
                localization(context),
                services.cubismJvmSettings(),
                this::applyAutoBackupPreference
            )
        ));
        context.disposableScope().register(context.uiHost().contributeSettings(
            CubismJvmSettingsContribution.createZgcToggle(
                localization(context),
                services.cubismJvmSettings()
            )
        ));
        context.disposableScope().register(context.uiHost().contributeSettings(
            CubismJvmSettingsContribution.createPerformanceNote(localization(context))
        ));
        registerPluginActions();
        registerPanelTabActions();
        context.disposableScope().register(plugins);
        registerHistoryProvider();
        context.disposableScope().register(windows);
        refreshPanel();
        context.disposableScope().register(homeEntryService.registerSettingsMenu());
        context.disposableScope().register(homeEntryService.registerPluginManagementMenu());
        context.disposableScope().register(homeEntryService.registerLogsMenu());
        context.disposableScope().register(homeEntryService.registerAboutMenu());
        context.disposableScope().register(homeEntryService.registerHomeEntry());
        logger.info("Turboism main toolbar icon mode selected: "
            + (settings.useTextIcon() ? "text" : "installer"));
        applyAutoBackupPreference(services.cubismJvmSettings().reduceAutoBackup());
        logger.info("Turboism core enabled");
    }

    /**
     * Applies the opt-in auto-backup reduction through the verified
     * updateSettings path. ON captures the observed host settings into a
     * plugin-state baseline first, then disables the periodic timer while
     * keeping the user's interval/cap; OFF restores that baseline so a crash
     * while ON cannot strand backups disabled. Fail-soft: the backup service
     * may be unavailable early or the host may reject — a warning is enough.
     */
    private void applyAutoBackupPreference(final boolean reduce) {
        try {
            final dev.turboism.sdk.cubism.backup.EditorAutoBackupService backup = context.backup();
            final dev.turboism.sdk.cubism.backup.EditorAutoBackupSettings current = backup.settings();
            if (reduce) {
                if (saveBackupBaseline(current)) {
                    backup.updateSettings(new dev.turboism.sdk.cubism.backup.EditorAutoBackupSettings(
                        false,
                        clamp(current.intervalMinutes(),
                            dev.turboism.sdk.cubism.backup.EditorAutoBackupSettings.MIN_INTERVAL_MINUTES,
                            dev.turboism.sdk.cubism.backup.EditorAutoBackupSettings.MAX_INTERVAL_MINUTES),
                        clamp(current.maxMB(),
                            dev.turboism.sdk.cubism.backup.EditorAutoBackupSettings.MIN_MAX_MB,
                            dev.turboism.sdk.cubism.backup.EditorAutoBackupSettings.MAX_MAX_MB),
                        current.backupDir()));
                    logger.info("auto-backup reduced for this session (opt-in)");
                }
            } else {
                final java.util.Properties baseline = readBackupBaseline();
                if (baseline != null) {
                    backup.updateSettings(new dev.turboism.sdk.cubism.backup.EditorAutoBackupSettings(
                        Boolean.parseBoolean(baseline.getProperty("enabled", "true")),
                        clamp(Integer.parseInt(baseline.getProperty("intervalMinutes", "5")),
                            dev.turboism.sdk.cubism.backup.EditorAutoBackupSettings.MIN_INTERVAL_MINUTES,
                            dev.turboism.sdk.cubism.backup.EditorAutoBackupSettings.MAX_INTERVAL_MINUTES),
                        clamp(Integer.parseInt(baseline.getProperty("maxMB", "50")),
                            dev.turboism.sdk.cubism.backup.EditorAutoBackupSettings.MIN_MAX_MB,
                            dev.turboism.sdk.cubism.backup.EditorAutoBackupSettings.MAX_MAX_MB),
                        baseline.getProperty("backupDir")
                    ));
                    java.nio.file.Files.deleteIfExists(baselineFile());
                    logger.info("auto-backup baseline restored");
                }
            }
        } catch (Exception failure) {
            logger.warn("auto-backup preference apply failed: " + failure);
        }
    }

    private java.nio.file.Path baselineFile() {
        return context.paths().stateDir().resolve("auto-backup-baseline.properties");
    }

    private static int clamp(final int value, final int min, final int max) {
        return Math.max(min, Math.min(max, value));
    }

    /** Writes the baseline only when absent — a crashed session's baseline must survive. */
    private boolean saveBackupBaseline(
        final dev.turboism.sdk.cubism.backup.EditorAutoBackupSettings current
    ) throws java.io.IOException {
        final java.nio.file.Path file = baselineFile();
        if (java.nio.file.Files.exists(file)) {
            return true;
        }
        final java.util.Properties props = new java.util.Properties();
        props.setProperty("enabled", Boolean.toString(current.enabled()));
        props.setProperty("intervalMinutes", Integer.toString(current.intervalMinutes()));
        props.setProperty("maxMB", Integer.toString(current.maxMB()));
        if (current.backupDir() != null) {
            props.setProperty("backupDir", current.backupDir());
        }
        java.nio.file.Files.createDirectories(file.getParent());
        try (java.io.OutputStream out = java.nio.file.Files.newOutputStream(file)) {
            props.store(out, "host auto-backup settings before Turboism opt-in reduction");
        }
        return true;
    }

    private java.util.Properties readBackupBaseline() throws java.io.IOException {
        final java.nio.file.Path file = baselineFile();
        if (!java.nio.file.Files.exists(file)) {
            return null;
        }
        final java.util.Properties props = new java.util.Properties();
        try (java.io.InputStream in = java.nio.file.Files.newInputStream(file)) {
            props.load(in);
        }
        return props;
    }

    @Override public void disable() { logger.warn("Turboism core disable was ignored by runtime policy"); }

    @Override
    public void shutdown() {
        if (historyProviderRegistration != null) {
            historyProviderRegistration.unregister();
            historyProviderRegistration = null;
        }
        logger.info("Turboism core shutdown");
    }

    /**
     * Registers the file-chooser history persistence provider backed by the
     * plugin config dir. Failures (safe mode, missing paths) are warn-only and
     * never block core startup.
     */
    private void registerHistoryProvider() {
        try {
            historyProviderRegistration = fileChooserHistory.registerProvider(
                new SaveDirectoryHistoryProvider(context.paths().configDir())
            );
        } catch (RuntimeException failure) {
            logger.warn("File-chooser history provider registration failed safely: "
                + failure.getClass().getSimpleName() + ": " + failure.getMessage());
        }
    }

    private void registerSettingsActions() {
        registerAction("settings.safe-mode", localization(context).text("settings.safe-mode"), action -> update(action, "safe-mode"));
        registerAction("settings.log-level", localization(context).text("settings.log-level"), action -> update(action, "log-level"));
        registerAction("settings.skip-update", localization(context).text("settings.skip-update"), action -> update(action, "skip-update"));
        registerAction("settings.skip-splash", localization(context).text("settings.skip-splash"), action -> update(action, "skip-splash"));
        registerAction("settings.skip-information", localization(context).text("settings.skip-information"), action -> update(action, "skip-information"));
        registerAction("settings.separate-export-save-directory", localization(context).text("settings.separate-export-save-directory"),
            action -> update(action, "separate-export-save-directory"));
        registerAction("settings.use-text-icon", localization(context).text("settings.use-text-icon"),
            action -> update(action, "use-text-icon"));
        registerAction("settings.save", localization(context).text("settings.save"), ignored -> {
            settings = services.settings().save(settings);
            logger.info("Turboism settings saved; startup changes require restart");
        });
        registerAction("settings.clean-empty-docks", localization(context).text("settings.clean-empty-docks"), ignored ->
            logger.info(services.settings().cleanEmptyDocks().message()));
    }

    private void registerPanelTabActions() {
        registerAction(
            "turboism.panel-tab.toggle-floating",
            localization(context).text("context-menu.panel-tab.float"),
            action -> action.panelTabSelection()
                .ifPresentOrElse(
                    services.floatingPanelActions()::togglePanelFloating,
                    () -> logger.warn("Panel-tab floating action ignored without a native Tab selection")
                )
        );
        // Floating is offered only for docked tabs; closing a floating tab docks it
        // back (native close interception), matching the legacy semantics.
        context.disposableScope().register(context.contextMenu().contribute(
            panelTabContribution("turboism.panel-tab.float", "context-menu.panel-tab.float", "panel.docked")
        ));
    }

    private dev.turboism.sdk.ui.context.ContextMenuRegistry.ContextMenuContribution panelTabContribution(
        final String id,
        final String labelKey,
        final String context
    ) {
        final String label = localization(this.context).text(labelKey);
        return new dev.turboism.sdk.ui.context.ContextMenuRegistry.ContextMenuContribution(
            id,
            "turboism.panel-tab.toggle-floating",
            label,
            null,
            context,
            dev.turboism.sdk.ui.context.ContextMenuRegistry.Location.WORKSPACE_OBJECT,
            java.util.Set.of(),
            100,
            dev.turboism.sdk.ui.context.ContextMenuRegistry.Target.PANEL_TAB,
            dev.turboism.sdk.ui.context.ContextMenuRegistry.Operation.TOGGLE_PANEL_FLOATING,
            dev.turboism.sdk.ui.context.ContextMenuRegistry.ContextMenuEntry.item(
                id,
                label,
                "turboism.panel-tab.toggle-floating"
            ),
            dev.turboism.sdk.ui.context.ContextMenuRegistry.Placement.last()
        );
    }

    private void registerPluginActions() {
        registerAction(MainToolbarHomeEntryService.INSTALL_ACTION_ID, localization(context).text("plugins.install"), ignored ->
            plugins.requestInstall(this::completeOperation));
        for (CorePluginManagement.PluginInfo plugin : plugins.plugins()) {
            if (plugin.core()) continue;
            registerAction("turboism.core.plugins.enable." + plugin.id(), localization(context).format("plugins.enable", plugin.name()), ignored ->
                runOperation(() -> plugins.setEnabled(plugin.id(), true)));
            registerAction("turboism.core.plugins.disable." + plugin.id(), localization(context).format("plugins.disable", plugin.name()), ignored ->
                runOperation(() -> plugins.setEnabled(plugin.id(), false)));
            registerAction("turboism.core.plugins.uninstall." + plugin.id(), localization(context).format("plugins.uninstall", plugin.name()), ignored -> {
                if (context.uiHost().confirmDialog(new DialogRequest(
                    "turboism.core.plugins.uninstall.confirm", localization(context).text("plugins.uninstall"),
                    localization(context).format("plugins.uninstall.confirm", plugin.name())
                ))) runOperation(() -> plugins.uninstall(plugin.id()));
            });
        }
    }

    private void runOperation(final java.util.function.Supplier<CorePluginManagement.OperationResult> operation) {
        try {
            completeOperation(operation.get());
        } catch (RuntimeException failure) {
            completeOperation(CorePluginManagement.OperationResult.rejected(
                "PLUGIN_OPERATION_FAILED", localized("plugins.operation-failed", "Plugin operation failed safely.")
            ));
        }
    }

    private void completeOperation(final CorePluginManagement.OperationResult result) {
        report(result);
        try {
            refreshPanel();
        } catch (RuntimeException failure) {
            logger.warn("Plugin management panel refresh failed safely");
        }
    }

    private void refreshPanel() {
        if (panelRegistration != null) panelRegistration.close();
        panelRegistration = homeEntryService.registerTurboismPanel();
        context.disposableScope().register(panelRegistration);
    }

    private void report(final CorePluginManagement.OperationResult result) {
        if (result.accepted()) logger.info(result.message()); else logger.warn(result.message());
    }

    private String localized(final String key, final String fallback) {
        final String value = localization(context).text(key);
        return key.equals(value) ? fallback : value;
    }


    private void update(final ActionRegistry.ActionContext action, final String field) {
        final dev.turboism.sdk.action.UiActionEvent.Value value = action.uiEvent()
            .orElseThrow(() -> new IllegalArgumentException("settings action requires a UI event")).value();
        settings = switch (field) {
            case "safe-mode" -> new dev.turboism.sdk.runtime.RuntimeSettings(
                ((dev.turboism.sdk.action.UiActionEvent.ToggleValue) value).value(),
                settings.logLevel(), settings.maxLogStorageMiB(),
                settings.skipStartupUpdateCheck(), settings.skipStartupSplash(),
                settings.skipStartupInformation(), settings.separateExportSaveDirectory(),
                settings.locale(), settings.useTextIcon());
            case "log-level" -> new dev.turboism.sdk.runtime.RuntimeSettings(
                settings.safeMode(), ((dev.turboism.sdk.action.UiActionEvent.SelectionValue) value).value(),
                settings.maxLogStorageMiB(), settings.skipStartupUpdateCheck(),
                settings.skipStartupSplash(), settings.skipStartupInformation(),
                settings.separateExportSaveDirectory(), settings.locale(), settings.useTextIcon());
            case "skip-update" -> new dev.turboism.sdk.runtime.RuntimeSettings(
                settings.safeMode(), settings.logLevel(), settings.maxLogStorageMiB(),
                ((dev.turboism.sdk.action.UiActionEvent.ToggleValue) value).value(),
                settings.skipStartupSplash(), settings.skipStartupInformation(),
                settings.separateExportSaveDirectory(), settings.locale(), settings.useTextIcon());
            case "skip-splash" -> new dev.turboism.sdk.runtime.RuntimeSettings(
                settings.safeMode(), settings.logLevel(), settings.maxLogStorageMiB(),
                settings.skipStartupUpdateCheck(),
                ((dev.turboism.sdk.action.UiActionEvent.ToggleValue) value).value(),
                settings.skipStartupInformation(), settings.separateExportSaveDirectory(),
                settings.locale(), settings.useTextIcon());
            case "skip-information" -> new dev.turboism.sdk.runtime.RuntimeSettings(
                settings.safeMode(), settings.logLevel(), settings.maxLogStorageMiB(),
                settings.skipStartupUpdateCheck(), settings.skipStartupSplash(),
                ((dev.turboism.sdk.action.UiActionEvent.ToggleValue) value).value(),
                settings.separateExportSaveDirectory(), settings.locale(), settings.useTextIcon());
            case "separate-export-save-directory" -> new dev.turboism.sdk.runtime.RuntimeSettings(
                settings.safeMode(), settings.logLevel(), settings.maxLogStorageMiB(),
                settings.skipStartupUpdateCheck(), settings.skipStartupSplash(),
                settings.skipStartupInformation(),
                ((dev.turboism.sdk.action.UiActionEvent.ToggleValue) value).value(),
                settings.locale(), settings.useTextIcon());
            case "use-text-icon" -> new dev.turboism.sdk.runtime.RuntimeSettings(
                settings.safeMode(), settings.logLevel(), settings.maxLogStorageMiB(),
                settings.skipStartupUpdateCheck(), settings.skipStartupSplash(),
                settings.skipStartupInformation(), settings.separateExportSaveDirectory(),
                settings.locale(),
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
                        case "common.turboism" -> "Turboism";
                        case "main-toolbar.home.action" -> "Open Turboism";
                        case "main-toolbar.settings-menu.label" -> "Settings";
                        case "main-toolbar.plugins-menu.label" -> "Plugin Management";
                        case "context-menu.panel-tab.float" -> "Float";
                        case "main-toolbar.logs-menu.label" -> "Logs";
                        case "main-toolbar.about-menu.label" -> "About";
                        case "settings.save" -> "Save";
                        case "settings.use-text-icon" -> "Use text icon";
                        case "plugins.operation-failed" -> "Plugin operation failed safely.";
                        default -> key;
                    };
                }
                @Override public String format(final String key, final Object... arguments) {
                    return switch (key) {
                        case "plugins.enable" -> "Enable " + arguments[0];
                        case "plugins.disable" -> "Disable " + arguments[0];
                        case "plugins.uninstall" -> "Uninstall " + arguments[0];
                        default -> text(key);
                    };
                }
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
