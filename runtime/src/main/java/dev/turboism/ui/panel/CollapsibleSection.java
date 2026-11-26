package dev.turboism.ui.panel;

import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.LayoutManager2;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Area;
import java.util.MissingResourceException;
import java.util.Objects;
import java.util.ResourceBundle;
import javax.swing.BorderFactory;
import javax.swing.Icon;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.LookAndFeel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.border.AbstractBorder;
import javax.swing.border.Border;
import javax.swing.border.EmptyBorder;
import javax.swing.border.TitledBorder;

/**
 * Collapsible section control, ported faithfully from the legacy
 * {@code tool/agent/ui/CubismEmbeddedPanel} (buildCollapsibleSection +
 * CollapsibleTitledBorder). Behavior and appearance match the legacy control
 * while optionally placing a tri-state group toggle checkbox to the left of the title.
 */
public final class CollapsibleSection {
    private static final int MIN_TOP_INSET = 20;
    private static final int CONTENT_TOP_GAP = 2;
    private static final int HEADER_CHECKBOX_VERTICAL_PADDING = 2;
    private static final int HEADER_LEFT_INSET = 10;
    private static final int CHECKBOX_TITLE_GAP = 4;
    private static final int TITLE_WIDTH_SLACK = 2;
    private static final int HEADER_CUTOUT_PADDING = 4;
    private static final int ACTION_RIGHT_INSET = 12;
    private static final int ACTION_TOP_OFFSET = 2;
    private static final int BORDER_EDGE_SPACING = 2;
    private static final int FALLBACK_ICON_SIZE = 13;
    private static final int INDETERMINATE_BAR_INSET_X = 3;
    private static final int INDETERMINATE_BAR_HEIGHT = 2;
    private static final int MIN_INDETERMINATE_BAR_WIDTH = 4;
    private static final String SELECTED_STATE_PROPERTY = "JButton.selectedState";
    private static final String SELECTED_STATE_INDETERMINATE = "indeterminate";
    private static final String FLAT_LAF_PACKAGE_PREFIX = "com.formdev.flatlaf.";
    private static final String AQUA_LAF_CLASS_NAME = "com.apple.laf.AquaLookAndFeel";
    private static final Color FALLBACK_ACTION_COLOR = new Color(120, 120, 120);
    private static final Color FALLBACK_DISABLED_COLOR = new Color(140, 140, 140);

    private CollapsibleSection() {}

    /**
     * Creates a transparent tri-state checkbox styled for a collapsible section header.
     *
     * @return a configured header checkbox supporting FlatLaf and fallback indeterminate painting
     */
    public static JCheckBox createGroupCheckBox() {
        final TriStateCheckBox checkBox = new TriStateCheckBox("", false);
        checkBox.setOpaque(false);
        checkBox.setMargin(new Insets(0, 0, 0, 0));
        checkBox.setHorizontalAlignment(SwingConstants.CENTER);
        return checkBox;
    }

    /**
     * Creates a standard-aligned tri-state checkbox for bundled settings rows.
     *
     * @param text checkbox label
     * @param selected initial selection state
     * @return a configured row checkbox supporting FlatLaf and fallback indeterminate painting
     */
    public static JCheckBox createTriStateCheckBox(final String text, final boolean selected) {
        return new TriStateCheckBox(text, selected);
    }

    /**
     * Synchronizes a header group checkbox across unchecked, indeterminate, and checked states.
     *
     * @param checkBox the group header checkbox created by {@link #createGroupCheckBox()}
     * @param allSelected whether every toggleable item in the group is selected
     * @param anySelected whether at least one toggleable item in the group is selected
     */
    public static void updateGroupCheckBoxState(
            final JCheckBox checkBox, final boolean allSelected, final boolean anySelected) {
        Objects.requireNonNull(checkBox, "checkBox");
        final boolean indeterminate = !allSelected && anySelected;
        checkBox.putClientProperty(SELECTED_STATE_PROPERTY, indeterminate ? SELECTED_STATE_INDETERMINATE : null);
        checkBox.setSelected(allSelected);
        checkBox.repaint();
    }

    /**
     * Builds a titled section panel with a clickable collapse/expand hotspot.
     *
     * @param title             section title
     * @param content           section content panel, made transparent with a 2px top inset
     * @param expandedByDefault whether the section starts expanded
     * @return the configured collapsible section panel
     */
    public static JPanel create(String title, JPanel content, boolean expandedByDefault) {
        return create(title, content, expandedByDefault, dev.turboism.i18n.CubismHostLocale.resolve(), null);
    }

