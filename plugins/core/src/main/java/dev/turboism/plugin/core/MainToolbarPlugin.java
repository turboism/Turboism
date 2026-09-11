package dev.turboism.plugin.core;

import dev.turboism.plugin.core.service.MainToolbarHomeEntryService;
import dev.turboism.sdk.action.ActionRegistry;
import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.sdk.plugin.PluginLogger;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.ui.CanvasHintNotification;
import dev.turboism.sdk.plugin.TurboismPlugin;
import dev.turboism.sdk.ui.DialogRequest;

import java.util.concurrent.atomic.AtomicLong;
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
    private volatile boolean closed;
    private final AtomicLong updateUiGeneration = new AtomicLong();
    /** Keyed hint id: re-issuing the same key refreshes the native hint instead of stacking one. */
    private static final String UPDATE_HINT_ID = "turboism-update-available";
    private static final String CHECK_RESULT_HINT_ID = "turboism-update-check-result";
    private static final float CHECK_RESULT_HINT_SECONDS = 5.0f;
    /** Sits after the core menu items, which occupy 10..13. */
    private static final int UPDATE_MENU_ORDER = 14;
    private Registration updateHint;
    /** Identity currently shown by the hint, so a newer build replaces the message. */
    private String updateHintIdentity;

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
        this.closed = false;
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
        registerUpdateFeatures();
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
        if (services.update().available()) services.update().start();
        logger.info("Turboism main toolbar icon mode selected: "
            + (settings.useTextIcon() ? "text" : "installer"));
        logger.info("Turboism core enabled");
    }

    @Override public void disable() { logger.warn("Turboism core disable was ignored by runtime policy"); }

    @Override
    public void shutdown() {
        closed = true;
        updateUiGeneration.incrementAndGet();
        closeUpdateHint();
        if (historyProviderRegistration != null) {
            historyProviderRegistration.unregister();
            historyProviderRegistration = null;
        }
        logger.info("Turboism core shutdown");
    }

    private void registerUpdateFeatures() {
        final CoreUpdateService updates = services.update();
        if (!updates.available()) return;
        registerAction(
            CoreUpdateService.MANUAL_CHECK_ACTION_ID,
            localized("updates.check", "Check for updates"),
            ignored -> updates.checkManual()
        );
        try {
            final String root = localized("common.turboism", "Turboism");
            context.disposableScope().register(context.menus().contribute(
                new dev.turboism.sdk.menu.MenuRegistry.MenuContribution() {
                    @Override public String menuPath() {
                        return root + "/" + localized("updates.check", "Check for updates");
                    }
                    @Override public String actionId() {
                        return CoreUpdateService.MANUAL_CHECK_ACTION_ID;
                    }
                    @Override public int order() {
                        return UPDATE_MENU_ORDER;
                    }
                }
            ));
        } catch (RuntimeException unavailable) {
            logger.warn("Update menu contribution unavailable; continuing without it");
        }
        try {
            context.disposableScope().register(context.uiHost().contributeSettings(
                CoreUpdateSettingsContribution.create(localization(context), updates)
            ));
        } catch (RuntimeException unavailable) {
            logger.warn("Update preference contribution unavailable; continuing without it");
        }
        context.disposableScope().register(updates.subscribe(this::onUpdateSnapshot));
    }

    private void onUpdateSnapshot(final CoreUpdateService.Snapshot snapshot) {
        if (closed) return;
        final long expectedUiGeneration = updateUiGeneration.incrementAndGet();
        try {
            context.disposableScope().register(context.uiScheduler().runOnUiThread(
                () -> applyUpdateSnapshot(snapshot, expectedUiGeneration)
            ));
        } catch (RuntimeException rejected) {
            logger.warn("Update UI dispatch was rejected safely");
        }
    }

    /**
     * Presents the update state in the host's own drawing-area hint.
     *
     * <p>An available update is a condition, not an event: the hint is issued through the
     * condition watch so it stays on the drawing area while the update is still offered and clears
     * itself once it is not, and a click on it opens the download page. A user-visible check result
     * that is not an update is a one-shot notification, so it is sent dismissible and expires on its
     * own. Nothing is added to the docked panel or to the plugin tabs.</p>
     */
    private void applyUpdateSnapshot(
        final CoreUpdateService.Snapshot snapshot,
        final long expectedUiGeneration
    ) {
        if (!isDeliverable(snapshot, expectedUiGeneration)) return;
        try {
            switch (snapshot.status()) {
                case UPDATE_AVAILABLE -> showUpdateHint(snapshot);
                case UP_TO_DATE, UNAVAILABLE -> {
                    // The current result no longer claims an update is available, so the hint must
                    // go at once rather than linger on a stale "click to download".
                    closeUpdateHint();
                    if (snapshot.userInitiated()) showCheckResultHint(snapshot);
                }
                case DISABLED, CLOSED -> closeUpdateHint();
                // A check in flight proves nothing about the update, so the hint the user is
                // already reading stays put instead of flickering away and back.
                case IDLE, CHECKING -> { }
            }
        } catch (RuntimeException unavailable) {
            logger.warn("Update canvas hint was unavailable");
        }
    }

    /**
     * Keeps one keyed hint on the drawing area while the update is worth showing.
     *
     * <p>The hint is replaced rather than duplicated when the offered identity changes, so a newer
     * build never leaves the previous build's message on screen.</p>
     */
    private void showUpdateHint(final CoreUpdateService.Snapshot snapshot) {
        final String identity = snapshot.availableIdentity().orElse("a newer version");
        if (updateHint != null && identity.equals(updateHintIdentity)) return;
        closeUpdateHint();
        final String message = format(
            "updates.hint.available",
            "Turboism " + identity + " is available \u2014 click to download",
            identity
        );
        updateHintIdentity = identity;
        updateHint = context.uiHost().showCanvasHintWhile(
            context.uiScheduler(),
            new CanvasHintNotification(
                UPDATE_HINT_ID,
                message,
                CanvasHintNotification.UNTIL_DISMISSED,
                java.util.Optional.of(this::openUpdatePage)
            ),
            this::updateHintStillWorthShowing
        );
        logger.info("UPDATE_HINT_SENT id=" + UPDATE_HINT_ID + " identity=" + identity);
    }

    /**
     * Whether the hint the user is reading should stay up.
     *
     * <p>An update that is still offered obviously stays. A check in flight also stays: it has not
     * disproved the update yet, and dropping the message for the second or two a manual check takes
     * would make it flicker away and come straight back. Any settled non-update result clears it.</p>
     *
     * <p>This is the watch's own condition, so it also has to retire our bookkeeping when it says
     * "no": once the watch dismisses the hint we no longer hold a live one, and a later update must
     * be able to show a new hint.</p>
     */
    private boolean updateHintStillWorthShowing() {
        boolean keep = false;
        if (!closed) {
            try {
                final CoreUpdateService.Status status = services.update().snapshot().status();
                keep = status == CoreUpdateService.Status.UPDATE_AVAILABLE
                    || status == CoreUpdateService.Status.CHECKING;
            } catch (RuntimeException unavailable) {
                keep = false;
            }
        }
        if (!keep) {
            updateHint = null;
            updateHintIdentity = null;
        }
        return keep;
    }

    private void closeUpdateHint() {
        final Registration hint = updateHint;
        updateHint = null;
        updateHintIdentity = null;
        if (hint == null) return;
        try {
            hint.close();
        } catch (RuntimeException failure) {
            logger.warn("Update canvas hint cleanup failed safely");
        }
    }

    /** One-shot, click-to-dismiss result of a check the user asked for. */
    private void showCheckResultHint(final CoreUpdateService.Snapshot snapshot) {
        final String message = switch (snapshot.status()) {
            case UP_TO_DATE -> format(
                "updates.up-to-date",
                "Turboism " + snapshot.localVersion() + " is up to date.",
                snapshot.localVersion()
            );
            case UNAVAILABLE -> localized(
                "updates.unavailable", "Turboism updates are currently unavailable."
            );
            default -> null;
        };
        if (message == null) return;
        context.disposableScope().register(context.uiHost().notifyDismissibleCanvasHint(
            new CanvasHintNotification(
                CHECK_RESULT_HINT_ID,
                message,
                CHECK_RESULT_HINT_SECONDS
            )
        ));
        logger.info("UPDATE_CHECK_RESULT_HINT_SENT status=" + snapshot.status());
    }

    private boolean isDeliverable(
        final CoreUpdateService.Snapshot snapshot,
        final long expectedUiGeneration
    ) {
        if (closed || updateUiGeneration.get() != expectedUiGeneration) return false;
        try {
            return services.update().snapshot() == snapshot;
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    private void openUpdatePage() {
        if (!windows.openUpdateDownloadPage()) {
            logger.warn("Update download page could not be opened");
        }
    }

    private String format(final String key, final String fallback, final Object... arguments) {
        try {
            final String value = localization(context).format(key, arguments);
            return key.equals(value) ? fallback : value;
        } catch (RuntimeException unavailable) {
            return fallback;
        }
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
