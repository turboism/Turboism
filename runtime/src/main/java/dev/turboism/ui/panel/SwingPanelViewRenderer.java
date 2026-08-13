package dev.turboism.ui.panel;

import dev.turboism.sdk.action.UiActionEvent;
import dev.turboism.sdk.ui.PanelView;

import javax.imageio.ImageIO;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSeparator;
import javax.swing.JTextField;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.io.ByteArrayInputStream;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiConsumer;

/** Renders immutable SDK panel values into runtime-owned Swing components. */
public final class SwingPanelViewRenderer {

    private SwingPanelViewRenderer() { }

    public static JComponent render(
        final PanelView view,
        final BiConsumer<String, Optional<UiActionEvent>> action
    ) {
        Objects.requireNonNull(view, "view");
        Objects.requireNonNull(action, "action");
        return renderNode(view, action, false, dev.turboism.i18n.CubismHostLocale.resolve());
    }
    public static JComponent render(
        final PanelView view,
        final BiConsumer<String, Optional<UiActionEvent>> action,
        final java.util.Locale locale
    ) {
        Objects.requireNonNull(view, "view");
        Objects.requireNonNull(action, "action");
        return renderNode(view, action, false, locale);
    }

    private static JComponent renderNode(
        final PanelView view,
        final BiConsumer<String, Optional<UiActionEvent>> action,
        final boolean chartTitleSuppressed,
        final java.util.Locale locale
    ) {
        if (view instanceof PanelView.Column column) {
            return container(column.children(), BoxLayout.Y_AXIS, action, chartTitleSuppressed, locale);
        }
        if (view instanceof PanelView.Row row) {
            return container(row.children(), BoxLayout.X_AXIS, action, chartTitleSuppressed, locale);
        }

        if (view instanceof PanelView.Chart chart) {
            final ChartComponent component = new ChartComponent(chart, !chartTitleSuppressed);
            component.setName(chart.id());
            return component;
        }
        if (view instanceof PanelView.Text text) {
            // JLabel rendering (main baseline) with grayed support for the
            // history-panel redo entries; word wrap stays a future enhancement.
            final JLabel label = new JLabel(text.value());
            if (text.grayed()) {
                final java.awt.Color grayed = javax.swing.UIManager.getColor("Label.disabledForeground");
                label.setForeground(grayed == null ? new java.awt.Color(0x999999) : grayed);
            }
            return label;
        }
        if (view instanceof PanelView.Image image) {
            final JLabel label = new JLabel();
            label.setName("panel-image");
            label.setIcon(icon(image.pngBytes()));
            label.setToolTipText(image.altText());
            label.setPreferredSize(new Dimension(label.getIcon().getIconWidth(), label.getIcon().getIconHeight()));
            return label;
        }
        if (view instanceof PanelView.Button button) {
            final JButton component = new JButton(button.label());
            component.setName(button.id());
            component.addActionListener(ignored -> action.accept(button.actionId(), Optional.empty()));
            return component;
        }
        if (view instanceof PanelView.TextInput input) {
            final JTextField component = new JTextField(input.value());
            component.setName(input.id());
            component.addActionListener(ignored -> action.accept(
                input.actionId(),
                Optional.of(UiActionEvent.text(input.id(), component.getText()))
            ));
            return labelled(input.label(), component);
        }
        if (view instanceof PanelView.Select select) {
            final JComboBox<PanelView.Option> component = new JComboBox<>(
                select.options().toArray(PanelView.Option[]::new)
            );
            component.setName(select.id());
            component.setSelectedItem(select.options().stream()
                .filter(option -> option.value().equals(select.selectedValue()))
                .findFirst()
                .orElseThrow());
            component.addActionListener(ignored -> {
                final PanelView.Option selected = (PanelView.Option) component.getSelectedItem();
                if (selected != null) {
                    action.accept(
                        select.actionId(),
                        Optional.of(UiActionEvent.selection(select.id(), selected.value()))
                    );
                }
            });
            return labelled(select.label(), component);
        }
        if (view instanceof PanelView.Toggle toggle) {
            final JCheckBox component = new JCheckBox(toggle.label(), toggle.selected());
            component.setName(toggle.id());
            component.addActionListener(ignored -> action.accept(
                toggle.actionId(),
                Optional.of(UiActionEvent.toggle(toggle.id(), component.isSelected()))
            ));
            return component;
        }
        if (view instanceof PanelView.Scroll scroll) {
            return new JScrollPane(
                renderNode(scroll.child(), action, false, locale),
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
            );
        }

        if (view instanceof PanelView.CollapsibleSection section) {
            // A section holding exactly one chart already carries the chart
            // label as its border title; suppress the duplicated inner title
            // (runtime-private single-chart optimization, no SDK change).
            final boolean singleChart = section.children().size() == 1
                && section.children().get(0) instanceof PanelView.Chart;
            return CollapsibleSection.create(
                section.title(),
                container(section.children(), BoxLayout.Y_AXIS, action, singleChart, locale),
                section.expandedByDefault(),
                locale
            );
        }
        if (view instanceof PanelView.Separator) {
            return new JSeparator();
        }
        throw new IllegalArgumentException("unsupported panel view: " + view.getClass().getName());
    }

    private static Icon icon(final byte[] pngBytes) {
        try {
            return new ImageIcon(ImageIO.read(new ByteArrayInputStream(pngBytes)));
        } catch (Exception failure) {
            throw new IllegalArgumentException("pngBytes must be a readable PNG", failure);
        }
    }

    private static JPanel container(
        final java.util.List<PanelView> children,
        final int axis,
        final BiConsumer<String, Optional<UiActionEvent>> action,
        final boolean chartTitleSuppressed,
        final java.util.Locale locale
    ) {
        final JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, axis));
        for (int index = 0; index < children.size(); index++) {
            final JComponent child = renderNode(children.get(index), action, chartTitleSuppressed, locale);
            if (axis == BoxLayout.X_AXIS) {
                child.setAlignmentY(Component.CENTER_ALIGNMENT);
            } else {
                child.setAlignmentX(Component.LEFT_ALIGNMENT);
            }
            panel.add(child);
            if (index + 1 < children.size()) {
                panel.add(axis == BoxLayout.X_AXIS
                    ? Box.createHorizontalStrut(8)
                    : Box.createVerticalStrut(8));
            }
        }
        return panel;
    }

    private static JPanel labelled(final String label, final JComponent control) {
        final JPanel panel = new JPanel(new BorderLayout(8, 0));
        final JLabel caption = new JLabel(label);
        caption.setLabelFor(control);
        panel.add(caption, BorderLayout.WEST);
        panel.add(control, BorderLayout.CENTER);
        panel.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 0));
        return panel;
    }
}