    /**
     * Builds a titled section panel using the caller's resolved locale.
     *
     * @param title             section title
     * @param content           section content panel, made transparent with a 2px top inset
     * @param expandedByDefault whether the section starts expanded
     * @param locale            effective localization locale for expand/collapse labels
     * @return the configured collapsible section panel
     */
    public static JPanel create(String title, JPanel content, boolean expandedByDefault, java.util.Locale locale) {
        return create(title, content, expandedByDefault, locale, null);
    }

    /**
     * Builds a titled section panel with an optional header toggle checkbox on the left of the title.
     *
     * @param title             section title
     * @param content           section content panel, made transparent with a 2px top inset
     * @param expandedByDefault whether the section starts expanded
     * @param locale            effective localization locale for expand/collapse labels
     * @param headerCheckBox    optional tri-state group checkbox placed left of the title, or {@code null}
     * @return the configured collapsible section panel
     */
    public static JPanel create(
            final String title,
            final JPanel content,
            final boolean expandedByDefault,
            final java.util.Locale locale,
            final JCheckBox headerCheckBox) {
        ResourceBundle bundle = ResourceBundle.getBundle(
                "dev.turboism.ui.panel.messages", locale == null ? java.util.Locale.ENGLISH : locale);
        return create(
                title,
                content,
                expandedByDefault,
                tr(bundle, "collapsible.section.expand", "Expand"),
                tr(bundle, "collapsible.section.collapse", "Collapse"),
                headerCheckBox);
    }

    private static JPanel create(
            String title,
            JPanel content,
            boolean expandedByDefault,
            String expandLabel,
            String collapseLabel,
            JCheckBox headerCheckBox) {
        CollapsibleTitledBorder border = new CollapsibleTitledBorder(title, expandLabel, collapseLabel, headerCheckBox);
        JPanel panel = new JPanel();
        panel.setLayout(new SectionLayout(content, headerCheckBox, border));
        panel.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.setBorder(border);
        content.setOpaque(false);
        content.setBorder(new EmptyBorder(CONTENT_TOP_GAP, 0, 0, 0));
        panel.add(content);
        if (headerCheckBox != null) {
            panel.add(headerCheckBox);
        }
        MouseAdapter toggleMouseListener = new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent event) {
                if (!border.isToggleHit(event.getPoint())) return;
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
        if (border.isToggleHit(point)) {
            panel.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            return;
        }
        panel.setCursor(Cursor.getDefaultCursor());
    }

    /** Lays out the section content inside insets and places the optional toggle checkbox in the top border. */
    private static final class SectionLayout implements LayoutManager2 {
        private final JPanel content;
        private final JCheckBox headerCheckBox;
        private final CollapsibleTitledBorder border;

        private SectionLayout(
                final JPanel content, final JCheckBox headerCheckBox, final CollapsibleTitledBorder border) {
            this.content = content;
            this.headerCheckBox = headerCheckBox;
            this.border = border;
        }

        @Override
        public void addLayoutComponent(final Component comp, final Object constraints) {}

        @Override
        public void addLayoutComponent(final String name, final Component comp) {}

        @Override
        public void removeLayoutComponent(final Component comp) {}

        @Override
        public Dimension preferredLayoutSize(final Container parent) {
            return computeSize(parent, false);
        }

        @Override
        public Dimension minimumLayoutSize(final Container parent) {
            return computeSize(parent, true);
        }

        @Override
        public Dimension maximumLayoutSize(final Container target) {
            return new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE);
        }

        @Override
        public float getLayoutAlignmentX(final Container target) {
            return Component.LEFT_ALIGNMENT;
        }

        @Override
        public float getLayoutAlignmentY(final Container target) {
            return Component.TOP_ALIGNMENT;
        }

        @Override
        public void invalidateLayout(final Container target) {}

        @Override
        public void layoutContainer(final Container parent) {
            final Insets insets = parent.getInsets();
            if (headerCheckBox != null) {
                final Dimension cbPref = headerCheckBox.getPreferredSize();
                final int cbY = Math.max(0, (insets.top - cbPref.height) / 2);
                headerCheckBox.setBounds(HEADER_LEFT_INSET, cbY, cbPref.width, cbPref.height);
            }
            if (content.isVisible()) {
                final int width = Math.max(0, parent.getWidth() - insets.left - insets.right);
                final int height = Math.max(0, parent.getHeight() - insets.top - insets.bottom);
                content.setBounds(insets.left, insets.top, width, height);
            } else {
                content.setBounds(insets.left, insets.top, 0, 0);
            }
        }

