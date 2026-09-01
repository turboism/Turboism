package dev.turboism.plugin.psdclipmaskimport;

import dev.turboism.sdk.i18n.PluginLocalization;
import dev.turboism.sdk.ui.window.TurboismWindowFactory;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.BorderFactory;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;

/** Modeless, indeterminate progress window for the atomic Cubism import batch. */
final class PsdClipMaskImportProgressDialog implements PsdClipMaskImportProgress {

    private final String title;
    private final String preparingText;
    private final String confirmationText;
    private final String applyingText;
    private final AtomicReference<JDialog> dialog = new AtomicReference<>();
    private final AtomicReference<JLabel> label = new AtomicReference<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile String message;

    PsdClipMaskImportProgressDialog(final PluginLocalization localization) {
        final PluginLocalization i18n = Objects.requireNonNull(localization, "localization");
        title = i18n.text("psd.clip-mask-import.progress.title");
        preparingText = i18n.text("psd.clip-mask-import.progress.preparing");
        confirmationText = i18n.text("psd.clip-mask-import.progress.confirming");
        applyingText = i18n.text("psd.clip-mask-import.progress.applying");
        message = preparingText;
    }

    @Override
    public void show() {
        onEdt(() -> {
            if (closed.get()) return;
            JDialog current = dialog.get();
            if (current == null) {
                current = createDialog();
                if (current == null) return;
                if (!dialog.compareAndSet(null, current)) {
                    current.dispose();
                    current = dialog.get();
                }
            }
            if (current != null) {
                current.setVisible(true);
                current.toFront();
            }
        });
    }

    @Override public void preparing() { update(preparingText); }
    @Override public void awaitingConfirmation() { update(confirmationText); }
    @Override public void applying() { update(applyingText); }

    @Override
    public void focus() {
        onEdt(() -> {
            final JDialog current = dialog.get();
            if (current == null || closed.get()) return;
            current.setVisible(true);
            current.toFront();
            current.requestFocus();
        });
    }

    @Override
    public boolean cancellationRequested() {
        return false;
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        onEdt(() -> {
            label.set(null);
            final JDialog current = dialog.getAndSet(null);
            if (current != null) current.dispose();
        });
    }

    private void update(final String text) {
        message = text;
        onEdt(() -> {
            final JLabel current = label.get();
            if (current != null && !closed.get()) current.setText(message);
        });
    }

    private JDialog createDialog() {
        final JDialog created = TurboismWindowFactory.dialog(null, title, false);
        if (created == null) return null;
        created.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        created.setResizable(false);

        final JLabel messageLabel = new JLabel(message);
        label.set(messageLabel);
        final JProgressBar progress = new JProgressBar();
        progress.setIndeterminate(true);

        final JPanel content = new JPanel(new BorderLayout(0, 12));
        content.setBorder(BorderFactory.createEmptyBorder(18, 20, 18, 20));
        content.add(messageLabel, BorderLayout.NORTH);
        content.add(progress, BorderLayout.CENTER);
        created.setContentPane(content);
        created.pack();
        created.setMinimumSize(new Dimension(380, created.getHeight()));
        created.setLocationByPlatform(true);
        return created;
    }

    private static void onEdt(final Runnable action) {
        if (SwingUtilities.isEventDispatchThread()) {
            action.run();
        } else {
            SwingUtilities.invokeLater(action);
        }
    }
}
