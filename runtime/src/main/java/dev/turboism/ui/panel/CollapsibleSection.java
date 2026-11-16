package dev.turboism.ui.panel;

import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Insets;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.MissingResourceException;
import java.util.ResourceBundle;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JPanel;
import javax.swing.UIManager;
import javax.swing.border.AbstractBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.TitledBorder;

/**
 * Collapsible section control, ported faithfully from the legacy
 * {@code tool/agent/ui/CubismEmbeddedPanel} (buildCollapsibleSection +
 * CollapsibleTitledBorder). Behavior and appearance match the legacy control
 * exactly: the title row holds a clickable collapse/expand hotspot at the top right.
 */
public final class CollapsibleSection {

    private CollapsibleSection() {}

    /**
     * Builds a titled section panel with a clickable collapse/expand hotspot.
     *
     * @param title             section title
     * @param content           section content panel, made transparent with a 2px top inset
     * @param expandedByDefault whether the section starts expanded
     */
    public static JPanel create(String title, JPanel content, boolean expandedByDefault) {
        return create(title, content, expandedByDefault, dev.turboism.i18n.CubismHostLocale.resolve());
    }

    /** Uses the caller's already-resolved effective locale; no global locale is changed. */
    public static JPanel create(String title, JPanel content, boolean expandedByDefault, java.util.Locale locale) {
        ResourceBundle bundle = ResourceBundle.getBundle(
                "dev.turboism.ui.panel.messages", locale == null ? java.util.Locale.ENGLISH : locale);
        return create(
                title,
                content,
                expandedByDefault,
                tr(bundle, "collapsible.section.expand", "Expand"),
                tr(bundle, "collapsible.section.collapse", "Collapse"));
    }

    private static JPanel create(
            String title, JPanel content, boolean expandedByDefault, String expandLabel, String collapseLabel) {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setAlignmentX(Component.LEFT_ALIGNMENT);
        CollapsibleTitledBorder border = new CollapsibleTitledBorder(title, expandLabel, collapseLabel);
        panel.setBorder(border);
        content.setOpaque(false);
        content.setBorder(new EmptyBorder(2, 0, 0, 0));
        panel.add(content);
        MouseAdapter toggleMouseListener = new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent event) {
                if (!border.isActionHit(event.getPoint())) return;
                updateCollapsibleSection(panel, content, border, !content.isVisible());
            }

            @Override
            public void mouseMoved(MouseEvent event) {
                updateCollapsibleCursor(panel, border, event.getPoint());
            }

            @Override
            public void mouseExited(MouseEvent event) {
                panel.setCursor(Cursor.getDefaultCursor());
            }
        };
        panel.addMouseListener(toggleMouseListener);
        panel.addMouseMotionListener(toggleMouseListener);
        updateCollapsibleSection(panel, content, border, expandedByDefault);
        return panel;
    }

    /**
     * Package-private: expands or collapses a section panel produced by {@link #create}, reusing the
     * internal toggle.
     *
     * @param section  a panel produced by this class
     * @param expanded the target expanded state
     */
    static void setExpanded(JPanel section, boolean expanded) {
        JPanel content = (JPanel) section.getComponent(0);
        CollapsibleTitledBorder border = (CollapsibleTitledBorder) section.getBorder();
        updateCollapsibleSection(section, content, border, expanded);
    }

    /**
     * Package-private: reports whether a section panel is currently expanded, read from the content
     * visibility.
     *
     * @param section a panel produced by this class
     */
    static boolean isExpanded(JPanel section) {
        return ((JPanel) section.getComponent(0)).isVisible();
    }

    /**
     * Reads the text of one key from the bundle, falling back to the legacy value when the key is
     * missing.
     */
    private static String tr(ResourceBundle bundle, String key, String fallback) {
        try {
            return bundle.getString(key);
        } catch (MissingResourceException e) {
            return fallback;
        }
    }

    private static void updateCollapsibleSection(
            JPanel panel, JPanel content, CollapsibleTitledBorder border, boolean expanded) {
        border.setActionText(expanded ? border.collapseLabel : border.expandLabel);
        content.setVisible(expanded);
        panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, panel.getPreferredSize().height));
        panel.revalidate();
        panel.repaint();
    }

    private static void updateCollapsibleCursor(JPanel panel, CollapsibleTitledBorder border, Point point) {
        if (border.isActionHit(point)) {
            panel.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            return;
        }
        panel.setCursor(Cursor.getDefaultCursor());
    }

    /**
     * Package-private, so a same-package test reads it directly. Delegates the title painting to
     * TitledBorder and draws the actionText hotspot at the top right.
     */
    static final class CollapsibleTitledBorder extends AbstractBorder {
        private final TitledBorder delegate;
        private final String expandLabel;
        private final String collapseLabel;
        private String actionText = "展开";
        private Rectangle actionBounds = new Rectangle();

        private CollapsibleTitledBorder(String title, String expandLabel, String collapseLabel) {
            this.delegate = BorderFactory.createTitledBorder(title);
            this.expandLabel = expandLabel;
            this.collapseLabel = collapseLabel;
        }

        private void setActionText(String actionText) {
            this.actionText = actionText;
        }

        Rectangle actionBounds() {
            return actionBounds;
        }

        String actionText() {
            return actionText;
        }

        boolean isActionHit(Point point) {
            return actionBounds.contains(point);
        }

        @Override
        public Insets getBorderInsets(Component component, Insets insets) {
            Insets delegateInsets = delegate.getBorderInsets(component);
            insets.top = Math.max(delegateInsets.top, 20);
            insets.left = delegateInsets.left;
            insets.bottom = delegateInsets.bottom;
            insets.right = delegateInsets.right;
            return insets;
        }

        @Override
        public Insets getBorderInsets(Component component) {
            Insets insets = delegate.getBorderInsets(component);
            insets.top = Math.max(insets.top, 20);
            return insets;
        }

        @Override
        public void paintBorder(Component component, Graphics graphics, int x, int y, int width, int height) {
            delegate.paintBorder(component, graphics, x, y, width, height);

            Font font = component.getFont();
            FontMetrics metrics = component.getFontMetrics(font);
            int textWidth = metrics.stringWidth(actionText);
            int textHeight = metrics.getHeight();
            int textX = x + width - textWidth - 12;
            int textY = y + metrics.getAscent() + 2;

            graphics.setColor(component.getBackground());
            graphics.fillRect(textX - 4, y, textWidth + 8, textHeight);
            graphics.setColor(
                    UIManager.getColor("Label.foreground") != null
                            ? UIManager.getColor("Label.foreground")
                            : new Color(120, 120, 120));
            graphics.setFont(font);
            graphics.drawString(actionText, textX, textY);
            actionBounds = new Rectangle(textX - 4, y, textWidth + 8, textHeight);
        }
    }
}
