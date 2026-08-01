package dev.turboism.ui;

import dev.turboism.sdk.ui.ChoiceDialogAction;
import dev.turboism.sdk.ui.ChoiceDialogOption;
import dev.turboism.sdk.ui.ChoiceDialogRequest;
import dev.turboism.sdk.ui.ChoiceDialogResultListener;
import dev.turboism.sdk.ui.ChoiceDialogRequest;

import javax.swing.BorderFactory;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Dialog;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Frame;
import java.awt.Window;
import java.lang.reflect.InvocationTargetException;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/** Runtime-owned Swing rendering for bounded SDK choice dialogs. */
final class RuntimeChoiceDialogs {

    private RuntimeChoiceDialogs() {
    }

    static Optional<String> choose(final ChoiceDialogRequest request) {
        if (java.awt.GraphicsEnvironment.isHeadless()) {
            return Optional.empty();
        }
        final DialogResult result;
        if (SwingUtilities.isEventDispatchThread()) {
            result = show(request);
        } else {
            final AtomicReference<DialogResult> ref = new AtomicReference<>();
            try {
                SwingUtilities.invokeAndWait(() -> ref.set(show(request)));
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return Optional.empty();
            } catch (InvocationTargetException exception) {
                return Optional.empty();
            }
            result = ref.get();
        }
        return result.actionId() == null
            ? Optional.ofNullable(result.optionId())
            : Optional.empty();
    }

    /**
     * Non-blocking dialog open. The dialog is shown on the EDT; the listener
     * receives {@code (optionId, actionId)} when the user acts. Accept and
     * cancel pass a {@code null} actionId; cancel passes a {@code null} optionId.
     */
    static void openAsync(
        final ChoiceDialogRequest request,
        final ChoiceDialogResultListener listener
    ) {
        if (java.awt.GraphicsEnvironment.isHeadless()) {
            listener.onResult(null, null);
            return;
        }
        final Runnable showTask = () -> {
            final DialogResult result = show(request);
            listener.onResult(result.optionId(), result.actionId());
        };
        if (SwingUtilities.isEventDispatchThread()) {
            showTask.run();
        } else {
            SwingUtilities.invokeLater(showTask);
        }
    }

    private static DialogResult show(final ChoiceDialogRequest request) {
        final Window owner = activeOwner();
        final JDialog dialog = owner instanceof Frame frame
            ? new JDialog(frame, request.title(), true)
            : owner instanceof Dialog parent
                ? new JDialog(parent, request.title(), true)
                : new JDialog((Frame) null, request.title(), true);
        final AtomicReference<String> selected = new AtomicReference<>();
        final JPanel content = new JPanel(new BorderLayout(0, 10));
        content.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

        if (!request.notice().isBlank()) {
            final JTextArea notice = textArea(request.notice());
            notice.setRows(Math.min(4, Math.max(1, request.notice().split("\\R", -1).length)));
            content.add(notice, BorderLayout.NORTH);
        }

        final JPanel center = new JPanel(new BorderLayout(0, 8));
        final DefaultComboBoxModel<ChoiceDialogOption> model = new DefaultComboBoxModel<>();
        request.options().forEach(model::addElement);
        final JComboBox<ChoiceDialogOption> choices = new JComboBox<>(model);
        choices.setRenderer((list, value, index, isSelected, cellHasFocus) -> {
            final JLabel label = new JLabel(value == null ? "" : value.label());
            label.setEnabled(value == null || value.enabled());
            if (isSelected) {
                label.setOpaque(true);
                label.setBackground(list.getSelectionBackground());
                label.setForeground(list.getSelectionForeground());
            }
            return label;
        });
        request.selectedOptionId().ifPresent(id -> select(choices, id));
        final JTextArea detail = textArea("");
        detail.setRows(8);
        detail.setColumns(48);
        final Runnable refresh = () -> {
            final ChoiceDialogOption option = (ChoiceDialogOption) choices.getSelectedItem();
            detail.setText(option == null ? "" : option.detail());
            detail.setCaretPosition(0);
        };
        choices.addActionListener(ignored -> refresh.run());
        refresh.run();
        center.add(choices, BorderLayout.NORTH);
        center.add(new JScrollPane(detail), BorderLayout.CENTER);
        content.add(center, BorderLayout.CENTER);

        final JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        final JButton accept = new JButton(request.acceptLabel());
        final JButton cancel = new JButton(request.cancelLabel());
        accept.addActionListener(ignored -> {
            final ChoiceDialogOption option = (ChoiceDialogOption) choices.getSelectedItem();
            if (option != null && option.enabled()) {
                dialog.dispose();
                selected.set(new DialogResult(option.id(), null).encode());
            }
        });
        cancel.addActionListener(ignored -> {
            dialog.dispose();
            selected.set(new DialogResult(null, null).encode());
        });
        for (ChoiceDialogAction action : request.actions()) {
            final JButton button = new JButton(action.label());
            button.addActionListener(ignored -> {
                final ChoiceDialogOption option = (ChoiceDialogOption) choices.getSelectedItem();
                dialog.dispose();
                selected.set(new DialogResult(
                    option == null ? null : option.id(),
                    action.id()
                ).encode());
            });
            buttons.add(button);
        }
        choices.addActionListener(ignored -> {
            final ChoiceDialogOption option = (ChoiceDialogOption) choices.getSelectedItem();
            accept.setEnabled(option != null && option.enabled());
        });
        final ChoiceDialogOption initial = (ChoiceDialogOption) choices.getSelectedItem();
        accept.setEnabled(initial != null && initial.enabled());
        buttons.add(accept);
        buttons.add(cancel);
        content.add(buttons, BorderLayout.SOUTH);

        dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
        dialog.setContentPane(content);
        dialog.pack();
        dialog.setMinimumSize(new Dimension(480, 340));
        dialog.setLocationRelativeTo(owner);
        dialog.setVisible(true);
        return DialogResult.decode(selected.get());
    }

    private static final class DialogResult {
        private final String optionId;
        private final String actionId;

        private DialogResult(final String optionId, final String actionId) {
            this.optionId = optionId;
            this.actionId = actionId;
        }

        private String optionId() {
            return optionId;
        }

        private String actionId() {
            return actionId;
        }

        private String encode() {
            return (optionId == null ? "\n" : optionId) + "\0" + (actionId == null ? "" : actionId);
        }

        private static DialogResult decode(final String encoded) {
            if (encoded == null) {
                return new DialogResult(null, null);
            }
            final int separator = encoded.indexOf('\0');
            if (separator < 0) {
                return new DialogResult(encoded, null);
            }
            final String option = encoded.substring(0, separator);
            final String action = encoded.substring(separator + 1);
            return new DialogResult(
                "\n".equals(option) ? null : option,
                action.isEmpty() ? null : action
            );
        }
    }

    private static JTextArea textArea(final String text) {
        final JTextArea area = new JTextArea(text);
        area.setEditable(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setOpaque(false);
        area.setFocusable(false);
        return area;
    }

    private static void select(final JComboBox<ChoiceDialogOption> choices, final String id) {
        for (int index = 0; index < choices.getItemCount(); index++) {
            if (choices.getItemAt(index).id().equals(id)) {
                choices.setSelectedIndex(index);
                return;
            }
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
