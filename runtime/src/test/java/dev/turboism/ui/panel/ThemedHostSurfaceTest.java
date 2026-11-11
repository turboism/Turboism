package dev.turboism.ui.panel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.UIManager;
import org.junit.jupiter.api.Test;

/**
 * Pins the themed background contract of the ArtMesh tool-details host surface.
 *
 * <p>The runtime injects plugin tool buttons and sliders into the native tool-details containers.
 * Those containers are built with the native background, so the injected host must paint the
 * themed panel surface itself, follow a live theme change, and leave every injected row
 * transparent.</p>
 */
class ThemedHostSurfaceTest {

    private static final String BACKGROUND_KEY = "Panel.background";
    private static final Color FIRST_THEME = new Color(0x182432);
    private static final Color SECOND_THEME = new Color(0xFFF5F8);
    private static final Color NATIVE_BACKGROUND = new Color(0xF2F2F2);
    private static final Color ROW_BACKGROUND = new Color(0x3C3C3C);

    @Test
    void paintsTheThemedSurfaceBehindTransparentInjectedRows() {
        final Object previous = UIManager.get(BACKGROUND_KEY);
        final ThemedHostSurface surfaces = new ThemedHostSurface();
        final JPanel host = new JPanel();
        host.setBackground(NATIVE_BACKGROUND);
        final JLabel row = new JLabel("radius");
        row.setBackground(ROW_BACKGROUND);
        host.add(row);
        try {
            UIManager.put(BACKGROUND_KEY, FIRST_THEME);
            surfaces.paint(host);

            assertTrue(host.isOpaque(), "the host owns the background, so it must be opaque");
            assertEquals(FIRST_THEME, host.getBackground());
            assertFalse(row.isOpaque(), "a row inside the host must not paint its own background");
            assertEquals(ROW_BACKGROUND, row.getBackground());
        } finally {
            UIManager.put(BACKGROUND_KEY, previous);
        }
    }

    @Test
    void followsAThemeChangeAndRestoresTheNativeAppearanceWhenTheLastOwnerLeaves() {
        final Object previous = UIManager.get(BACKGROUND_KEY);
        final ThemedHostSurface surfaces = new ThemedHostSurface();
        final JPanel host = new JPanel();
        host.setBackground(NATIVE_BACKGROUND);
        host.setOpaque(false);
        try {
            UIManager.put(BACKGROUND_KEY, FIRST_THEME);
            surfaces.paint(host);

            UIManager.put(BACKGROUND_KEY, SECOND_THEME);
            surfaces.refresh();
            assertEquals(SECOND_THEME, host.getBackground(), "a live theme switch repaints the same surface");

            surfaces.paint(host);
            surfaces.release(host);
            assertEquals(SECOND_THEME, host.getBackground(), "a shared host stays themed while one owner remains");

            surfaces.release(host);
            assertFalse(host.isOpaque(), "the native appearance returns after the last owner");
            assertEquals(NATIVE_BACKGROUND, host.getBackground());
        } finally {
            UIManager.put(BACKGROUND_KEY, previous);
        }
    }
}
