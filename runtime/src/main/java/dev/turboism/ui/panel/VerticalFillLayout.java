package dev.turboism.ui.panel;

import javax.swing.JComponent;
import javax.swing.JScrollPane;
import javax.swing.plaf.basic.BasicHTML;
import javax.swing.text.View;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.LayoutManager2;

/**
 * Vertical stack that stretches every child to the container width (so HTML
 * labels wrap) and lets a trailing {@link JScrollPane} fill the remaining
 * height (so long lists scroll inside the panel instead of being clipped).
 */
final class VerticalFillLayout implements LayoutManager2 {

    @Override
    public void addLayoutComponent(final String name, final Component component) { }

    @Override
    public void addLayoutComponent(final Component component, final Object constraints) { }

    @Override
    public void removeLayoutComponent(final Component component) { }

    @Override
    public Dimension preferredLayoutSize(final Container parent) {
        int width = parent.getWidth();
        int height = 0;
        for (Component child : parent.getComponents()) {
            // A child whose width is already known (previous layout pass)
            // must report its height at that width, not at the unwrapped
            // preferred width.
            if (width > 0) {
                sizeHtmlView(child, width);
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
        if (children.length == 0) {
            return;
        }
        int width = parent.getWidth();
        // Lay each HTML child's text out at the available width first, so the
        // preferred heights below reflect wrapped lines instead of one
        // unwrapped line.
        for (Component child : children) {
            sizeHtmlView(child, width);
        }
        int fixed = 0;
        int scrollIndex = -1;
        for (int i = 0; i < children.length; i++) {
            if (children[i] instanceof JScrollPane) {
                scrollIndex = i;
            } else {
                fixed += children[i].getPreferredSize().height;
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

    /**
     * Sizes the BasicHTML view (when present) to the target width so the
     * component's preferred height reflects the wrapped text. Plain children
     * are untouched.
     */
    private static void sizeHtmlView(final Component child, final int width) {
        if (child instanceof JComponent component) {
            Object html = component.getClientProperty(BasicHTML.propertyKey);
            if (html instanceof View view) {
                // Horizontal non-HTML overhead (icon, insets, gap, chrome):
                // the component's preferred width minus the HTML text span,
                // measured BEFORE resizing so it stays stable across passes
                // (JLabel overhead = 0, JCheckBox overhead ≈ 25). The HTML
                // view gets only the text budget, so the laid-out preferred
                // width fits the actual bounds instead of overflowing.
                int htmlSpan = (int) Math.ceil(view.getPreferredSpan(View.X_AXIS));
                int overhead = Math.max(0, component.getPreferredSize().width - htmlSpan);
                view.setSize(Math.max(1, width - overhead), Short.MAX_VALUE);
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
    public void invalidateLayout(final Container target) { }
}
