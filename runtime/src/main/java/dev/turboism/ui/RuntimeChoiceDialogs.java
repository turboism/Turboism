package dev.turboism.ui;

import dev.turboism.sdk.ui.ChoiceDialogAction;
import dev.turboism.sdk.ui.ChoiceDialogOption;
import dev.turboism.sdk.ui.ChoiceDialogRequest;
import dev.turboism.sdk.ui.ChoiceDialogResultListener;

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
        final JPanel detailHost = new JPanel(new BorderLayout());
        final JTextArea detail = textArea("");
        detail.setRows(8);
        detail.setColumns(48);
        final JScrollPane detailScroll = new JScrollPane(detail);
        detailScroll.setBorder(javax.swing.BorderFactory.createEmptyBorder());
        final Runnable refresh = () -> {
            final ChoiceDialogOption option = (ChoiceDialogOption) choices.getSelectedItem();
            detailHost.removeAll();
            if (option != null && !option.detailRows().isEmpty()) {
                detailHost.add(detailRows(option.detailRows()), BorderLayout.CENTER);
            } else {
                detail.setText(option == null ? "" : option.detail());
                detail.setCaretPosition(0);
                detailHost.add(detailScroll, BorderLayout.CENTER);
            }
            detailHost.revalidate();
            detailHost.repaint();
        };
        choices.addActionListener(ignored -> refresh.run());
        refresh.run();
        center.add(choices, BorderLayout.NORTH);
        center.add(detailHost, BorderLayout.CENTER);
        content.add(center, BorderLayout.CENTER);

        final JPanel side = new JPanel(new BorderLayout(0, 8));
        side.setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 0));
        final JPanel buttons = new JPanel();
        buttons.setLayout(new javax.swing.BoxLayout(buttons, javax.swing.BoxLayout.Y_AXIS));
        final JPanel bottom = new JPanel();
        bottom.setLayout(new javax.swing.BoxLayout(bottom, javax.swing.BoxLayout.Y_AXIS));
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
        request.refresher().ifPresent(refresher -> {
            final JButton reload = new JButton(
                request.reloadLabel().isBlank() ? "Reload" : request.reloadLabel()
            );
            reload.addActionListener(ignored -> {
                final String previousId = ((ChoiceDialogOption) choices.getSelectedItem()) == null
                    ? null
                    : ((ChoiceDialogOption) choices.getSelectedItem()).id();
                model.removeAllElements();
                refresher.refresh().forEach(model::addElement);
                if (previousId != null) {
                    select(choices, previousId);
                }
                final ChoiceDialogOption option = (ChoiceDialogOption) choices.getSelectedItem();
                accept.setEnabled(option != null && option.enabled());
                refresh.run();
            });
            buttons.add(reload);
            buttons.add(javax.swing.Box.createVerticalStrut(4));
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
            buttons.add(javax.swing.Box.createVerticalStrut(4));
        }
        choices.addActionListener(ignored -> {
            final ChoiceDialogOption option = (ChoiceDialogOption) choices.getSelectedItem();
            accept.setEnabled(option != null && option.enabled());
        });
        final ChoiceDialogOption initial = (ChoiceDialogOption) choices.getSelectedItem();
        accept.setEnabled(initial != null && initial.enabled());
        bottom.add(accept);
        bottom.add(javax.swing.Box.createVerticalStrut(4));
        bottom.add(cancel);
        for (java.awt.Component component : buttons.getComponents()) {
            if (component instanceof JButton button) {
                button.setAlignmentX(java.awt.Component.CENTER_ALIGNMENT);
                button.setMaximumSize(new Dimension(Integer.MAX_VALUE, button.getPreferredSize().height));
            }
        }
        for (java.awt.Component component : bottom.getComponents()) {
            if (component instanceof JButton button) {
                button.setAlignmentX(java.awt.Component.CENTER_ALIGNMENT);
                button.setMaximumSize(new Dimension(Integer.MAX_VALUE, button.getPreferredSize().height));
            }
        }
        side.add(buttons, BorderLayout.NORTH);
        side.add(javax.swing.Box.createVerticalGlue(), BorderLayout.CENTER);
        side.add(bottom, BorderLayout.SOUTH);
        content.add(side, BorderLayout.EAST);

        dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
        dialog.setContentPane(content);
        dialog.pack();
        final Dimension packed = dialog.getPreferredSize();
        final int width = Math.max(480, Math.min(packed.width, 720));
        final int height = Math.max(340, Math.min(packed.height, 560));
        dialog.setSize(width, height);
        dialog.setMinimumSize(new Dimension(width, height));
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

    private static JPanel detailRows(final java.util.List<dev.turboism.sdk.ui.ChoiceDialogDetailRow> rows) {
        final JPanel panel = new JPanel(new java.awt.GridBagLayout());
        panel.setBorder(javax.swing.BorderFactory.createCompoundBorder(
            javax.swing.BorderFactory.createLineBorder(new java.awt.Color(0xCCCCCC)),
            javax.swing.BorderFactory.createEmptyBorder(8, 10, 8, 10)
        ));
        final java.awt.GridBagConstraints gbc = new java.awt.GridBagConstraints();
        gbc.insets = new java.awt.Insets(3, 4, 3, 8);
        gbc.anchor = java.awt.GridBagConstraints.NORTHWEST;
        int row = 0;
        for (dev.turboism.sdk.ui.ChoiceDialogDetailRow detail : rows) {
            gbc.gridx = 0;
            gbc.gridy = row;
            gbc.weightx = 0;
            gbc.fill = java.awt.GridBagConstraints.NONE;
            final JLabel label = new JLabel(detail.label());
            label.setFont(label.getFont().deriveFont(java.awt.Font.BOLD));
            panel.add(label, gbc);
            gbc.gridx = 1;
            gbc.weightx = 1.0;
            gbc.fill = java.awt.GridBagConstraints.HORIZONTAL;
            if (detail.url() != null && !detail.url().isBlank()) {
                panel.add(urlLabel(detail.value(), detail.url()), gbc);
            } else {
                panel.add(new JLabel(detail.value().isEmpty() ? "-" : detail.value()), gbc);
            }
            row++;
        }
        panel.revalidate();
        return panel;
    }

    private static JLabel urlLabel(final String text, final String url) {
        final String escaped = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        final JLabel link = new JLabel("<html><a href=''>" + escaped + "</a></html>");
        link.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
        link.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(final java.awt.event.MouseEvent event) {
                if (!java.awt.Desktop.isDesktopSupported()) {
                    return;
                }
                try {
                    java.awt.Desktop.getDesktop().browse(new java.net.URI(url));
                } catch (Exception ignored) {
                    // Browsing is best-effort; a missing browser must not fail the dialog.
                }
            }
        });
        return link;
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
        // Fall back to the first showing frame (e.g. the Cubism main window) so
        // dialogs opened right after another dialog closes keep a stable owner.
        for (Window window : Window.getWindows()) {
            if (window instanceof Frame frame && frame.isShowing()) {
                return frame;
            }
        }
        return null;
    }
}
