package dev.turboism.sdk.ui.window;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Frame;
import java.awt.KeyboardFocusManager;
import java.awt.Window;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.BorderFactory;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;

/**
 * Reusable modeless progress window, centered on the visible Cubism Editor frame.
 * All operations may be called from worker threads. Closing is terminal and idempotent;
 * the window offers no cancellation because the caller owns the operation lifecycle.
 */
public final class TurboismProgressDialog implements AutoCloseable {
    private final Window owner;
    private final String title;
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile String message;
    private JDialog dialog;
    private JLabel label;
    private JProgressBar progress;
    private int value;
    private int maximum;

    /**
     * Creates a progress window that automatically selects the Cubism Editor as its owner.
     * Falls back to the active window's root owner, then the screen center.
     * @param title localized window title
     * @param message localized initial message
     */
    public TurboismProgressDialog(final String title, final String message) {
        this(null, title, message);
    }

    /**
     * Creates a progress window centered on an explicit owner, or automatically on Cubism.
     * @param owner owner window, or {@code null} for automatic selection
     * @param title localized window title
     * @param message localized initial message
     */
    public TurboismProgressDialog(final Window owner, final String title, final String message) {
        this.owner = owner;
        this.title = Objects.requireNonNull(title, "title");
        this.message = Objects.requireNonNull(message, "message");
    }

    /** Opens the window, or brings an existing window to the front. */
    public void show() {
        onEdt(() -> {
            if (closed.get()) return;
            if (dialog == null) {
                final Window parent = owner != null ? owner : editorOwner();
                dialog = TurboismWindowFactory.dialog(parent, title, false);
                if (dialog == null) return;
                dialog.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
                dialog.setResizable(false);
                label = new JLabel(message);
                progress = new JProgressBar();
                applyProgress();
                final JPanel content = new JPanel(new BorderLayout(0, 12));
                content.setBorder(BorderFactory.createEmptyBorder(18, 20, 18, 20));
                content.add(label, BorderLayout.NORTH);
                content.add(progress, BorderLayout.CENTER);
                dialog.setContentPane(content);
                dialog.pack();
                dialog.setSize(new Dimension(Math.max(380, dialog.getWidth()), dialog.getHeight()));
            }
            if (!dialog.isVisible()) dialog.setLocationRelativeTo(dialog.getOwner());
            dialog.setVisible(true);
            dialog.toFront();
        });
    }

    /** Brings an already open window to the front without moving it. */
    public void focus() {
        onEdt(() -> {
            if (dialog == null || closed.get()) return;
            dialog.toFront();
            dialog.requestFocus();
        });
    }

    /** @param text localized progress message */
    public void setMessage(final String text) {
        message = Objects.requireNonNull(text, "text");
        onEdt(() -> {
            if (label != null && !closed.get()) label.setText(message);
        });
    }

    /**
     * Switches to determinate progress. Before this call, progress is indeterminate.
     * @param value completed amount, between zero and maximum
     * @param maximum total amount, strictly positive
     */
    public void setProgress(final int value, final int maximum) {
        if (maximum <= 0 || value < 0 || value > maximum) {
            throw new IllegalArgumentException("progress must be between zero and a positive maximum");
        }
        onEdt(() -> {
            if (closed.get()) return;
            this.value = value;
            this.maximum = maximum;
            if (progress != null) applyProgress();
        });
    }

    /** Disposes the window; subsequent show calls have no effect. */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        onEdt(() -> {
            if (dialog != null) dialog.dispose();
            dialog = null;
            label = null;
            progress = null;
        });
    }

    private void applyProgress() {
        progress.setIndeterminate(maximum == 0);
        if (maximum > 0) {
            progress.setMaximum(maximum);
            progress.setValue(value);
        }
    }

    private static Window editorOwner() {
        for (Frame frame : Frame.getFrames()) {
            if (frame.isShowing() && frame.getTitle().contains("Live2D Cubism Editor")) return frame;
        }
        Window active = KeyboardFocusManager.getCurrentKeyboardFocusManager().getActiveWindow();
        while (active != null && active.getOwner() != null) active = active.getOwner();
        return active;
    }

    private static void onEdt(final Runnable action) {
        if (SwingUtilities.isEventDispatchThread()) action.run();
        else SwingUtilities.invokeLater(action);
    }
}
