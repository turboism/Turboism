package dev.turboism.ui.panel;

import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.LayoutManager2;
import java.awt.Window;
import javax.swing.JComponent;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import javax.swing.plaf.basic.BasicHTML;
import javax.swing.text.View;

/**
 * Vertical stack that stretches every child to the container width (so HTML
 * labels wrap) and lets a trailing {@link JScrollPane} fill the remaining
 * height (so long lists scroll inside the panel instead of being clipped).
 */
final class VerticalFillLayout implements LayoutManager2 {

    private static final int PLACEHOLDER_WINDOW_EXTENT = 1;
    private static final int MINIMUM_HTML_WIDTH = 1;

    @Override
    public void addLayoutComponent(final String name, final Component component) {}

    @Override
    public void addLayoutComponent(final Component component, final Object constraints) {}

    @Override
    public void removeLayoutComponent(final Component component) {}

    @Override
    public Dimension preferredLayoutSize(final Container parent) {
        final int knownWidth = hasKnownWidth(parent) ? parent.getWidth() : 0;
        int width = knownWidth;
        int height = 0;
        for (Component child : parent.getComponents()) {
            // Re-measure HTML views at the container width when known, or restore natural unwrapped spans.
            if (knownWidth > 0) {
                sizeHtmlView(child, knownWidth);
            } else {
                resetHtmlView(child);
            }
            Dimension preferred = child.getPreferredSize();
            width = Math.max(width, preferred.width);
            height += preferred.height;
        }
        return new Dimension(width, height);
    }

    @Override
    public Dimension minimumLayoutSize(final Container parent) {
        return preferredLayoutSize(parent);
    }

    @Override
    public Dimension maximumLayoutSize(final Container target) {
        return new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE);
    }

    @Override
    public void layoutContainer(final Container parent) {
        Component[] children = parent.getComponents();
        if (children.length == 0 || !hasKnownWidth(parent)) {
            return;
        }
        int width = parent.getWidth();
        // Size HTML views at the container width before reading child preferred heights.
        for (Component child : children) {
            sizeHtmlView(child, width);
        }
        int scrollIndex = -1;
        for (int i = 0; i < children.length; i++) {
            if (children[i] instanceof JScrollPane) {
                scrollIndex = i;
            }
        }
        int y = 0;
        for (int i = 0; i < children.length; i++) {
            Component child = children[i];
            int height = (i == scrollIndex)
                    ? Math.max(0, parent.getHeight() - y - remainingAfter(children, i))
                    : child.getPreferredSize().height;
            child.setBounds(0, y, width, height);
            y += height;
        }
    }

    private static boolean hasKnownWidth(final Container parent) {
        if (parent.getWidth() <= 0) {
            return false;
        }
        final Window window = SwingUtilities.getWindowAncestor(parent);
        return window == null
                || window.isVisible()
                || window.getWidth() > PLACEHOLDER_WINDOW_EXTENT
                || window.getHeight() > PLACEHOLDER_WINDOW_EXTENT;
    }

    /**
     * Sizes the BasicHTML view (when present) to the target width so the
     * component's preferred height reflects the wrapped text. Plain children
     * are untouched.
     */
    private static void sizeHtmlView(final Component child, final int width) {
        if (child instanceof JComponent component) {
            Object html = component.getClientProperty(BasicHTML.propertyKey);
            if (html instanceof View view) {
                // Subtract non-HTML chrome before resizing so wrapped text fits the actual bounds.
                int htmlSpan = (int) Math.ceil(view.getPreferredSpan(View.X_AXIS));
                int overhead = Math.max(0, component.getPreferredSize().width - htmlSpan);
                view.setSize(Math.max(MINIMUM_HTML_WIDTH, width - overhead), Short.MAX_VALUE);
            }
        }
    }

    private static void resetHtmlView(final Component child) {
        if (child instanceof JComponent component) {
            Object html = component.getClientProperty(BasicHTML.propertyKey);
            if (html instanceof View view && view.getViewCount() > 0) {
                final float naturalWidth = view.getView(0).getPreferredSpan(View.X_AXIS);
                view.setSize(naturalWidth, Short.MAX_VALUE);
            }
        }
    }

    private static int remainingAfter(final Component[] children, final int index) {
        int rest = 0;
        for (int i = index + 1; i < children.length; i++) {
            rest += children[i].getPreferredSize().height;
        }
        return rest;
    }

    @Override
    public float getLayoutAlignmentX(final Container target) {
        return 0.0f;
    }

    @Override
    public float getLayoutAlignmentY(final Container target) {
        return 0.0f;
    }

    @Override
    public void invalidateLayout(final Container target) {}
}
