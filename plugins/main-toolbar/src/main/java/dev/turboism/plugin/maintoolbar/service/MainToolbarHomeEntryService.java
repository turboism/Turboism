package dev.turboism.plugin.maintoolbar.service;

import dev.turboism.sdk.cubism.ProjectSnapshot;
import dev.turboism.sdk.cubism.WorkspaceSnapshot;
import dev.turboism.sdk.cubism.service.read.CubismReadCapabilityService;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.ui.StatusNotification;
import dev.turboism.sdk.ui.UiHostCapabilityService;
import dev.turboism.sdk.ui.toolbar.MainToolbarRegistry;

import java.util.Objects;
import java.util.Optional;

public final class MainToolbarHomeEntryService {

    public static final String ACTION_ID = "main-toolbar.home-entry.open";
    public static final String ACTION_LABEL = "Open Turboism Home";

    private static final String CONTRIBUTION_ID = "main-toolbar.home-entry";
    private static final String LABEL_KEY = "main-toolbar.home-entry.label";
    private static final String ICON_RESOURCE_PATH = "icons/main-toolbar-home.svg";
    private static final String ANCHOR = "start";
    private static final int ORDER = 10;
    private static final String PROJECT_SUMMARY_NOTIFICATION_ID = "main-toolbar.home-entry.project-summary";
    private static final String NO_PROJECT_NOTIFICATION_ID = "main-toolbar.home-entry.no-project";

    private final CubismReadCapabilityService cubismRead;
    private final UiHostCapabilityService uiHost;

    public MainToolbarHomeEntryService(
        final CubismReadCapabilityService cubismRead,
        final UiHostCapabilityService uiHost
    ) {
        this.cubismRead = Objects.requireNonNull(cubismRead, "cubismRead");
        this.uiHost = Objects.requireNonNull(uiHost, "uiHost");
    }

    public Registration registerHomeEntry() {
        return uiHost.contributeMainToolbar(new MainToolbarRegistry.MainToolbarContribution(
            CONTRIBUTION_ID,
            ACTION_ID,
            LABEL_KEY,
            ICON_RESOURCE_PATH,
            ANCHOR,
            ORDER
        ));
    }

    public void showProjectSummary() {
        final Optional<ProjectSnapshot> activeProject = cubismRead.activeProject();
        if (activeProject.isEmpty()) {
            uiHost.notifyStatus(new StatusNotification(
                NO_PROJECT_NOTIFICATION_ID,
                "WARNING",
                "No active project is available for the Turboism home entry."
            ));
            return;
        }

        final ProjectSnapshot project = activeProject.orElseThrow();
        final String workspaceSummary = cubismRead.workspace()
            .map(this::workspaceSummary)
            .orElse("workspace unavailable");
        uiHost.notifyStatus(new StatusNotification(
            PROJECT_SUMMARY_NOTIFICATION_ID,
            "INFO",
            "Project " + project.name() + " has " + project.documents().size()
                + " document(s); " + workspaceSummary + "."
        ));
    }

    private String workspaceSummary(final WorkspaceSnapshot workspace) {
        return "layout workspace " + workspace.displayName();
    }
}
