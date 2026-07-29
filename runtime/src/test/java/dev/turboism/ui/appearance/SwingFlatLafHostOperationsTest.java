package dev.turboism.ui.appearance;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import javax.swing.UIManager;
import java.awt.Color;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class SwingFlatLafHostOperationsTest {

    @AfterEach
    void clear() {
        UIManager.getDefaults().remove("CubismCommon.blue");
        UIManager.getDefaults().remove("Panel.background");
    }

    @Test
    void capturesReplacesAndRemovesOnlyOwnedDefaults() {
        UIManager.put("CubismCommon.blue", new Color(1, 2, 3));
        UIManager.put("Panel.background", "native-panel");
        SwingFlatLafHostOperations host = new SwingFlatLafHostOperations(getClass().getClassLoader());

        Map<String, String> captured = host.capture();
        host.replace(Map.of("CubismCommon.blue", "#112233"));

        assertEquals("#010203", captured.get("CubismCommon.blue"));
        assertEquals("native-panel", captured.get("Panel.background"));
        assertInstanceOf(Color.class, UIManager.getDefaults().get("CubismCommon.blue"));
        assertFalse(UIManager.getDefaults().containsKey("Panel.background"));
    }
}
