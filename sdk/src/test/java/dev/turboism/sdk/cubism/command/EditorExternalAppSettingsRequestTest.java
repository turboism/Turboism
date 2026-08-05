package dev.turboism.sdk.cubism.command;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EditorExternalAppSettingsRequestTest {
    @Test
    void validatesPortAndKeepsRemoteAccessExplicit() {
        EditorExternalAppSettingsRequest request = new EditorExternalAppSettingsRequest(22033, false);
        assertEquals(EditorParameterizedCommand.EXTERNAL_APP_SETTING, request.command());
        assertEquals("external.app.setting", request.commandId());
        assertThrows(IllegalArgumentException.class,
            () -> new EditorExternalAppSettingsRequest(0, false));
        assertThrows(IllegalArgumentException.class,
            () -> new EditorExternalAppSettingsRequest(65_536, true));
    }
}
