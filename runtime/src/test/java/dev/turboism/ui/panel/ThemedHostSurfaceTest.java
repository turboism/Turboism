package dev.turboism.ui.panel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.util.Locale;
import javax.swing.AbstractButton;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JToggleButton;
import javax.swing.UIManager;
import org.junit.jupiter.api.Test;

/**
 * Pins the themed background contract of the ArtMesh tool-details host.
 *
 * <p>The runtime injects plugin tool buttons and sliders into the native tool-details containers.
 * Those containers are built with the native background. The themed host must paint the themed
 * panel surface of the whole tab tree itself. It must follow a live theme change, give the native
 * buttons the theme state colours, and leave every injected row transparent.</p>
 */
class ThemedHostSurfaceTest {

    private static final String BACKGROUND_KEY = "Panel.background";
    private static final String SELECTION_KEY = "CubismCommon.selectionBackground";
    private static final String HOVER_KEY = "Button.hoverBackground";
    private static final String PRESSED_KEY = "CubismCommon.activeColor";
    private static final String STYLE_KEY = "FlatLaf.style";
    private static final String BUTTON_TYPE_KEY = "JButton.buttonType";
    private static final String TOOLBAR_BUTTON_TYPE = "toolBarButton";
    private static final String HOST_STYLE = "arc: 0";
    private static final Color FIRST_THEME = new Color(0x182432);
    private static final Color SECOND_THEME = new Color(0xFFF5F8);
    private static final Color FIRST_SELECTION = new Color(0xE8A0BF);
    private static final Color SECOND_SELECTION = new Color(0xD4789A);
    private static final Color HOVER = new Color(0xFFD5E5);
    private static final Color PRESSED = new Color(0xC96B8A);
    private static final Color NATIVE_BACKGROUND = new Color(0xF2F2F2);
    private static final Color NATIVE_BUTTON_BACKGROUND = new Color(0xFFFFFF);
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

    @Test
    void themesEveryNativeContainerAndButtonOfOneHostTreeAndRestoresThem() {
        final Object background = UIManager.get(BACKGROUND_KEY);
        final Object selection = UIManager.get(SELECTION_KEY);
        final Object hover = UIManager.get(HOVER_KEY);
        final Object pressed = UIManager.get(PRESSED_KEY);
        final ThemedHostSurface surfaces = new ThemedHostSurface(() -> true);
        final JPanel root = new JPanel();
        root.setBackground(NATIVE_BACKGROUND);
        final JPanel first = new JPanel();
        first.setBackground(NATIVE_BACKGROUND);
        final JPanel second = new JPanel();
        second.setBackground(NATIVE_BACKGROUND);
        second.setOpaque(false);
        final JLabel row = new JLabel("axis");
        row.setBackground(ROW_BACKGROUND);
        final JPanel rendered = new RenderedPanel();
        rendered.setOpaque(false);
        final JToggleButton toggle = new JToggleButton("X");
        toggle.setBackground(NATIVE_BUTTON_BACKGROUND);
        final JButton toolbarButton = new JButton();
        toolbarButton.putClientProperty(BUTTON_TYPE_KEY, TOOLBAR_BUTTON_TYPE);
        toolbarButton.putClientProperty(STYLE_KEY, HOST_STYLE);
        toolbarButton.setBackground(NATIVE_BUTTON_BACKGROUND);
        second.add(row);
        second.add(rendered);
        second.add(toggle);
        second.add(toolbarButton);
        root.add(first);
        root.add(second);
        try {
            putDefault(BACKGROUND_KEY, FIRST_THEME);
            putDefault(SELECTION_KEY, FIRST_SELECTION);
            putDefault(HOVER_KEY, HOVER);
            putDefault(PRESSED_KEY, PRESSED);
            surfaces.paintTree(root);

            assertEquals(FIRST_THEME, root.getBackground(), "the tab root follows the theme");
            assertEquals(FIRST_THEME, first.getBackground(), "a sibling container follows the theme");
            assertEquals(FIRST_THEME, second.getBackground(), "a native transparent container becomes themed");
            assertTrue(second.isOpaque(), "a themed container must paint its own surface");
            assertFalse(row.isOpaque(), "a label inside the tree must stay transparent");
            assertEquals(ROW_BACKGROUND, row.getBackground());
            assertFalse(rendered.isOpaque(), "a rendered panel keeps its transparent stack");

            assertFalse(toggle.isOpaque(), "a themed button must not paint a parent plate");
            assertEquals(FIRST_THEME, toggle.getBackground(), "a plain toggle paints the themed surface");
            assertEquals(hex(FIRST_SELECTION), styleValue(toggle, "selectedBackground"));
            assertEquals(hex(HOVER), styleValue(toggle, "hoverBackground"));
            assertEquals(hex(PRESSED), styleValue(toggle, "pressedBackground"));
            assertEquals(hex(FIRST_THEME), styleValue(toggle, "disabledBackground"));
            assertEquals(hex(FIRST_SELECTION), styleValue(toggle, "disabledSelectedBackground"));

            assertEquals(FIRST_THEME, toolbarButton.getBackground(), "a toolbar button paints the themed surface");
            assertEquals(hex(FIRST_SELECTION), styleValue(toolbarButton, "toolbar.selectedBackground"));
            assertEquals(hex(HOVER), styleValue(toolbarButton, "toolbar.hoverBackground"));
            assertNull(styleValue(toolbarButton, "selectedBackground"), "a toolbar button keeps the toolbar family");
            assertTrue(
                    String.valueOf(toolbarButton.getClientProperty(STYLE_KEY)).startsWith(HOST_STYLE),
                    "the host style stays intact while the theme is active");

            surfaces.releaseTree(root);

            assertEquals(NATIVE_BACKGROUND, root.getBackground(), "the tab root returns to the native colour");
            assertEquals(NATIVE_BACKGROUND, first.getBackground());
            assertEquals(NATIVE_BACKGROUND, second.getBackground());
            assertFalse(second.isOpaque(), "the native transparency returns");
            assertEquals(NATIVE_BUTTON_BACKGROUND, toggle.getBackground(), "the native button tile returns");
            assertNull(toggle.getClientProperty(STYLE_KEY), "the native button states return");
            assertEquals(NATIVE_BUTTON_BACKGROUND, toolbarButton.getBackground());
            assertEquals(HOST_STYLE, toolbarButton.getClientProperty(STYLE_KEY), "the host style returns");
        } finally {
            putDefault(BACKGROUND_KEY, background);
            putDefault(SELECTION_KEY, selection);
            putDefault(HOVER_KEY, hover);
            putDefault(PRESSED_KEY, pressed);
        }
    }

