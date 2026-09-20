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
import java.awt.Window;
import java.util.Objects;

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
        dialog.setOpacity(0.0f);
        dialog.setSize(INVISIBLE_MODAL_WIDTH, INVISIBLE_MODAL_HEIGHT);
        dialog.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);
        return new InvisibleModal() {
            @Override
            public void show() {
                dialog.setLocationRelativeTo(ownerWindow(context));
                dialog.setVisible(true);
            }

            @Override
            public void dispose() {
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
        cancelButton.addActionListener(event -> context.cancelRequest().run());
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
