package dev.turboism.plugin.uitheme.service;

import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.theme.ThemeStatusSnapshot;
import dev.turboism.sdk.ui.DialogRequest;
import dev.turboism.sdk.ui.EmbeddedPanelContribution;
import dev.turboism.sdk.ui.FileChooserRequest;
import dev.turboism.sdk.ui.OverlayContribution;
import dev.turboism.sdk.ui.StatusNotification;
import dev.turboism.sdk.ui.UiHostCapabilityService;
import dev.turboism.sdk.ui.ViewportSnapshot;
import dev.turboism.sdk.ui.context.ContextMenuRegistry;
import dev.turboism.sdk.ui.context.ContextSourceSnapshot;
import dev.turboism.sdk.ui.toolbar.MainToolbarRegistry;
import dev.turboism.sdk.ui.toolbar.PaletteToolbarRegistry;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ThemePackageStatusServiceTest {

    @Test
    void checkThemeStatus_emitsInfoWithThemeDetails_whenThemeAvailable() {
        // Given
        RecordingUiHost uiHost = new RecordingUiHost(Optional.empty());
        ThemePackageStatusService service = new ThemePackageStatusService(
            new AvailableThemeStatusReader("aurora", "Aurora"),
            uiHost
        );

        // When
        service.checkThemeStatus();

        // Then
        assertEquals(
            List.of(new StatusNotification(
                "ui-theme.package.status.available",
                "INFO",
                "Theme package available: Aurora (aurora)"
            )),
            uiHost.notifications()
        );
    }

    @Test
    void checkThemeStatus_emitsWarning_whenThemeUnavailable() {
        // Given
        RecordingUiHost uiHost = new RecordingUiHost(Optional.empty());
        ThemePackageStatusService service = new ThemePackageStatusService(
            new UnavailableThemeStatusReader(),
            uiHost
        );

        // When
        service.checkThemeStatus();

        // Then
        assertEquals(
            List.of(new StatusNotification(
                "ui-theme.package.status.unavailable",
                "WARNING",
                "Theme package is not available"
            )),
            uiHost.notifications()
        );
    }

    @Test
    void handleThemePackageImport_requestsZipFileShowsConfirmationAndEmitsStarted_whenConfirmed() {
        // Given
        RecordingUiHost uiHost = new RecordingUiHost(Optional.of("themes/aurora.zip"), true);
        ThemePackageStatusService service = new ThemePackageStatusService(
            new UnavailableThemeStatusReader(),
            uiHost
        );

        // When
        service.handleThemePackageImport();

        // Then
        assertEquals(
            List.of(new FileChooserRequest(
                "ui-theme.package.import.file",
                "Import theme package",
                List.of("zip")
            )),
            uiHost.fileRequests()
        );
        assertEquals(
            List.of(new DialogRequest(
                "ui-theme.package.import.confirm",
                "Import theme package?",
                "Import theme package themes/aurora.zip?"
            )),
            uiHost.dialogs()
        );
        assertEquals(
            List.of(new StatusNotification(
                "ui-theme.package.import.started",
                "INFO",
                "Theme package import started: themes/aurora.zip"
            )),
            uiHost.notifications()
        );
    }

    @Test
    void handleThemePackageImport_doesNotConfirmOrNotify_whenNoFileSelected() {
        // Given
        RecordingUiHost uiHost = new RecordingUiHost(Optional.empty(), true);
        ThemePackageStatusService service = new ThemePackageStatusService(
            new UnavailableThemeStatusReader(),
            uiHost
        );

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
        ThemePackageStatusService service = new ThemePackageStatusService(
            new UnavailableThemeStatusReader(),
            uiHost
        );

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

    private static final class RecordingUiHost implements UiHostCapabilityService {
        private final Optional<String> selectedFile;
        private final boolean confirmResult;
        private final List<FileChooserRequest> fileRequests = new ArrayList<>();
        private final List<DialogRequest> dialogs = new ArrayList<>();
        private final List<StatusNotification> notifications = new ArrayList<>();

        RecordingUiHost(final Optional<String> selectedFile) {
            this(selectedFile, true);
        }

        RecordingUiHost(final Optional<String> selectedFile, final boolean confirmResult) {
            this.selectedFile = selectedFile;
            this.confirmResult = confirmResult;
        }

        List<FileChooserRequest> fileRequests() {
            return fileRequests;
        }

        List<DialogRequest> dialogs() {
            return dialogs;
        }

        List<StatusNotification> notifications() {
            return notifications;
        }

        @Override
        public Registration contributeOverlay(final OverlayContribution contribution) {
            throw new UnsupportedOperationException("overlay contributions are not used by this service");
        }

        @Override
        public ContextSourceSnapshot contextSource() {
            throw new UnsupportedOperationException("context source is not used by this service");
        }

        @Override
        public ViewportSnapshot viewport() {
            throw new UnsupportedOperationException("viewport is not used by this service");
        }

        @Override
        public Registration openDialog(final DialogRequest request) {
            dialogs.add(request);
            return () -> dialogs.remove(request);
        }

        @Override
        public boolean confirmDialog(final DialogRequest request) {
            dialogs.add(request);
            return confirmResult;
        }

        @Override
        public Registration contributeEmbeddedPanel(final EmbeddedPanelContribution contribution) {
            throw new UnsupportedOperationException("embedded panels are not used by this service");
        }

        @Override
        public Optional<String> requestFile(final FileChooserRequest request) {
            fileRequests.add(request);
            return selectedFile;
        }

        @Override
        public Registration notifyStatus(final StatusNotification notification) {
            notifications.add(notification);
            return () -> notifications.remove(notification);
        }

        @Override
        public Registration contributeContextMenu(final ContextMenuRegistry.ContextMenuContribution contribution) {
            throw new UnsupportedOperationException("context menus are not used by this service");
        }

        @Override
        public Registration contributeMainToolbar(final MainToolbarRegistry.MainToolbarContribution contribution) {
            throw new UnsupportedOperationException("main toolbar is not used by this service");
        }

        @Override
        public Registration contributePaletteToolbar(final PaletteToolbarRegistry.PaletteToolbarContribution contribution) {
            throw new UnsupportedOperationException("palette toolbar is not used by this service");
        }
    }
}
