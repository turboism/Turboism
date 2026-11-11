package dev.turboism.ui.panel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JPanel;
import org.junit.jupiter.api.Test;

/**
 * Pins the icon contract of the themed ArtMesh tool-details host.
 *
 * <p>The editor ships its toolbar icons on a pale square, so the themed host must strip that plate
 * from the glyph and tint the glyph with the theme foreground. An icon without a uniform plate, and
 * every icon outside an active theme, must keep the shipped pixels untouched.</p>
 */
class ThemedNativeIconsTest {

    private static final int SIZE = 16;
    private static final int GLYPH_INSET = 4;
    private static final int GLYPH_SIZE = SIZE - GLYPH_INSET * 2;
    private static final int CENTER = SIZE / 2;
    private static final int PLATE_RGB = 0xF2F2F2;
    private static final int GLYPH_RGB = 0x202020;
    private static final int RGB_MASK = 0xFFFFFF;
    private static final int OPAQUE_ALPHA = 0xFF;
    private static final Color INK = new Color(0xE6EDF3);

    @Test
    void replacesTheLightPlateWithTheThemeForegroundGlyphAtTheDeclaredSize() {
        final Icon source = platedIcon();

        final Icon processed = ThemedNativeIcons.recolored(source, INK);

        assertEquals(SIZE, processed.getIconWidth(), "a processed icon keeps the declared width");
        assertEquals(SIZE, processed.getIconHeight(), "a processed icon keeps the declared height");
        final BufferedImage raster = painted(processed);
        assertEquals(0, alpha(raster, 0, 0), "a plate pixel becomes transparent");
        assertEquals(OPAQUE_ALPHA, alpha(raster, CENTER, CENTER), "a glyph pixel stays visible");
        assertEquals(
                INK.getRGB() & RGB_MASK,
                raster.getRGB(CENTER, CENTER) & RGB_MASK,
                "the glyph follows the theme foreground");
    }

    @Test
    void leavesAnAlreadyTransparentIconUntouched() {
        final Icon source = new ImageIcon(new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB));

        assertSame(source, ThemedNativeIcons.recolored(source, INK));
    }

    @Test
    void followsTheThemeLifecycleOnTheHostButtonsItWalks() {
        final boolean[] themeActive = {false};
        final ThemedNativeIcons icons = new ThemedNativeIcons(() -> themeActive[0]);
        final Icon source = platedIcon();
        final JButton button = new JButton(source);
        final JPanel host = new JPanel();
        host.add(button);

        icons.decorate(host);
        assertSame(source, button.getIcon(), "no active theme leaves the shipped icon");

        themeActive[0] = true;
        icons.refresh();
        assertNotSame(source, button.getIcon(), "an active theme replaces the plated icon");

        themeActive[0] = false;
        icons.refresh();
        assertSame(source, button.getIcon(), "a removed theme restores the shipped icon");
    }

    private static Icon platedIcon() {
        final BufferedImage plate = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);
        final Graphics2D graphics = plate.createGraphics();
        try {
            graphics.setColor(new Color(PLATE_RGB));
            graphics.fillRect(0, 0, SIZE, SIZE);
            graphics.setColor(new Color(GLYPH_RGB));
            graphics.fillRect(GLYPH_INSET, GLYPH_INSET, GLYPH_SIZE, GLYPH_SIZE);
        } finally {
            graphics.dispose();
        }
        return new ImageIcon(plate);
    }

    private static BufferedImage painted(final Icon icon) {
        final BufferedImage raster =
                new BufferedImage(icon.getIconWidth(), icon.getIconHeight(), BufferedImage.TYPE_INT_ARGB);
        final Graphics2D graphics = raster.createGraphics();
        try {
            icon.paintIcon(null, graphics, 0, 0);
        } finally {
            graphics.dispose();
        }
        return raster;
    }

    private static int alpha(final BufferedImage raster, final int x, final int y) {
        return raster.getRGB(x, y) >>> 24;
    }
}
