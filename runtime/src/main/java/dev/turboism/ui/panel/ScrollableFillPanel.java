package dev.turboism.ui.panel;

import javax.swing.JPanel;
import java.awt.Rectangle;

/**
 * Vertical stack used as a scroll viewport's view. Implements {@code Scrollable}
 * so {@code JViewport} hands it the viewport width (HTML content then wraps to
 * the available width) instead of keeping its preferred width, which would push
 * long entries off-viewport (no horizontal scrollbar is ever shown).
 */
final class ScrollableFillPanel extends JPanel implements javax.swing.Scrollable {

    ScrollableFillPanel() {
        super(new VerticalFillLayout());
    }

    @Override
    public java.awt.Dimension getPreferredScrollableViewportSize() {
        return getPreferredSize();
    }

    @Override
    public int getScrollableUnitIncrement(final Rectangle visibleRect, final int orientation, final int direction) {
        return 16;
    }

    @Override
    public int getScrollableBlockIncrement(final Rectangle visibleRect, final int orientation, final int direction) {
        return visibleRect.height;
    }

    @Override
    public boolean getScrollableTracksViewportWidth() {
        return true;
    }

    @Override
    public boolean getScrollableTracksViewportHeight() {
        return false;
    }
}
