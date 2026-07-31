package dev.turboism.sdk.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EmbeddedPanelIdContractTest {

    @Test
    void ownsValidatedPanelIdentityForActivationRequests() {
        EmbeddedPanelId panelId = EmbeddedPanelId.of("turboism.panel.main");

        assertEquals("turboism.panel.main", panelId.value());
        assertEquals(panelId, new EmbeddedPanelId("turboism.panel.main"));
        assertThrows(IllegalArgumentException.class, () -> EmbeddedPanelId.of(" "));
    }
}