        private Dimension computeSize(final Container parent, final boolean minimum) {
            final Insets insets = parent.getInsets();
            final Dimension contentSize = content.isVisible()
                    ? (minimum ? content.getMinimumSize() : content.getPreferredSize())
                    : new Dimension(0, 0);
            final int width = minimum
                    ? contentSize.width + insets.left + insets.right
                    : Math.max(contentSize.width + insets.left + insets.right, border.headerPreferredWidth(parent));
            final int height = insets.top + contentSize.height + insets.bottom;
            return new Dimension(width, height);
        }
    }

    /** Checkbox supporting FlatLaf native indeterminate styling plus a cross-L&F bar fallback. */
    private static final class TriStateCheckBox extends JCheckBox {
        private TriStateCheckBox(final String text, final boolean selected) {
            super(text, selected);
        }

        @Override
        public void updateUI() {
            final boolean wasHeader =
                    (getText() == null || getText().isEmpty()) && getHorizontalAlignment() == SwingConstants.CENTER;
            super.updateUI();
            if (wasHeader) {
                setOpaque(false);
            }
        }

        @Override
        protected void paintComponent(final Graphics graphics) {
            super.paintComponent(graphics);
            if (!SELECTED_STATE_INDETERMINATE.equals(getClientProperty(SELECTED_STATE_PROPERTY))
                    || supportsNativeIndeterminateState()) {
                return;
            }
            paintFallbackIndeterminateBar(graphics);
        }

        private void paintFallbackIndeterminateBar(final Graphics graphics) {
            final Icon boxIcon = UIManager.getIcon("CheckBox.icon");
            final int iconWidth = boxIcon != null ? boxIcon.getIconWidth() : FALLBACK_ICON_SIZE;
            final int iconHeight = boxIcon != null ? boxIcon.getIconHeight() : FALLBACK_ICON_SIZE;
            final Insets insets = getInsets();
            final int boxX;
            final int boxY;
            if (getHorizontalAlignment() == SwingConstants.CENTER
                    && (getText() == null || getText().isEmpty())) {
                boxX = insets.left + Math.max(0, (getWidth() - insets.left - insets.right - iconWidth) / 2);
                boxY = insets.top + Math.max(0, (getHeight() - insets.top - insets.bottom - iconHeight) / 2);
            } else {
                final Rectangle viewRect = new Rectangle(
                        insets.left,
                        insets.top,
                        Math.max(0, getWidth() - insets.left - insets.right),
                        Math.max(0, getHeight() - insets.top - insets.bottom));
                final Rectangle iconRect = new Rectangle();
                final Rectangle textRect = new Rectangle();
                SwingUtilities.layoutCompoundLabel(
                        this,
                        getFontMetrics(getFont()),
                        getText() == null ? "" : getText(),
                        boxIcon,
                        getVerticalAlignment(),
                        getHorizontalAlignment(),
                        getVerticalTextPosition(),
                        getHorizontalTextPosition(),
                        viewRect,
                        iconRect,
                        textRect,
                        getIconTextGap());
                boxX = iconRect.x;
                boxY = iconRect.y;
            }
            final int barWidth = Math.max(MIN_INDETERMINATE_BAR_WIDTH, iconWidth - INDETERMINATE_BAR_INSET_X * 2);
            final int barX = boxX + (iconWidth - barWidth) / 2;
            final int barY = boxY + (iconHeight - INDETERMINATE_BAR_HEIGHT) / 2;
            final Color color = isEnabled()
                    ? (getForeground() != null ? getForeground() : UIManager.getColor("CheckBox.foreground"))
                    : FALLBACK_DISABLED_COLOR;
            graphics.setColor(color != null ? color : Color.DARK_GRAY);
            graphics.fillRect(barX, barY, barWidth, INDETERMINATE_BAR_HEIGHT);
        }

        private static boolean supportsNativeIndeterminateState() {
            final LookAndFeel laf = UIManager.getLookAndFeel();
            if (laf == null) {
                return false;
            }
            final String className = laf.getClass().getName();
            return className.startsWith(FLAT_LAF_PACKAGE_PREFIX) || AQUA_LAF_CLASS_NAME.equals(className);
        }
    }

    /**
     * Package-private, so a same-package test reads it directly. Delegates the title identity to
     * {@link TitledBorder} and renders the optional header checkbox, title, and top-right hotspot.
     */
    static final class CollapsibleTitledBorder extends AbstractBorder {
        private final TitledBorder delegate;
        private final String expandLabel;
        private final String collapseLabel;
        private final JCheckBox headerCheckBox;
        private final JLabel titleLabel = new JLabel();
        private final JLabel actionLabel = new JLabel();
        private String actionText = "展开";
        private Rectangle actionBounds = new Rectangle();
        private Rectangle titleBounds = new Rectangle();

        private CollapsibleTitledBorder(
                final String title,
                final String expandLabel,
                final String collapseLabel,
                final JCheckBox headerCheckBox) {
            this.delegate = BorderFactory.createTitledBorder(title);
            this.expandLabel = expandLabel;
            this.collapseLabel = collapseLabel;
            this.headerCheckBox = headerCheckBox;
            this.titleLabel.setOpaque(false);
            this.actionLabel.setOpaque(false);
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

        boolean isToggleHit(Point point) {
            return actionBounds.contains(point) || titleBounds.contains(point);
        }

        int headerPreferredWidth(final Component component) {
            final Font titleFont = resolveTitleFont(component);
            final int titleWidth = component.getFontMetrics(titleFont).stringWidth(delegate.getTitle());
            final int actionWidth =
                    component.getFontMetrics(resolveActionFont(component)).stringWidth(actionText);
            final int checkWidth =
                    headerCheckBox == null ? 0 : headerCheckBox.getPreferredSize().width + CHECKBOX_TITLE_GAP;
            return HEADER_LEFT_INSET
                    + checkWidth
                    + titleWidth
                    + HEADER_CUTOUT_PADDING * 2
                    + actionWidth
                    + ACTION_RIGHT_INSET;
        }

        @Override
        public Insets getBorderInsets(Component component, Insets insets) {
            Insets delegateInsets = delegate.getBorderInsets(component);
            insets.top = computeTopInset(delegateInsets.top);
            insets.left = delegateInsets.left;
            insets.bottom = delegateInsets.bottom;
            insets.right = delegateInsets.right;
            return insets;
        }

        @Override
        public Insets getBorderInsets(Component component) {
            Insets insets = delegate.getBorderInsets(component);
            insets.top = computeTopInset(insets.top);
            return insets;
        }

        @Override
        public void paintBorder(Component component, Graphics graphics, int x, int y, int width, int height) {
            if (headerCheckBox == null) {
                paintLegacyBorder(component, graphics, x, y, width, height);
                return;
            }
            paintHeaderCheckboxBorder(component, graphics, x, y, width, height);
        }

        private void paintLegacyBorder(
                final Component component,
                final Graphics graphics,
                final int x,
                final int y,
                final int width,
                final int height) {
            delegate.paintBorder(component, graphics, x, y, width, height);

            final Font titleFont = resolveTitleFont(component);
            final FontMetrics titleMetrics = component.getFontMetrics(titleFont);
            final int titleWidth = titleMetrics.stringWidth(delegate.getTitle());
            titleBounds = new Rectangle(x + HEADER_LEFT_INSET, y, titleWidth, titleMetrics.getHeight());

            final Font font = resolveActionFont(component);
            final FontMetrics metrics = component.getFontMetrics(font);
            final int textWidth = metrics.stringWidth(actionText);
            final int textHeight = metrics.getHeight();
            final int textX = x + width - textWidth - ACTION_RIGHT_INSET;
            final int textY = y + metrics.getAscent() + ACTION_TOP_OFFSET;

            graphics.setColor(component.getBackground());
            graphics.fillRect(textX - HEADER_CUTOUT_PADDING, y, textWidth + HEADER_CUTOUT_PADDING * 2, textHeight);
            graphics.setColor(resolveActionColor());
            graphics.setFont(font);
            graphics.drawString(actionText, textX, textY);
            actionBounds =
                    new Rectangle(textX - HEADER_CUTOUT_PADDING, y, textWidth + HEADER_CUTOUT_PADDING * 2, textHeight);
        }

        private void paintHeaderCheckboxBorder(
                final Component component,
                final Graphics graphics,
                final int x,
                final int y,
                final int width,
                final int height) {
            final int topInset = getBorderInsets(component).top;
            final Font titleFont = resolveTitleFont(component);
            final Font actionFont = resolveActionFont(component);
            final int checkWidth = headerCheckBox.getPreferredSize().width;
            final int actionWidth = component.getFontMetrics(actionFont).stringWidth(actionText);
            final int actionX = x + width - actionWidth - ACTION_RIGHT_INSET;
            final int titleX = x + HEADER_LEFT_INSET + checkWidth + CHECKBOX_TITLE_GAP;
            final int rawTitleWidth = component.getFontMetrics(titleFont).stringWidth(delegate.getTitle());
            final int maxTitleWidth = Math.max(0, actionX - HEADER_CUTOUT_PADDING * 2 - titleX);
            final int drawnTitleWidth = Math.min(rawTitleWidth, maxTitleWidth);

            titleBounds = new Rectangle(titleX, y, drawnTitleWidth, topInset);
            actionBounds = new Rectangle(
                    actionX - HEADER_CUTOUT_PADDING, y, actionWidth + HEADER_CUTOUT_PADDING * 2, topInset);

            final Border innerBorder = delegate.getBorder();
            if (innerBorder != null) {
                final int lineY = y + topInset / 2;
                final Graphics2D borderGraphics = (Graphics2D) graphics.create();
                try {
                    final Area clip = new Area(
                            borderGraphics.getClip() != null
                                    ? borderGraphics.getClip()
                                    : new Rectangle(x, y, width, height));
                    final int leftCutoutWidth =
                            checkWidth + CHECKBOX_TITLE_GAP + drawnTitleWidth + HEADER_CUTOUT_PADDING * 2;
                    clip.subtract(new Area(new Rectangle(
                            x + HEADER_LEFT_INSET - HEADER_CUTOUT_PADDING, y, leftCutoutWidth, topInset)));
                    clip.subtract(new Area(actionBounds));
                    borderGraphics.setClip(clip);
                    innerBorder.paintBorder(
                            component,
                            borderGraphics,
                            x + BORDER_EDGE_SPACING,
                            lineY,
                            Math.max(0, width - BORDER_EDGE_SPACING * 2),
                            Math.max(0, height - (lineY - y) - BORDER_EDGE_SPACING));
                } finally {
                    borderGraphics.dispose();
                }
            }

            paintHeaderLabel(
                    titleLabel,
                    graphics,
                    delegate.getTitle(),
                    titleFont,
                    resolveTitleColor(component),
                    component,
                    titleX,
                    y,
                    Math.max(1, drawnTitleWidth + TITLE_WIDTH_SLACK),
                    topInset);
            paintHeaderLabel(
                    actionLabel,
                    graphics,
                    actionText,
                    actionFont,
                    resolveActionColor(),
                    component,
                    actionX,
                    y,
                    Math.max(1, actionWidth + TITLE_WIDTH_SLACK),
                    topInset);
        }

        private static void paintHeaderLabel(
                final JLabel label,
                final Graphics graphics,
                final String text,
                final Font font,
                final Color color,
                final Component owner,
                final int x,
                final int y,
                final int width,
                final int height) {
            label.setText(text);
            label.setFont(font);
            label.setForeground(color);
            label.setComponentOrientation(owner.getComponentOrientation());
            label.setEnabled(owner.isEnabled());
            label.setSize(width, height);
            final Graphics2D copy = (Graphics2D) graphics.create();
            try {
                copy.translate(x, y);
                label.paint(copy);
            } finally {
                copy.dispose();
            }
        }

        private int computeTopInset(final int delegateTop) {
            int top = Math.max(delegateTop, MIN_TOP_INSET);
            if (headerCheckBox != null) {
                top = Math.max(top, headerCheckBox.getPreferredSize().height + HEADER_CHECKBOX_VERTICAL_PADDING);
            }
            return top;
        }

        private Font resolveTitleFont(final Component component) {
            Font font = delegate.getTitleFont();
            if (font == null) {
                font = UIManager.getFont("TitledBorder.font");
            }
            return font != null ? font : component.getFont();
        }

        private Color resolveTitleColor(final Component component) {
            Color color = delegate.getTitleColor();
            if (color == null) {
                color = UIManager.getColor("TitledBorder.titleColor");
            }
            if (color == null) {
                color = UIManager.getColor("Label.foreground");
            }
            return color != null ? color : component.getForeground();
        }

        private static Font resolveActionFont(final Component component) {
            final Font font = component.getFont();
            return font != null ? font : UIManager.getFont("Label.font");
        }

        private static Color resolveActionColor() {
            final Color color = UIManager.getColor("Label.foreground");
            return color != null ? color : FALLBACK_ACTION_COLOR;
        }
    }
}
