package dev.turboism.plugin.uitheme.service;

import dev.turboism.sdk.theme.ThemeStatusSnapshot;
import dev.turboism.sdk.ui.DialogRequest;
import dev.turboism.sdk.ui.FileChooserRequest;
import dev.turboism.sdk.ui.StatusNotification;
import dev.turboism.sdk.ui.UiHostCapabilityService;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Reports the host's theme-package status and drives the user-facing import prompt.
 *
 * <p>Read-only with respect to themes: it reads the host status snapshot and asks for a file and a
 * confirmation, but installs nothing itself. The capability id constants name the permissions this
 * service's operations require of the host.
 */
public final class ThemePackageStatusService {

    public static final String THEME_STATUS_READ_CAPABILITY = "cubism.theme.status.read";
    public static final String UI_DIALOG_CONTRIBUTE_CAPABILITY = "ui.dialog.contribute";
    public static final String UI_FILE_CHOOSER_REQUEST_CAPABILITY = "ui.file-chooser.request";
    public static final String UI_STATUS_NOTIFY_CAPABILITY = "ui.status.notify";

    private final ThemeStatusReadCapability statusReadCapability;
    private final UiHostCapabilityService uiHost;

    public ThemePackageStatusService(
        final ThemeStatusReadCapability statusReadCapability,
        final UiHostCapabilityService uiHost
    ) {
        this.statusReadCapability = Objects.requireNonNull(statusReadCapability, "statusReadCapability");
        this.uiHost = Objects.requireNonNull(uiHost, "uiHost");
    }

    /**
     * Reads the host theme status and posts it as a status notification.
     *
     * <p>An absent snapshot is normal on a host without theme support and is reported as a
     * {@code WARNING} notification rather than thrown.
     */
    public void checkThemeStatus() {
        final Optional<ThemeStatusSnapshot> status = statusReadCapability.readStatus();
        if (status.isPresent()) {
            final ThemeStatusSnapshot snapshot = status.orElseThrow();
            uiHost.notifyStatus(new StatusNotification(
                "ui-theme.package.status.available",
                "INFO",
                "Theme package available: " + snapshot.displayName() + " (" + snapshot.themeId() + ")"
            ));
            return;
        }
        uiHost.notifyStatus(new StatusNotification(
            "ui-theme.package.status.unavailable",
            "WARNING",
            "Theme package is not available"
        ));
    }

    /**
     * Prompts for a {@code .zip} theme package and, once the user confirms the overwrite dialog, posts
     * an import-started notification.
     *
     * <p>Announces the intent only: this method reads no bytes and installs nothing. Cancelling either
     * the file chooser or the confirmation leaves no notification and no effect.
     */
    public void handleThemePackageImport() {
        uiHost.requestFile(new FileChooserRequest(
            "ui-theme.package.import.file",
            "Import theme package",
            List.of("zip")
        )).filter(this::confirmImport)
            .ifPresent(packagePath -> uiHost.notifyStatus(new StatusNotification(
                "ui-theme.package.import.started",
                "INFO",
                "Theme package import started: " + packagePath
            )));
    }

    private boolean confirmImport(final String packagePath) {
        return uiHost.confirmDialog(new DialogRequest(
            "ui-theme.package.import.confirm",
            "Import theme package?",
            "Import theme package " + packagePath + "?"
        ));
    }

    public interface ThemeStatusReadCapability {
        Optional<ThemeStatusSnapshot> readStatus();
    }
}
