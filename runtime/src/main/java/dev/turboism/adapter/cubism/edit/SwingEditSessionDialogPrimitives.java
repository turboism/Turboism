package dev.turboism.adapter.cubism.edit;

import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.BorderLayout;
import java.awt.Dialog;
import java.awt.Frame;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Window;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Swing implementation of the session-UI primitives, used by the production {@link
 * SwingEditSessionUiLockFactory}. Pure JDK Swing — no reflective host access; the main window
 * handle arrives pre-resolved through verified aliases inside {@link
 * EditSessionUiLockContext#mainWindow()}.
 */
public final class SwingEditSessionDialogPrimitives implements EditSessionDialogPrimitives {

    /** Official invisible-dialog title. */
    static final String INVISIBLE_MODAL_TITLE = "Invisible Modal Dialog";
    /** Official invisible-dialog dimensions. */
    static final int INVISIBLE_MODAL_WIDTH = 300;
    static final int INVISIBLE_MODAL_HEIGHT = 200;

    @Override
    public InvisibleModal createInvisibleModal(final EditSessionUiLockContext context) {
        final JDialog dialog = new JDialog(ownerFrame(context), INVISIBLE_MODAL_TITLE);
        dialog.setModalityType(Dialog.ModalityType.APPLICATION_MODAL);
        dialog.setUndecorated(true);
        // The official interceptor is transparent. A display without per-window
        // translucency cannot honor setOpacity — a zero-size undecorated shell holds the
        // same modal input block there instead of failing session admission.
        if (translucencySupported()) {
            dialog.setOpacity(0.0f);
            dialog.setSize(INVISIBLE_MODAL_WIDTH, INVISIBLE_MODAL_HEIGHT);
        } else {
            dialog.setSize(0, 0);
        }
        dialog.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);
        final AtomicBoolean released = new AtomicBoolean();
        return new InvisibleModal() {
            @Override
            public void show() {
                // The official call site blocks its own handler on the modal's nested event
                // loop for the pulse duration; posting the show instead keeps the session's
                // dispatch task free while the invisible dialog still intercepts host input.
                SwingUtilities.invokeLater(() -> {
                    // A session admitted and terminated inside one dispatch task (the
                    // official WS dispatcher runs on the host UI thread, so a transient
                    // silent read opens and cancels before this queued show runs) reaches
                    // dispose() first. setVisible(true) on a disposed dialog re-creates
                    // its peer and leaves an ownerless APPLICATION_MODAL shell showing
                    // forever — the late show must be dropped.
                    if (released.get()) {
                        return;
                    }
                    dialog.setLocationRelativeTo(ownerWindow(context));
                    dialog.setVisible(true);
                });
            }

            @Override
            public void hide() {
                SwingUtilities.invokeLater(() -> {
                    if (!released.get()) {
                        dialog.setVisible(false);
                    }
                });
            }

            @Override
            public void dispose() {
                // The latch lands before the dialog is touched, so a show or hide already
                // queued behind this release can never resurrect the disposed dialog.
                released.set(true);
                dialog.setVisible(false);
                dialog.dispose();
            }
        };
    }

    @Override
    public StatusDialog createStatusDialog(final EditSessionUiLockContext context) {
        final JDialog dialog = new JDialog(ownerFrame(context), "Cubism Edit Session");
        final JTextArea logArea = new JTextArea(10, 40);
        logArea.setEditable(false);
        final JProgressBar progressBar = new JProgressBar(0, 1000);
        final JButton cancelButton = new JButton("Cancel");
        cancelButton.addActionListener(event -> {
            // A disposed or never-shown dialog can still receive synthetic clicks
            // (Window.getWindows() lists disposed shells until GC); only a live
            // dialog may raise the session's cancel request.
            if (dialog.isShowing()) {
                context.cancelRequest().run();
            }
        });
        dialog.getContentPane().setLayout(new BorderLayout());
        dialog.getContentPane().add(new JScrollPane(logArea), BorderLayout.CENTER);
        dialog.getContentPane().add(progressBar, BorderLayout.NORTH);
        dialog.getContentPane().add(cancelButton, BorderLayout.SOUTH);
        dialog.pack();
        dialog.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);
        return new StatusDialog() {
            @Override
            public void show() {
                dialog.setLocationRelativeTo(ownerWindow(context));
                dialog.setVisible(true);
            }

            @Override
            public void log(final String message) {
                logArea.append(message + "\n");
            }

            @Override
            public void progress(final double value) {
                progressBar.setValue((int) Math.round(value * 1000.0));
            }

            @Override
            public void dispose() {
                dialog.setVisible(false);
                dialog.dispose();
            }
        };
    }

    @Override
    public TimerHandle timer(final int delayMs, final Runnable action) {
        final Timer timer = new Timer(delayMs, event -> action.run());
        timer.setRepeats(false);
        timer.start();
        return timer::stop;
    }

    @Override
    public void setWindowEnabled(final Object window, final boolean enabled) {
        if (window instanceof Window swingWindow) {
            swingWindow.setEnabled(enabled);
        }
    }

    private static boolean translucencySupported() {
        try {
            return !GraphicsEnvironment.isHeadless()
                && GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice()
                    .isWindowTranslucencySupported(GraphicsDevice.WindowTranslucency.TRANSLUCENT);
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    private static Frame ownerFrame(final EditSessionUiLockContext context) {
        final Object window = context.mainWindow().orElse(null);
        return window instanceof Frame frame ? frame : null;
    }

    private static Window ownerWindow(final EditSessionUiLockContext context) {
        final Object window = context.mainWindow().orElse(null);
        return window instanceof Window swingWindow ? swingWindow : null;
    }

    /** Marker kept so static analysis sees this class is Swing-only, not a reflective shim. */
    static {
        Objects.requireNonNull(SwingUtilities.class);
    }
}
