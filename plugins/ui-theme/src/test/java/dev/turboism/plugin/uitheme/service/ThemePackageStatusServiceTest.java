package dev.turboism.plugin.uitheme.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.turboism.sdk.theme.ThemeStatusSnapshot;
import dev.turboism.sdk.ui.DialogRequest;
import dev.turboism.sdk.ui.FileChooserRequest;
import dev.turboism.sdk.ui.StatusNotification;
import dev.turboism.test.ui.RecordingUiHost;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ThemePackageStatusServiceTest {

    @Test
    void checkThemeStatus_emitsInfoWithThemeDetails_whenThemeAvailable() {
        // Given
        RecordingUiHost uiHost = new RecordingUiHost(Optional.empty());
        ThemePackageStatusService service =
                new ThemePackageStatusService(new AvailableThemeStatusReader("aurora", "Aurora"), uiHost);

        // When
        service.checkThemeStatus();

        // Then
        assertEquals(
                List.of(new StatusNotification(
                        "ui-theme.package.status.available", "INFO", "Theme package available: Aurora (aurora)")),
                uiHost.notifications());
    }

    @Test
    void checkThemeStatus_emitsWarning_whenThemeUnavailable() {
        // Given
        RecordingUiHost uiHost = new RecordingUiHost(Optional.empty());
        ThemePackageStatusService service = new ThemePackageStatusService(new UnavailableThemeStatusReader(), uiHost);

        // When
        service.checkThemeStatus();

        // Then
        assertEquals(
                List.of(new StatusNotification(
                        "ui-theme.package.status.unavailable", "WARNING", "Theme package is not available")),
                uiHost.notifications());
    }

    @Test
    void handleThemePackageImport_requestsZipFileShowsConfirmationAndEmitsStarted_whenConfirmed() {
        // Given
        RecordingUiHost uiHost = new RecordingUiHost(Optional.of("themes/aurora.zip"), true);
        ThemePackageStatusService service = new ThemePackageStatusService(new UnavailableThemeStatusReader(), uiHost);

        // When
        service.handleThemePackageImport();

        // Then
        assertEquals(
                List.of(new FileChooserRequest("ui-theme.package.import.file", "Import theme package", List.of("zip"))),
                uiHost.fileRequests());
        assertEquals(
                List.of(new DialogRequest(
                        "ui-theme.package.import.confirm",
                        "Import theme package?",
                        "Import theme package themes/aurora.zip?")),
                uiHost.dialogs());
        assertEquals(
                List.of(new StatusNotification(
                        "ui-theme.package.import.started", "INFO", "Theme package import started: themes/aurora.zip")),
                uiHost.notifications());
    }

    @Test
    void handleThemePackageImport_doesNotConfirmOrNotify_whenNoFileSelected() {
        // Given
        RecordingUiHost uiHost = new RecordingUiHost(Optional.empty(), true);
        ThemePackageStatusService service = new ThemePackageStatusService(new UnavailableThemeStatusReader(), uiHost);

        // When
        service.handleThemePackageImport();

        // Then
        assertEquals(1, uiHost.fileRequests().size());
        assertEquals(List.of(), uiHost.dialogs());
        assertEquals(List.of(), uiHost.notifications());
    }

    @Test
    void handleThemePackageImport_doesNotNotify_whenConfirmationDeclined() {
        // Given
        RecordingUiHost uiHost = new RecordingUiHost(Optional.of("themes/aurora.zip"), false);
        ThemePackageStatusService service = new ThemePackageStatusService(new UnavailableThemeStatusReader(), uiHost);

        // When
        service.handleThemePackageImport();

        // Then
        assertEquals(1, uiHost.dialogs().size());
        assertEquals(List.of(), uiHost.notifications());
    }

    private record AvailableThemeStatusReader(String themeId, String displayName)
            implements ThemePackageStatusService.ThemeStatusReadCapability {

        @Override
        public Optional<ThemeStatusSnapshot> readStatus() {
            return Optional.of(new ThemeStatusSnapshot(themeId, displayName, true));
        }
    }

    private record UnavailableThemeStatusReader() implements ThemePackageStatusService.ThemeStatusReadCapability {
        @Override
        public Optional<ThemeStatusSnapshot> readStatus() {
            return Optional.empty();
        }
    }
}
