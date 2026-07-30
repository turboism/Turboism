package dev.turboism.plugin.core.service;

import dev.turboism.sdk.i18n.PluginLocalization;
import dev.turboism.sdk.menu.MenuRegistry;
import dev.turboism.plugin.core.CorePluginManagement;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.runtime.RuntimeSettings;
import dev.turboism.sdk.runtime.RuntimeSettingsService;
import dev.turboism.sdk.ui.EmbeddedPanelContribution;
import dev.turboism.sdk.ui.EmbeddedPanelId;
import dev.turboism.sdk.ui.PanelView;
import dev.turboism.sdk.ui.UiHostCapabilityService;
import dev.turboism.sdk.ui.toolbar.MainToolbarRegistry;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class MainToolbarHomeEntryService {

    public static final String ACTION_ID = "turboism.core.open";
    public static final String ACTION_LABEL = "Open Turboism";
    public static final String INSTALL_ACTION_ID = "turboism.core.plugins.install";
    public static final EmbeddedPanelId TURBOISM_PANEL_ID = EmbeddedPanelId.of("turboism.panel.main");

    private static final String CONTRIBUTION_ID = "turboism.core.home-entry";
    private static final String LABEL_KEY = "main-toolbar.home.aria-label";
    private static final String TOOLTIP_KEY = "main-toolbar.home.tooltip";
    private static final String SETTINGS_MENU_LABEL_KEY = "main-toolbar.settings-menu.label";
    private static final String PLUGINS_MENU_LABEL_KEY = "main-toolbar.plugins-menu.label";
    private static final String TURBOISM_MENU_ROOT = "Turboism";
    private static final String ICON_RESOURCE_PATH = "icons/main-toolbar-home.png";
    private static final int ORDER = 10;

    private final UiHostCapabilityService uiHost;
    private final MainToolbarRegistry mainToolbar;
    private final MenuRegistry menus;
    private final PluginLocalization localization;
    private final RuntimeSettingsService runtimeSettings;
    private final CorePluginManagement plugins;

    public MainToolbarHomeEntryService(
        final UiHostCapabilityService uiHost,
        final MainToolbarRegistry mainToolbar,
        final MenuRegistry menus,
        final PluginLocalization localization
    ) {
        this(
            uiHost, mainToolbar, menus, localization,
            new RuntimeSettingsService() {
                private RuntimeSettings settings = new RuntimeSettings(false, "INFO", false, false, false);
                @Override public RuntimeSettings read() { return settings; }
                @Override public RuntimeSettings save(final RuntimeSettings value) { settings = value; return value; }
                @Override public DockCleanupResult cleanEmptyDocks() {
                    return new DockCleanupResult("Empty dock cleanup completed.");
                }
            },
            new CorePluginManagement() {
                @Override public List<PluginInfo> plugins() { return List.of(); }
                @Override public OperationResult install() { return OperationResult.rejected("Unavailable"); }
                @Override public OperationResult uninstall(final String id) { return OperationResult.rejected("Unavailable"); }
                @Override public OperationResult setEnabled(final String id, final boolean enabled) {
                    return OperationResult.rejected("Unavailable");
                }
            }
        );
    }
    public MainToolbarHomeEntryService(
        final UiHostCapabilityService uiHost,
        final MainToolbarRegistry mainToolbar,
        final MenuRegistry menus,
        final PluginLocalization localization,
        final RuntimeSettingsService runtimeSettings,
        final CorePluginManagement plugins
    ) {
        this.uiHost = Objects.requireNonNull(uiHost, "uiHost");
        this.mainToolbar = Objects.requireNonNull(mainToolbar, "mainToolbar");
        this.menus = Objects.requireNonNull(menus, "menus");
        this.localization = Objects.requireNonNull(localization, "localization");
        this.runtimeSettings = Objects.requireNonNull(runtimeSettings, "runtimeSettings");
        this.plugins = Objects.requireNonNull(plugins, "plugins");
    }

    public Registration registerTurboismPanel() {
        return uiHost.contributeEmbeddedPanel(new EmbeddedPanelContribution(
            TURBOISM_PANEL_ID.value(), "Turboism", "right", 0,
            panelView(runtimeSettings.read(), plugins.plugins())
        ));
    }

    public Registration registerHomeEntry() {
        return mainToolbar.contributeButton(new MainToolbarRegistry.MainToolbarButtonContribution(
            CONTRIBUTION_ID, ACTION_ID, LABEL_KEY, TOOLTIP_KEY,
            new MainToolbarRegistry.IconVariants(
                ICON_RESOURCE_PATH, Optional.of("icons/main-toolbar-home-hover.png"),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()
            ),
            MainToolbarRegistry.Placement.after(MainToolbarRegistry.Anchor.HOST_HOME_ENTRY), ORDER
        ));
    }

    public Registration registerSettingsMenu() {
        return menu(localization.text(SETTINGS_MENU_LABEL_KEY), ORDER);
    }

    public Registration registerPluginManagementMenu() {
        return menu(localization.text(PLUGINS_MENU_LABEL_KEY), ORDER + 1);
    }

    private Registration menu(final String label, final int order) {
        final String menuPath = TURBOISM_MENU_ROOT + "/" + label;
        return menus.contribute(new MenuRegistry.MenuContribution() {
            @Override public String menuPath() { return menuPath; }
            @Override public String actionId() { return ACTION_ID; }
            @Override public int order() { return order; }
        });
    }

    public void openTurboismPanel() {
        uiHost.activateEmbeddedPanel(TURBOISM_PANEL_ID);
    }

    private static PanelView panelView(
        final RuntimeSettings settings,
        final List<CorePluginManagement.PluginInfo> plugins
    ) {
        final List<PanelView> content = new ArrayList<>();
        content.add(PanelView.text("Runtime settings"));
        content.add(PanelView.toggle("safe-mode", "Safe Mode", settings.safeMode(), "settings.safe-mode"));
        content.add(PanelView.select(
            "log-level", "Log level",
            List.of(
                PanelView.option("DEBUG", "Debug"), PanelView.option("INFO", "Info"),
                PanelView.option("WARN", "Warn"), PanelView.option("ERROR", "Error")
            ),
            settings.logLevel(), "settings.log-level"
        ));
        content.add(PanelView.toggle("skip-update", "Skip update check", settings.skipStartupUpdateCheck(), "settings.skip-update"));
        content.add(PanelView.toggle("skip-splash", "Skip splash", settings.skipStartupSplash(), "settings.skip-splash"));
        content.add(PanelView.toggle("skip-information", "Skip startup information", settings.skipStartupInformation(), "settings.skip-information"));
        content.add(PanelView.row(
            PanelView.button("save-settings", "Save settings", "settings.save"),
            PanelView.button("clean-empty-docks", "Clean empty docks", "settings.clean-empty-docks")
        ));
        content.add(PanelView.separator());
        content.add(PanelView.text("Plugin management — changes apply after restarting Cubism"));
        content.add(PanelView.button("install-plugin", "Install plugin…", INSTALL_ACTION_ID));
        for (CorePluginManagement.PluginInfo plugin : plugins) {
            content.add(PanelView.text(
                plugin.name() + " " + plugin.version() + " [effective=" + plugin.effectiveState()
                    + ", desired=" + plugin.desiredState() + "]"
                    + plugin.pendingOperation().map(value -> " pending=" + value).orElse("") + "\n"
                    + plugin.id() + (plugin.description().isBlank() ? "" : " — " + plugin.description())
            ));
            if (!plugin.core()) {
                content.add(PanelView.row(
                    PanelView.button(
                        "toggle-" + plugin.id(), plugin.desiredState().equals("ENABLED") ? "Disable" : "Enable",
                        (plugin.desiredState().equals("ENABLED") ? "turboism.core.plugins.disable." : "turboism.core.plugins.enable.") + plugin.id()
                    ),
                    PanelView.button(
                        "uninstall-" + plugin.id(), "Uninstall",
                        "turboism.core.plugins.uninstall." + plugin.id()
                    )
                ));
            }
        }
        return new PanelView.Column(content);
    }
}
