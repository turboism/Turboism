package dev.turboism.plugin.logfilter.service;

import dev.turboism.sdk.plugin.Registration;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;

class LogFilterPaletteServiceTest {

    @Test
    void packagedToolbarIconExists() {
        assertNotNull(LogFilterPaletteService.class.getResource("/icons/log-filter-toggle.svg"));
    }

    @Test
    void registerPaletteToolbar_contributesToggleButtonToLogPalette_whenPaletteToolbarAvailable() {
        // Given
        RecordingUiHost uiHost = new RecordingUiHost(true);
        LogFilterPaletteService service = new LogFilterPaletteService(uiHost);

        // When
        service.registerPaletteToolbar();

        // Then
        assertEquals(
            List.of(new PaletteToolbarRegistry.PaletteToolbarContribution(
                "log-filter.toggle-level",
                "log-filter.toggle-level",
                "log-filter.toggle-level.label",
                "icons/log-filter-toggle.svg",
                "LOG",
                "end",
                100
            )),
            uiHost.paletteToolbarContributions()
        );
        assertEquals(List.of(), uiHost.notifications());
    }

    @Test
    void toggleFilterLevel_cyclesLevelsAndEmitsStatusNotification_whenInvokedRepeatedly() {
        // Given
        RecordingUiHost uiHost = new RecordingUiHost(true);
        LogFilterPaletteService service = new LogFilterPaletteService(uiHost);

        // When
        service.toggleFilterLevel();
        service.toggleFilterLevel();
        service.toggleFilterLevel();

        // Then
        assertEquals(
            List.of(
                new StatusNotification(
                    "log-filter.level.changed",
                    "INFO",
                    "Log filter level changed to WARNING"
                ),
                new StatusNotification(
                    "log-filter.level.changed",
                    "INFO",
                    "Log filter level changed to ERROR"
                ),
                new StatusNotification(
                    "log-filter.level.changed",
                    "INFO",
                    "Log filter level changed to INFO"
                )
            ),
            uiHost.notifications()
        );
    }

    @Test
    void registerPaletteToolbar_emitsFallbackNotification_whenPaletteToolbarUnavailable() {
        // Given
        RecordingUiHost uiHost = new RecordingUiHost(false);
        LogFilterPaletteService service = new LogFilterPaletteService(uiHost);

        // When
        service.registerPaletteToolbar();

        // Then
        assertEquals(List.of(), uiHost.paletteToolbarContributions());
        assertEquals(
            List.of(new StatusNotification(
                "log-filter.palette-toolbar.unavailable",
                "WARNING",
                "Log filter palette toolbar is unavailable; use the fallback toggle action."
            )),
            uiHost.notifications()
        );
    }

    private static final class RecordingUiHost implements UiHostCapabilityService {
        private final boolean paletteToolbarAvailable;
        private final List<PaletteToolbarRegistry.PaletteToolbarContribution> paletteToolbarContributions = new ArrayList<>();
        private final List<StatusNotification> notifications = new ArrayList<>();

        RecordingUiHost(final boolean paletteToolbarAvailable) {
            this.paletteToolbarAvailable = paletteToolbarAvailable;
        }

        List<PaletteToolbarRegistry.PaletteToolbarContribution> paletteToolbarContributions() {
            return paletteToolbarContributions;
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
            throw new UnsupportedOperationException("dialogs are not used by this service");
        }

        @Override
        public boolean confirmDialog(final DialogRequest request) {
            throw new UnsupportedOperationException("dialogs are not used by this service");
        }

        @Override
        public Registration contributeEmbeddedPanel(final EmbeddedPanelContribution contribution) {
            throw new UnsupportedOperationException("embedded panels are not used by this service");
        }

        @Override
        public Optional<String> requestFile(final FileChooserRequest request) {
            throw new UnsupportedOperationException("file requests are not used by this service");
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
            if (!paletteToolbarAvailable) {
                throw new UnsupportedOperationException("palette toolbar is unavailable");
            }
            paletteToolbarContributions.add(contribution);
            return () -> paletteToolbarContributions.remove(contribution);
        }
    }
}
