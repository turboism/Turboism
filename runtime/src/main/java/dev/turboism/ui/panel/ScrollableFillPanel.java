package dev.turboism.ui.panel;

import java.awt.LayoutManager;
import java.awt.Rectangle;
import javax.swing.JPanel;

/** Fits content to the viewport width without stretching short content vertically. */
final class ScrollableFillPanel extends JPanel implements javax.swing.Scrollable {

    ScrollableFillPanel() {
        this(new VerticalFillLayout());
    }

    ScrollableFillPanel(final LayoutManager layout) {
        super(layout);
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
