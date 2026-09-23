package dev.turboism.shell;

import javax.swing.BorderFactory;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JViewport;
import javax.swing.Scrollable;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Rectangle;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;

/** A tab-local viewport; dialog actions remain outside it and never scroll away. */
final class SettingsPageScrollPane extends JScrollPane {
    SettingsPageScrollPane(final JPanel form) {
        super(new Content(form), VERTICAL_SCROLLBAR_AS_NEEDED, HORIZONTAL_SCROLLBAR_AS_NEEDED);
        setBorder(BorderFactory.createEmptyBorder());
        setViewportBorder(BorderFactory.createEmptyBorder());
        setWheelScrollingEnabled(true);
        getViewport().setBackground(form.getBackground());
        getAccessibleContext().setAccessibleName(form.getAccessibleContext().getAccessibleName());
    }

    /** Preserves natural row heights while filling available width and spare height. */
    private static final class Content extends JPanel implements Scrollable {
        Content(final JPanel form) {
            super(new BorderLayout());
            setBackground(form.getBackground());
            add(form, BorderLayout.CENTER);
            installFocusReveal(form);
        }

        private void installFocusReveal(final Component component) {
            if (component.isFocusable()) {
                component.addFocusListener(new FocusAdapter() {
                    @Override public void focusGained(final FocusEvent event) {
                        final Component focused = event.getComponent();
                        if (!SwingUtilities.isDescendingFrom(focused, Content.this)) return;
                        final Rectangle bounds = SwingUtilities.convertRectangle(
                            focused.getParent(), focused.getBounds(), Content.this);
                        if (getParent() instanceof JViewport viewport) {
                            // Wide controls reveal their leading edge, not an invisible trailing end.
                            bounds.width = Math.min(bounds.width, viewport.getExtentSize().width);
                            bounds.height = Math.min(bounds.height, viewport.getExtentSize().height);
                        }
                        scrollRectToVisible(bounds);
                    }
                });
            }
            if (component instanceof Container container) {
                for (Component child : container.getComponents()) installFocusReveal(child);
            }
        }

        @Override public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        @Override public int getScrollableUnitIncrement(final Rectangle visibleRect,
                                                        final int orientation, final int direction) {
            return Math.max(1, getFontMetrics(getFont()).getHeight());
        }

        @Override public int getScrollableBlockIncrement(final Rectangle visibleRect,
                                                         final int orientation, final int direction) {
            final int extent = orientation == SwingConstants.HORIZONTAL ? visibleRect.width : visibleRect.height;
            return Math.max(1, extent - getScrollableUnitIncrement(visibleRect, orientation, direction));
        }

        @Override public boolean getScrollableTracksViewportWidth() {
            // Fit ordinary forms; expose horizontal scrolling instead of clipping long translations.
            return getParent() instanceof JViewport viewport
                && viewport.getExtentSize().width >= getMinimumSize().width;
        }

        @Override public boolean getScrollableTracksViewportHeight() {
            return getParent() instanceof JViewport viewport
                && viewport.getExtentSize().height >= getPreferredSize().height;
        }
    }
}
