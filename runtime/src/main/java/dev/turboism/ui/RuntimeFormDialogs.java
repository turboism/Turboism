package dev.turboism.ui;

import dev.turboism.sdk.ui.ChoiceDialogAction;
import dev.turboism.sdk.ui.FormDialogField;
import dev.turboism.sdk.ui.FormDialogRequest;
import dev.turboism.sdk.ui.FormDialogResultListener;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JColorChooser;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dialog;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Frame;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Window;
import java.util.LinkedHashMap;
import java.util.Map;

/** Runtime-owned Swing rendering for bounded SDK form dialogs. */
final class RuntimeFormDialogs {

    private RuntimeFormDialogs() {
    }

    static void openAsync(
        final FormDialogRequest request,
        final FormDialogResultListener listener
    ) {
        if (java.awt.GraphicsEnvironment.isHeadless()) {
            listener.onResult(false, null, Map.of());
            return;
        }
        final Runnable showTask = () -> show(request, listener);
        if (SwingUtilities.isEventDispatchThread()) {
            showTask.run();
        } else {
            SwingUtilities.invokeLater(showTask);
        }
    }

    private static void show(
        final FormDialogRequest request,
        final FormDialogResultListener listener
    ) {
        final Window owner = activeOwner();
        final JDialog dialog = owner instanceof Frame frame
            ? new JDialog(frame, request.title(), true)
            : owner instanceof Dialog parent
                ? new JDialog(parent, request.title(), true)
                : new JDialog((Frame) null, request.title(), true);

        final JPanel content = new JPanel(new BorderLayout(0, 10));
        content.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

        final JPanel fields = new JPanel(new GridBagLayout());
        final GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(4, 4, 4, 8);
        gbc.anchor = GridBagConstraints.WEST;
        final Map<String, JTextField> textFields = new LinkedHashMap<>();
        final Map<String, JButton> colorButtons = new LinkedHashMap<>();
        final Map<String, JComboBox<String>> selectFields = new LinkedHashMap<>();
        int row = 0;
        for (FormDialogField field : request.fields()) {
            gbc.gridx = 0;
            gbc.gridy = row;
            gbc.weightx = 0;
            gbc.fill = GridBagConstraints.NONE;
            final JLabel label = new JLabel(field.label());
            fields.add(label, gbc);
            gbc.gridx = 1;
            gbc.weightx = 1.0;
            gbc.fill = GridBagConstraints.HORIZONTAL;
            if (field.kind() == dev.turboism.sdk.ui.FormFieldKind.COLOR) {
                final JPanel colorRow = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 4, 0));
                final JButton picker = new JButton(normalizeColor(field.value()));
                picker.setToolTipText(field.value());
                final JLabel swatch = new JLabel("  ");
                swatch.setOpaque(true);
                swatch.setBackground(parseColor(normalizeColor(field.value())));
                swatch.setBorder(BorderFactory.createLineBorder(new Color(0x999999)));
                final Runnable refreshSwatch = () -> {
                    final Color color = parseColor(picker.getText());
                    swatch.setBackground(color == null ? Color.BLACK : color);
                };
                picker.addActionListener(ignored -> {
                    final Color current = parseColor(field.value());
                    final Color chosen = JColorChooser.showDialog(
                        dialog,
                        field.label(),
                        current == null ? Color.WHITE : current
                    );
                    if (chosen != null) {
                        final String hex = String.format("#%02X%02X%02X",
                            chosen.getRed(), chosen.getGreen(), chosen.getBlue());
                        picker.setText(hex);
                        picker.setToolTipText(hex);
                        refreshSwatch.run();
                    }
                });
                refreshSwatch.run();
                colorRow.add(picker);
                colorRow.add(swatch);
                colorButtons.put(field.id(), picker);
                fields.add(colorRow, gbc);
            } else if (field.kind() == dev.turboism.sdk.ui.FormFieldKind.SELECT) {
                final JComboBox<String> combo = new JComboBox<>(
                    field.options().toArray(new String[0])
                );
                combo.setSelectedItem(field.value());
                if (combo.getSelectedIndex() < 0 && !field.options().isEmpty()) {
                    combo.setSelectedIndex(0);
                }
                selectFields.put(field.id(), combo);
                fields.add(combo, gbc);
            } else {
                final JTextField input = new JTextField(field.value());
                textFields.put(field.id(), input);
                fields.add(input, gbc);
            }
            row++;
        }
        content.add(fields, BorderLayout.CENTER);

        final JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        final JButton accept = new JButton(request.acceptLabel());
        final JButton cancel = new JButton(request.cancelLabel());
        accept.addActionListener(ignored -> {
            dialog.dispose();
            listener.onResult(true, null, collect(request, textFields, colorButtons, selectFields));
        });
        cancel.addActionListener(ignored -> {
            dialog.dispose();
            listener.onResult(false, null, Map.of());
        });
        for (ChoiceDialogAction action : request.actions()) {
            final JButton button = new JButton(action.label());
            button.addActionListener(ignored -> {
                dialog.dispose();
                listener.onResult(true, action.id(), collect(request, textFields, colorButtons, selectFields));
            });
            buttons.add(button);
        }
        buttons.add(accept);
        buttons.add(cancel);
        content.add(buttons, BorderLayout.SOUTH);

        dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
        dialog.setContentPane(content);
        dialog.pack();
        dialog.setMinimumSize(new Dimension(420, 320));
        dialog.setLocationRelativeTo(owner);
        dialog.setVisible(true);
    }

    private static Map<String, String> collect(
        final FormDialogRequest request,
        final Map<String, JTextField> textFields,
        final Map<String, JButton> colorButtons,
        final Map<String, JComboBox<String>> selectFields
    ) {
        final LinkedHashMap<String, String> values = new LinkedHashMap<>();
        for (FormDialogField field : request.fields()) {
            if (field.kind() == dev.turboism.sdk.ui.FormFieldKind.COLOR) {
                final JButton picker = colorButtons.get(field.id());
                values.put(field.id(), picker == null ? field.value() : normalizeColor(picker.getText()));
            } else if (field.kind() == dev.turboism.sdk.ui.FormFieldKind.SELECT) {
                final JComboBox<String> combo = selectFields.get(field.id());
                values.put(field.id(), combo == null ? field.value() : String.valueOf(combo.getSelectedItem()));
            } else {
                final JTextField input = textFields.get(field.id());
                values.put(field.id(), input == null ? field.value() : input.getText());
            }
        }
        return Map.copyOf(values);
    }

    private static String normalizeColor(final String value) {
        final Color color = parseColor(value);
        return color == null ? "#000000" : String.format("#%02X%02X%02X",
            color.getRed(), color.getGreen(), color.getBlue());
    }

    private static Color parseColor(final String value) {
        if (value == null || !value.matches("#[0-9A-Fa-f]{6}")) {
            return null;
        }
        try {
            return Color.decode(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static Window activeOwner() {
        final Window active = java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().getActiveWindow();
        if (active != null && active.isShowing()) {
            return active;
        }
        for (Window window : Window.getWindows()) {
            if (window.isShowing() && window.isActive()) {
                return window;
            }
        }
        return null;
    }
}
