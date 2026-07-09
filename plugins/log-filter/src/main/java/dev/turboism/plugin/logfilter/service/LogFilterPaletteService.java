package dev.turboism.plugin.logfilter.service;

import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.ui.StatusNotification;
import dev.turboism.sdk.ui.UiHostCapabilityService;
import dev.turboism.sdk.ui.toolbar.PaletteToolbarRegistry;

import java.util.Objects;

public final class LogFilterPaletteService {

    private static final String CONTRIBUTION_ID = "log-filter.toggle-level";
    private static final String ACTION_ID = "log-filter.toggle-level";
    private static final String LABEL_KEY = "log-filter.toggle-level.label";
    private static final String ICON_RESOURCE_PATH = "icons/log-filter-toggle.svg";
    private static final String LOG_PALETTE_ID = "LOG";
    private static final String ANCHOR = "end";
    private static final int ORDER = 100;
    private static final String LEVEL_CHANGED_NOTIFICATION_ID = "log-filter.level.changed";
    private static final String PALETTE_TOOLBAR_UNAVAILABLE_NOTIFICATION_ID = "log-filter.palette-toolbar.unavailable";

    private final UiHostCapabilityService uiHost;
    private FilterLevel currentLevel = FilterLevel.INFO;

    public LogFilterPaletteService(final UiHostCapabilityService uiHost) {
        this.uiHost = Objects.requireNonNull(uiHost, "uiHost");
    }

    public Registration registerPaletteToolbar() {
        try {
            return uiHost.contributePaletteToolbar(new PaletteToolbarRegistry.PaletteToolbarContribution(
                CONTRIBUTION_ID,
                ACTION_ID,
                LABEL_KEY,
                ICON_RESOURCE_PATH,
                LOG_PALETTE_ID,
                ANCHOR,
                ORDER
            ));
        } catch (UnsupportedOperationException error) {
            uiHost.notifyStatus(new StatusNotification(
                PALETTE_TOOLBAR_UNAVAILABLE_NOTIFICATION_ID,
                "WARNING",
                "Log filter palette toolbar is unavailable; use the fallback toggle action."
            ));
            return () -> { };
        }
    }

    public void toggleFilterLevel() {
        currentLevel = currentLevel.next();
        uiHost.notifyStatus(new StatusNotification(
            LEVEL_CHANGED_NOTIFICATION_ID,
            "INFO",
            "Log filter level changed to " + currentLevel.label()
        ));
    }

    private enum FilterLevel {
        INFO("INFO"),
        WARNING("WARNING"),
        ERROR("ERROR");

        private final String label;

        FilterLevel(final String label) {
            this.label = label;
        }

        String label() {
            return label;
        }

        FilterLevel next() {
            return switch (this) {
                case INFO -> WARNING;
                case WARNING -> ERROR;
                case ERROR -> INFO;
            };
        }
    }
}
