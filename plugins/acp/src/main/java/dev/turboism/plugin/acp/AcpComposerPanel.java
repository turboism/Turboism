package dev.turboism.plugin.acp;

import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.RenderingHints;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;

/** Theme-colored input card and compact controls that wrap when the window narrows. */
final class AcpComposerPanel extends JPanel {

    /** Swing client-property name that both the window and this panel's prompt area read. */
    static final String PLACEHOLDER_PROPERTY = "JTextField.placeholderText";

    static final String ACTIVE_MODE_PROPERTY = "acp.active-mode";
    private static final int CARD_CORNER_ARC = 20;
    private static final int SUBMIT_ICON_SIZE = 36;
    private static final int SUBMIT_DISC_DIAMETER = 34;
    private static final int SUBMIT_GLYPH_OFFSET = 8;
    private static final int OPTION_CORNER_ARC = 8;
    private static final int ACTIVE_FILL_ALPHA = 36;
    private boolean inputFocused;

    AcpComposerPanel() {
        setOpaque(false);
    }

    void setInputFocused(final boolean focused) {
        if (inputFocused == focused) return;
        inputFocused = focused;
        repaint();
    }

    @Override
    protected void paintComponent(final Graphics graphics) {
        final Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(AcpChatTheme.inputBackground());
            g.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, CARD_CORNER_ARC, CARD_CORNER_ARC);
            g.setColor(inputFocused ? AcpChatTheme.accent() : AcpChatTheme.border());
            g.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, CARD_CORNER_ARC, CARD_CORNER_ARC);
        } finally {
            g.dispose();
        }
        super.paintComponent(graphics);
    }

    static Icon submitIcon(final boolean stop) {
        return new Icon() {
            public int getIconWidth() {
                return SUBMIT_ICON_SIZE;
            }

            public int getIconHeight() {
                return SUBMIT_ICON_SIZE;
            }

            public void paintIcon(final Component component, final Graphics graphics, final int x, final int y) {
                final Graphics2D g = (Graphics2D) graphics.create();
                try {
                    g.translate(x, y);
                    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    g.setColor(
                            !component.isEnabled()
                                    ? AcpChatTheme.surface()
                                    : stop ? AcpChatTheme.error() : AcpChatTheme.foreground());
                    g.fillOval(1, 1, SUBMIT_DISC_DIAMETER, SUBMIT_DISC_DIAMETER);
                    g.setColor(!component.isEnabled() ? AcpChatTheme.muted() : AcpChatTheme.inputBackground());
                    final javax.swing.JLabel glyph = new javax.swing.JLabel();
                    glyph.setForeground(g.getColor());
                    AcpIcons.icon(stop ? AcpIcons.Kind.STOP : AcpIcons.Kind.SEND)
                            .paintIcon(glyph, g, SUBMIT_GLYPH_OFFSET, SUBMIT_GLYPH_OFFSET);
                } finally {
                    g.dispose();
                }
            }
        };
    }

    static final class WrapLayout extends FlowLayout {
        WrapLayout() {
            super(FlowLayout.LEFT, 4, 2);
            setAlignOnBaseline(true);
        }

        @Override
        public Dimension preferredLayoutSize(final Container target) {
            synchronized (target.getTreeLock()) {
                final Insets insets = target.getInsets();
                final int available = target.getWidth() > 0
                        ? target.getWidth() - insets.left - insets.right - 2 * getHgap()
                        : Integer.MAX_VALUE;
                int width = 0, height = 0, rowWidth = 0, rowHeight = 0;
                for (Component child : target.getComponents()) {
                    if (!child.isVisible()) continue;
                    final Dimension size = child.getPreferredSize();
                    final int gap = rowWidth == 0 ? 0 : getHgap();
                    if (rowWidth > 0 && rowWidth + gap + size.width > available) {
                        width = Math.max(width, rowWidth);
                        height += rowHeight + getVgap();
                        rowWidth = 0;
                        rowHeight = 0;
                    }
                    rowWidth += (rowWidth == 0 ? 0 : getHgap()) + size.width;
                    rowHeight = Math.max(rowHeight, size.height);
                }
                return new Dimension(
                        Math.max(width, rowWidth) + insets.left + insets.right + 2 * getHgap(),
                        height + rowHeight + insets.top + insets.bottom + 2 * getVgap());
            }
        }
    }

    static final class PromptArea extends JTextArea {
        PromptArea() {
            super(3, 50);
        }

        @Override
        protected void paintComponent(final Graphics graphics) {
            super.paintComponent(graphics);
            if (getDocument().getLength() != 0) return;
            final Object placeholder = getClientProperty(PLACEHOLDER_PROPERTY);
            if (!(placeholder instanceof String text)) return;
            final Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setFont(getFont());
                g.setColor(AcpChatTheme.muted());
                final Insets insets = getInsets();
                final var metrics = g.getFontMetrics();
                final int width = getWidth() - insets.left - insets.right;
                if (width <= 0) return;
                final StringBuilder line = new StringBuilder();
                int lineWidth = 0;
                int y = insets.top + metrics.getAscent();
                for (int point : text.codePoints().toArray()) {
                    if (point == '\n') {
                        g.drawString(line.toString(), insets.left, y);
                        y += metrics.getHeight();
                        line.setLength(0);
                        lineWidth = 0;
                        continue;
                    }
                    final int charWidth = metrics.charWidth(point);
                    if (line.length() > 0 && lineWidth + charWidth > width) {
                        g.drawString(line.toString(), insets.left, y);
                        y += metrics.getHeight();
                        line.setLength(0);
                        lineWidth = 0;
                    }
                    line.appendCodePoint(point);
                    lineWidth += charWidth;
                }
                if (line.length() > 0) {
                    g.drawString(line.toString(), insets.left, y);
                }
            } finally {
                g.dispose();
            }
        }
    }

    /** Single-line filter field that paints muted placeholder text when empty. */
    static final class PromptSearchField extends JTextField {
        private final String placeholder;

        PromptSearchField(final String placeholder) {
            this.placeholder = placeholder == null ? "" : placeholder;
        }

        @Override
        protected void paintComponent(final Graphics graphics) {
            super.paintComponent(graphics);
            if (getDocument().getLength() != 0 || placeholder.isEmpty()) return;
            final Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                g.setFont(getFont());
                g.setColor(AcpChatTheme.muted());
                final Insets insets = getInsets();
                final var metrics = g.getFontMetrics();
                final int y = (getHeight() - metrics.getHeight()) / 2 + metrics.getAscent();
                g.drawString(placeholder, insets.left, y);
            } finally {
                g.dispose();
            }
        }
    }

    /** Paints disabled labels from the active palette even under the host's native Swing UI. */
    static final class OptionButton extends JButton {
        OptionButton(final String text) {
            super(text);
            setRolloverEnabled(true);
        }

        @Override
        protected void paintComponent(final Graphics graphics) {
            final Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                final boolean active = Boolean.TRUE.equals(getClientProperty(ACTIVE_MODE_PROPERTY));
                if (active) {
                    final java.awt.Color accent = AcpChatTheme.accent();
                    g.setColor(new java.awt.Color(
                            accent.getRed(), accent.getGreen(), accent.getBlue(), ACTIVE_FILL_ALPHA));
                    g.fillRoundRect(0, 0, getWidth(), getHeight(), OPTION_CORNER_ARC, OPTION_CORNER_ARC);
                    g.setColor(accent);
                    g.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, OPTION_CORNER_ARC, OPTION_CORNER_ARC);
                } else if (isEnabled() && (getModel().isRollover() || getModel().isPressed())) {
                    g.setColor(AcpChatTheme.surface());
                    g.fillRoundRect(0, 0, getWidth(), getHeight(), OPTION_CORNER_ARC, OPTION_CORNER_ARC);
                }
                if (hasFocus()) {
                    g.setColor(AcpChatTheme.accent());
                    g.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, OPTION_CORNER_ARC, OPTION_CORNER_ARC);
                }
                g.setFont(getFont());
                final Insets insets = getInsets();
                final java.awt.Rectangle view = new java.awt.Rectangle(
                        insets.left,
                        insets.top,
                        getWidth() - insets.left - insets.right,
                        getHeight() - insets.top - insets.bottom);
                final java.awt.Rectangle iconBounds = new java.awt.Rectangle(), textBounds = new java.awt.Rectangle();
                final String text = SwingUtilities.layoutCompoundLabel(
                        this,
                        g.getFontMetrics(),
                        getText(),
                        getIcon(),
                        getVerticalAlignment(),
                        getHorizontalAlignment(),
                        getVerticalTextPosition(),
                        getHorizontalTextPosition(),
                        view,
                        iconBounds,
                        textBounds,
                        getIconTextGap());
                if (getIcon() != null) getIcon().paintIcon(this, g, iconBounds.x, iconBounds.y);
                g.setColor(isEnabled() ? getForeground() : AcpChatTheme.muted());
                javax.swing.plaf.basic.BasicGraphicsUtils.drawStringUnderlineCharAt(
                        g,
                        text,
                        getDisplayedMnemonicIndex(),
                        textBounds.x,
                        textBounds.y + g.getFontMetrics().getAscent());
            } finally {
                g.dispose();
            }
        }
    }
}
