package dev.turboism.ui.panel;

import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import javax.swing.JComponent;
import javax.swing.JPanel;

/**
 * Swing helpers shared by the collapsible-section and panel-renderer tests.
 *
 * <p>The collapsible border derives its action bounds during {@code paintBorder}, so a test must
 * paint the panel before it can address the toggle hotspot.
 */
final class SwingPanelTestSupport {

    /** Width the collapsible fixtures paint at. */
    private static final int PANEL_WIDTH = 400;

    /** Height the collapsible fixtures paint at. */
    private static final int PANEL_HEIGHT = 200;

    private SwingPanelTestSupport() {}

    /** Paints the panel at the fixture size so the border publishes its action bounds. */
    static void paint(final JPanel panel) {
        paintIntoImage(panel, PANEL_WIDTH, PANEL_HEIGHT);
    }

    /** Paints the component at its current bounds and returns the rendered image. */
    static BufferedImage paintComponent(final JComponent component) {
        return paintIntoImage(component, Math.max(1, component.getWidth()), Math.max(1, component.getHeight()));
    }

    private static BufferedImage paintIntoImage(final JComponent component, final int width, final int height) {
        final BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        final Graphics2D graphics = image.createGraphics();
        try {
            component.paint(graphics);
        } finally {
            graphics.dispose();
        }
        return image;
    }

    /** Returns a left-button click event at {@code point} for the supplied panel. */
    static MouseEvent click(final JPanel panel, final Point point) {
        return new MouseEvent(
                panel,
                MouseEvent.MOUSE_CLICKED,
                System.currentTimeMillis(),
                0,
                point.x,
                point.y,
                1,
                false,
                MouseEvent.BUTTON1);
    }

    /** Returns the centre point of {@code bounds}. */
    static Point centerOf(final Rectangle bounds) {
        return new Point(bounds.x + bounds.width / 2, bounds.y + bounds.height / 2);
    }

    /** Returns the collapsible border the panel currently installs. */
    static CollapsibleSection.CollapsibleTitledBorder borderOf(final JPanel panel) {
        return (CollapsibleSection.CollapsibleTitledBorder) panel.getBorder();
    }

    /** Returns a content panel with one child, so collapsing has a visible subtree to hide. */
    static JPanel newContent() {
        final JPanel content = new JPanel();
        content.add(new JPanel());
        return content;
    }
}
