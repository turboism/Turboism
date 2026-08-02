package dev.turboism.ui.appearance;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import javax.swing.UIManager;
import java.awt.Color;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SwingFlatLafHostOperationsTest {

    @AfterEach
    void clear() {
        UIManager.getDefaults().remove("CubismCommon.blue");
        UIManager.getDefaults().remove("CubismCommon.gl.viewArea.background");
        UIManager.getDefaults().remove("Turboism.native.CubismCommon.gl.viewArea.background");
        ThemeRuntimeProperties.delete();
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

    @Test
    void restoreNativeReinstatesTheOffCanvasDefaultCapturedBeforeThemeInjection() {
        UIManager.put("CubismCommon.gl.viewArea.background", new Color(222, 223, 224));
        SwingFlatLafHostOperations.captureNativeOffCanvasBackground();
        SwingFlatLafHostOperations host = new SwingFlatLafHostOperations(getClass().getClassLoader());

        host.replace(Map.of("CubismCommon.gl.viewArea.background", "#F0E0E5"));
        SwingFlatLafHostOperations.captureNativeOffCanvasBackground();
        try {
            host.restoreNative();
            org.junit.jupiter.api.Assertions.fail("expected FlatLaf failure outside the host");
        } catch (IllegalStateException expected) {
            // The unit environment has no com.formdev.flatlaf.FlatLaf class.
        }

        assertEquals(new Color(222, 223, 224), UIManager.get("CubismCommon.gl.viewArea.background"));
    }

    @Test
    void restoreNativeDropsOwnedKeysAndDeletesTheRuntimeSource() {
        UIManager.put("CubismCommon.blue", new Color(1, 2, 3));
        SwingFlatLafHostOperations host = new SwingFlatLafHostOperations(getClass().getClassLoader());

        host.replace(Map.of("CubismCommon.blue", "#112233"));
        assertTrue(java.nio.file.Files.exists(ThemeRuntimeProperties.path()));

        // refresh() requires the host FlatLaf class, absent from the unit-test
        // classpath; the owned-key removal and source deletion happen first.
        try {
            host.restoreNative();
            org.junit.jupiter.api.Assertions.fail("expected FlatLaf failure outside the host");
        } catch (IllegalStateException expected) {
            // The unit environment has no com.formdev.flatlaf.FlatLaf class.
        }

        assertFalse(UIManager.getDefaults().containsKey("CubismCommon.blue"));
        assertFalse(java.nio.file.Files.exists(ThemeRuntimeProperties.path()));
    }
}