    @Test
    void reappliesTheNewThemeColoursAfterALookAndFeelChange() {
        final Object background = UIManager.get(BACKGROUND_KEY);
        final Object selection = UIManager.get(SELECTION_KEY);
        final ThemedHostSurface surfaces = new ThemedHostSurface(() -> true);
        final JPanel root = new JPanel();
        final JPanel panel = new JPanel();
        final JToggleButton button = new JToggleButton("X");
        panel.add(button);
        root.add(panel);
        try {
            putDefault(BACKGROUND_KEY, FIRST_THEME);
            putDefault(SELECTION_KEY, FIRST_SELECTION);
            surfaces.paintTree(root);

            putDefault(BACKGROUND_KEY, SECOND_THEME);
            putDefault(SELECTION_KEY, SECOND_SELECTION);
            surfaces.refresh();

            assertEquals(SECOND_THEME, panel.getBackground(), "a look-and-feel change recolours the containers");
            assertEquals(SECOND_THEME, button.getBackground(), "a look-and-feel change recolours the buttons");
            assertEquals(hex(SECOND_SELECTION), styleValue(button, "selectedBackground"));
        } finally {
            putDefault(BACKGROUND_KEY, background);
            putDefault(SELECTION_KEY, selection);
        }
    }

    /** Stands in for a panel this runtime renders; the walk must leave it transparent. */
    private static final class RenderedPanel extends JPanel {}

    private static void putDefault(final String key, final Object value) {
        if (value == null) {
            UIManager.getDefaults().remove(key);
        } else {
            UIManager.put(key, value);
        }
    }

    private static String styleValue(final AbstractButton button, final String key) {
        final Object style = button.getClientProperty(STYLE_KEY);
        if (!(style instanceof String value)) return null;
        for (String pair : value.split("; ")) {
            final int separator = pair.indexOf(": ");
            if (separator < 0) continue;
            if (key.equals(pair.substring(0, separator))) return pair.substring(separator + 2);
        }
        return null;
    }

    private static String hex(final Color color) {
        return String.format(Locale.ROOT, "#%02X%02X%02X", color.getRed(), color.getGreen(), color.getBlue());
    }
}
