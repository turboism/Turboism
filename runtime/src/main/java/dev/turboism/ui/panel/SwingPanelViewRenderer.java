package dev.turboism.ui.panel;

import dev.turboism.sdk.action.UiActionEvent;
import dev.turboism.sdk.ui.PanelView;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
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
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiConsumer;

/** Renders immutable SDK panel values into runtime-owned Swing components. */
final class SwingPanelViewRenderer {

    private SwingPanelViewRenderer() { }

    static JComponent render(
        final PanelView view,
        final BiConsumer<String, Optional<UiActionEvent>> action
    ) {
        Objects.requireNonNull(view, "view");
        Objects.requireNonNull(action, "action");
        return renderNode(view, action);
    }

    private static JComponent renderNode(
        final PanelView view,
        final BiConsumer<String, Optional<UiActionEvent>> action
    ) {
        if (view instanceof PanelView.Column column) {
            return container(column.children(), BoxLayout.Y_AXIS, action);
        }
        if (view instanceof PanelView.Row row) {
            return container(row.children(), BoxLayout.X_AXIS, action);
        }
        if (view instanceof PanelView.Text text) {
            final javax.swing.JTextArea area = new javax.swing.JTextArea(text.value());
            area.setEditable(false);
            area.setFocusable(false);
            area.setOpaque(false);
            area.setBorder(null);
            area.setLineWrap(true);
            area.setWrapStyleWord(true);
            area.setFont(javax.swing.UIManager.getFont("Label.font"));
            if (text.grayed()) {
                final java.awt.Color grayed = javax.swing.UIManager.getColor("Label.disabledForeground");
                area.setForeground(grayed == null ? new java.awt.Color(0x999999) : grayed);
            }
            return area;
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
                renderNode(scroll.child(), action),
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
            );
        }
        if (view instanceof PanelView.Separator) {
            return new JSeparator();
        }
        throw new IllegalArgumentException("unsupported panel view: " + view.getClass().getName());
    }

    private static JPanel container(
        final java.util.List<PanelView> children,
        final int axis,
        final BiConsumer<String, Optional<UiActionEvent>> action
    ) {
        final JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, axis));
        for (int index = 0; index < children.size(); index++) {
            final JComponent child = renderNode(children.get(index), action);
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
