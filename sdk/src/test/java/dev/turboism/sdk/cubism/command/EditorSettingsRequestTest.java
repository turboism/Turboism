package dev.turboism.sdk.cubism.command;

import dev.turboism.sdk.cubism.model.Color;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EditorSettingsRequestTest {
    @Test
    void validatesGridSpacingAndOpaqueColor() {
        EditorGridSettingsRequest request = new EditorGridSettingsRequest(
            50, new Color(0.5f, 0.5f, 0.5f, 1.0f)
        );
        assertEquals("grid.setting", request.commandId());
        assertThrows(IllegalArgumentException.class,
            () -> new EditorGridSettingsRequest(0, new Color(0, 0, 0, 1)),
            "host slider minimum is 5; 0 is rejected");
        assertThrows(IllegalArgumentException.class,
            () -> new EditorGridSettingsRequest(30_001, new Color(0, 0, 0, 1)),
            "host documents clamp at 30000 pixels; 30001 is rejected");
        assertThrows(IllegalArgumentException.class,
            () -> new EditorGridSettingsRequest(50, new Color(2, 0, 0, 1)),
            "unit-range color channel rejected");
        assertThrows(IllegalArgumentException.class,
            () -> new EditorGridSettingsRequest(50, new Color(0.5f, 0.5f, 0.5f, 0.5f)),
            "host grid color is opaque RGB; alpha must be 1");
    }

    @Test
    void validatesCanvasDimensionsAgainstTheHostDialogBounds() {
        assertEquals("model.setting", new EditorCanvasSettingsRequest(1000, 1000).commandId());
        assertEquals(30_000, new EditorCanvasSettingsRequest(30_000, 30_000).widthPixels());
        assertThrows(IllegalArgumentException.class, () -> new EditorCanvasSettingsRequest(15, 1000),
            "host dialog rejects widths below 16");
        assertThrows(IllegalArgumentException.class, () -> new EditorCanvasSettingsRequest(1000, 15),
            "host dialog rejects heights below 16");
        assertThrows(IllegalArgumentException.class, () -> new EditorCanvasSettingsRequest(30_001, 1000),
            "host dialog clamps at 30000; larger values are rejected");
    }
}
