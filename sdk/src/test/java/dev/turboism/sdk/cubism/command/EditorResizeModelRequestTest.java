package dev.turboism.sdk.cubism.command;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EditorResizeModelRequestTest {
    @Test
    void validatesTheObservedPercentageShape() {
        assertEquals(100, new EditorResizeModelRequest(100).percent());
        assertEquals("resize.model.document", new EditorResizeModelRequest(100).commandId());
        assertThrows(IllegalArgumentException.class, () -> new EditorResizeModelRequest(0));
        assertThrows(IllegalArgumentException.class, () -> new EditorResizeModelRequest(5001),
            "host percentage input accepts 1..5000");
    }
}
