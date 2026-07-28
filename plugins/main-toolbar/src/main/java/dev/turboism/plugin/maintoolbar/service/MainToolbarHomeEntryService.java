package dev.turboism.plugin.maintoolbar.service;

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
    private static final String ICON_RESOURCE_PATH = "icons/main-toolbar-home.png";
    private static final String PANEL_TITLE = "Turboism";
    private static final String PANEL_PLACEMENT = "right";
    private static final int PANEL_PRIORITY = 0;
    private static final int ORDER = 10;

    private final UiHostCapabilityService uiHost;
    private final MainToolbarRegistry mainToolbar;

    public MainToolbarHomeEntryService(
        final UiHostCapabilityService uiHost,
        final MainToolbarRegistry mainToolbar
    ) {
        this.uiHost = Objects.requireNonNull(uiHost, "uiHost");
        this.mainToolbar = Objects.requireNonNull(mainToolbar, "mainToolbar");
    }

    public Registration registerTurboismPanel() {
        return uiHost.contributeEmbeddedPanel(new EmbeddedPanelContribution(
            TURBOISM_PANEL_ID.value(),
            PANEL_TITLE,
            PANEL_PLACEMENT,
            PANEL_PRIORITY
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

    public void openTurboismPanel() {
        uiHost.activateEmbeddedPanel(TURBOISM_PANEL_ID);
    }
}
