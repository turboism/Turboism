package dev.turboism.test.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.ui.DialogRequest;
import dev.turboism.sdk.ui.EmbeddedPanelContribution;
import dev.turboism.sdk.ui.FileChooserRequest;
import dev.turboism.sdk.ui.StatusNotification;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RecordingUiHostTest {

    private RecordingUiHost uiHost;

    @BeforeEach
    void setUp() {
        uiHost = new RecordingUiHost();
    }

    @Test
    void notifyStatusRecordsAndDismisses() {
        final StatusNotification notification = new StatusNotification("id-1", "INFO", "Ready");
        final Registration handle = uiHost.notifyStatus(notification);
        assertEquals(1, uiHost.notifications().size());
        assertEquals(Optional.of(notification), uiHost.lastNotification());

        handle.close();
        assertEquals(0, uiHost.notifications().size());
    }

    @Test
    void dialogConfirmRecordsAndReturnsConfiguredResult() {
        final DialogRequest request = new DialogRequest("confirm-1", "Confirm", "Proceed?");
        uiHost.setConfirmResult(true);
        assertTrue(uiHost.confirmDialog(request));
        assertEquals(request, uiHost.lastConfirmRequest());

        uiHost.setConfirmResult(false);
        assertFalse(uiHost.confirmDialog(request));
    }

    @Test
    void requestFileReturnsConfiguredPath() {
        final FileChooserRequest request = new FileChooserRequest("chooser-1", "Choose model", List.of("cmo3"));
        assertTrue(uiHost.requestFile(request).isEmpty());

        uiHost.setChosenFile("/path/to/model.cmo3");
        assertEquals(Optional.of("/path/to/model.cmo3"), uiHost.requestFile(request));
    }

    @Test
    void colorPickerRecordsAndFiresListener() {
        final AtomicBoolean resultAccepted = new AtomicBoolean();
        uiHost.openColorPicker("picker-1", "Pick color", "#ff0000", (accepted, hex) -> {
            resultAccepted.set(accepted);
            assertEquals("#00ff00", hex);
        });

        assertEquals(1, uiHost.colorPickers().size());
        assertEquals("picker-1", uiHost.colorPickers().get(0).id());
        uiHost.acceptColorPicker(0, "#00ff00");
        assertTrue(resultAccepted.get());
    }

    @Test
    void embeddedPanelsReplacedByIdentity() {
        final EmbeddedPanelContribution panel1 = new EmbeddedPanelContribution("panel-1", "Panel One", "main", 0);
        final EmbeddedPanelContribution panel2 =
                new EmbeddedPanelContribution("panel-1", "Panel One Updated", "main", 0);
        uiHost.contributeEmbeddedPanel(panel1);
        assertEquals(1, uiHost.embeddedPanels().size());
        assertEquals("Panel One", uiHost.embeddedPanels().get(0).title());

        uiHost.contributeEmbeddedPanel(panel2);
        assertEquals(1, uiHost.embeddedPanels().size());
        assertEquals("Panel One Updated", uiHost.embeddedPanels().get(0).title());
    }
}
