package dev.turboism.plugin.maintoolbar.service;

import dev.turboism.sdk.i18n.PluginLocalization;
import dev.turboism.sdk.menu.MenuRegistry;
import dev.turboism.sdk.runtime.RuntimeSettings;
import dev.turboism.sdk.runtime.RuntimeSettingsService;
import dev.turboism.sdk.ui.PanelView;

import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.ui.EmbeddedPanelContribution;
import dev.turboism.sdk.ui.EmbeddedPanelId;
import dev.turboism.sdk.ui.UiHostCapabilityService;
import dev.turboism.sdk.ui.toolbar.MainToolbarRegistry;

import java.util.Objects;
import java.util.Optional;

public final class MainToolbarHomeEntryService {

    public static final String ACTION_ID = "main-toolbar.home-entry.open";
    public static final String ACTION_LABEL = "Open Turboism settings";
    public static final EmbeddedPanelId TURBOISM_PANEL_ID = EmbeddedPanelId.of("turboism.panel.main");

    private static final String CONTRIBUTION_ID = "main-toolbar.home-entry";
    private static final String LABEL_KEY = "main-toolbar.home.aria-label";
    private static final String TOOLTIP_KEY = "main-toolbar.home.tooltip";
    private static final String SETTINGS_MENU_LABEL_KEY = "main-toolbar.settings-menu.label";
    private static final String TURBOISM_MENU_ROOT = "Turboism";
    private static final String ICON_RESOURCE_PATH = "icons/main-toolbar-home.png";
    private static final String PANEL_TITLE = "Turboism";
    private static final String PANEL_PLACEMENT = "right";
    private static final int PANEL_PRIORITY = 0;
    private static final int ORDER = 10;

    private final UiHostCapabilityService uiHost;
    private final MainToolbarRegistry mainToolbar;
    private final MenuRegistry menus;
    private final PluginLocalization localization;
    private final RuntimeSettingsService runtimeSettings;

    public MainToolbarHomeEntryService(
        final UiHostCapabilityService uiHost,
        final MainToolbarRegistry mainToolbar,
        final MenuRegistry menus,
        final PluginLocalization localization
    ) {
        this(uiHost, mainToolbar, menus, localization, new RuntimeSettingsService() {
            @Override public RuntimeSettings read() {
                return new RuntimeSettings(false, "INFO", false, false, false);
            }
            @Override public RuntimeSettings save(final RuntimeSettings settings) { return settings; }
            @Override public DockCleanupResult cleanEmptyDocks() {
                return new DockCleanupResult("Empty dock cleanup completed.");
            }
        });
    }

    public MainToolbarHomeEntryService(
        final UiHostCapabilityService uiHost,
        final MainToolbarRegistry mainToolbar,
        final MenuRegistry menus,
        final PluginLocalization localization,
        final RuntimeSettingsService runtimeSettings
    ) {
        this.uiHost = Objects.requireNonNull(uiHost, "uiHost");
        this.mainToolbar = Objects.requireNonNull(mainToolbar, "mainToolbar");
        this.menus = Objects.requireNonNull(menus, "menus");
        this.localization = Objects.requireNonNull(localization, "localization");
        this.runtimeSettings = Objects.requireNonNull(runtimeSettings, "runtimeSettings");
    }

    public Registration registerTurboismPanel() {
        final RuntimeSettings settings = runtimeSettings.read();
        return uiHost.contributeEmbeddedPanel(new EmbeddedPanelContribution(
            TURBOISM_PANEL_ID.value(),
            PANEL_TITLE,
            PANEL_PLACEMENT,
            PANEL_PRIORITY,
            settingsView(settings)
        ));
    }

    public Registration registerHomeEntry() {
        return mainToolbar.contributeButton(
            new MainToolbarRegistry.MainToolbarButtonContribution(
                CONTRIBUTION_ID,
                ACTION_ID,
                LABEL_KEY,
                TOOLTIP_KEY,
                new MainToolbarRegistry.IconVariants(
                    ICON_RESOURCE_PATH,
                    Optional.of("icons/main-toolbar-home-hover.png"),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty()
                ),
                MainToolbarRegistry.Placement.after(MainToolbarRegistry.Anchor.HOST_HOME_ENTRY),
                ORDER
            )
        );
    }

    public Registration registerSettingsMenu() {
        final String menuPath = TURBOISM_MENU_ROOT + "/" + localization.text(SETTINGS_MENU_LABEL_KEY);
        return menus.contribute(new MenuRegistry.MenuContribution() {
            @Override
            public String menuPath() {
                return menuPath;
            }

            @Override
            public String actionId() {
                return ACTION_ID;
            }

            @Override
            public int order() {
                return ORDER;
            }
        });
    }

    public void openTurboismPanel() {
        uiHost.activateEmbeddedPanel(TURBOISM_PANEL_ID);
    }


    private static PanelView settingsView(final RuntimeSettings settings) {
        return PanelView.column(
            PanelView.text("Runtime"),
            PanelView.toggle("safe-mode", "Safe Mode", settings.safeMode(), "settings.safe-mode"),
            PanelView.select(
                "log-level", "Log level",
                java.util.List.of(
                    PanelView.option("DEBUG", "Debug"), PanelView.option("INFO", "Info"),
                    PanelView.option("WARN", "Warn"), PanelView.option("ERROR", "Error")
                ),
                settings.logLevel(), "settings.log-level"
            ),
            PanelView.separator(),
            PanelView.text("Startup (restart required)"),
            PanelView.toggle("skip-update", "Skip update check", settings.skipStartupUpdateCheck(), "settings.skip-update"),
            PanelView.toggle("skip-splash", "Skip splash", settings.skipStartupSplash(), "settings.skip-splash"),
            PanelView.toggle("skip-information", "Skip startup information", settings.skipStartupInformation(), "settings.skip-information"),
            PanelView.button("save-settings", "Save settings", "settings.save"),
            PanelView.separator(),
            PanelView.text("Dock maintenance"),
            PanelView.button("clean-empty-docks", "Clean empty docks", "settings.clean-empty-docks")
        );
    }
}
