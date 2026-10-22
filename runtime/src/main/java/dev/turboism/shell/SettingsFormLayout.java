package dev.turboism.shell;

import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.Map;
import java.util.TreeMap;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JViewport;
import javax.swing.plaf.basic.BasicHTML;
import javax.swing.text.View;

/** Width-aware settings rows; narrow pages place inputs below their captions. */
final class SettingsFormLayout extends GridBagLayout {
    private static final int GAP = 12;

    static void install(final Container form) {
        if (!(form.getLayout() instanceof GridBagLayout previous)) return;
        final SettingsFormLayout layout = new SettingsFormLayout();
        for (Component child : form.getComponents()) {
            layout.setConstraints(child, previous.getConstraints(child));
        }
        form.setLayout(layout);
    }

    @Override
    public Dimension preferredLayoutSize(final Container parent) {
        int width = parent.getWidth();
        if (parent.getParent() != null && parent.getParent().getParent() instanceof JViewport viewport) {
            width = viewport.getExtentSize().width;
        }
        if (width <= 0) width = 600;
        return arrange(parent, width, false);
    }

    @Override
    public Dimension minimumLayoutSize(final Container parent) {
        return new Dimension(0, preferredLayoutSize(parent).height);
    }

    @Override
    public void layoutContainer(final Container parent) {
        arrange(parent, parent.getWidth(), true);
    }

    private Dimension arrange(final Container parent, final int width, final boolean place) {
        final Insets insets = parent.getInsets();
        final int available = Math.max(1, width - insets.left - insets.right - GAP);
        final Map<Integer, Component[]> rows = new TreeMap<>();
        for (Component child : parent.getComponents()) {
            final var constraints = getConstraints(child);
            if (constraints.weighty > 0 || !child.isVisible()) continue;
            rows.computeIfAbsent(constraints.gridy, ignored -> new Component[2])[constraints.gridx == 0 ? 0 : 1] =
                    child;
        }
        int y = insets.top + GAP / 2;
        for (Component[] row : rows.values()) {
            final Component caption = row[0];
            final Component input = row[1];
            final boolean hasInput = input != null
                    && !(input instanceof JLabel label
                            && (label.getText() == null || label.getText().isEmpty()));
            final boolean sideBySide = hasInput && available >= 480;
            final int captionWidth = sideBySide ? available * 2 / 5 : available;
            final int captionHeight = heightAtWidth(caption, captionWidth);
            final int inputWidth = sideBySide ? available - captionWidth - GAP : available;
            final int inputHeight = hasInput ? heightAtWidth(input, inputWidth) : 0;
            final int rowHeight = sideBySide
                    ? Math.max(captionHeight, inputHeight)
                    : captionHeight + (hasInput ? GAP / 2 + inputHeight : 0);
            if (place) {
                if (caption != null) caption.setBounds(insets.left + GAP / 2, y, captionWidth, captionHeight);
                if (hasInput) {
                    input.setBounds(
                            insets.left + GAP / 2 + (sideBySide ? captionWidth + GAP : 0),
                            y + (sideBySide ? 0 : captionHeight + GAP / 2),
                            inputWidth,
                            inputHeight);
                } else if (input != null) {
                    input.setBounds(0, y, 0, 0);
                }
            }
            y += rowHeight + GAP;
        }
        return new Dimension(width, y + insets.bottom);
    }

    private static int heightAtWidth(final Component child, final int width) {
        if (child == null) return 0;
        if (child instanceof JLabel || child instanceof JCheckBox) {
            final JComponent component = (JComponent) child;
            final String text = child instanceof JLabel label ? label.getText() : ((JCheckBox) child).getText();
            if (text != null && !text.isEmpty()) {
                if (component.getClientProperty(BasicHTML.propertyKey) == null) {
                    // Keep the original caption and accessible name, including literal <, > and &.
                    BasicHTML.updateRenderer(
                            component,
                            "<html>"
                                    + text.replace("&", "&amp;")
                                            .replace("<", "&lt;")
                                            .replace(">", "&gt;")
                                            .replace("\n", "<br>")
                                    + "</html>");
                }
                final Object renderer = component.getClientProperty(BasicHTML.propertyKey);
                if (renderer instanceof View view) {
                    final int overhead = Math.max(
                            0,
                            component.getPreferredSize().width - (int) Math.ceil(view.getPreferredSpan(View.X_AXIS)));
                    view.setSize(Math.max(1, width - overhead), 0);
                }
            }
        }
        return child.getPreferredSize().height;
    }
}
