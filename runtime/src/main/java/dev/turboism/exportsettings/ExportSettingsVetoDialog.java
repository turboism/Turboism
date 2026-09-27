package dev.turboism.exportsettings;

import java.awt.Dialog;
import java.awt.GraphicsEnvironment;
import java.util.Objects;
import javax.swing.JDialog;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;

/**
 * User-visible surface for vetoed export-settings confirmations.
 *
 * <p>A checked option that cannot run — orchestration unwired, admission refused, or a
 * failed session — must never leave the confirmation dead. This dialog names the bounded
 * failure identity so the veto is visible instead of silent. The dialog is modeless and
 * is posted to the EDT: a veto reported inside the native decide gate appears only after
 * the native handler unwinds, and a failed session's report arrives off the EDT.</p>
 */
public final class ExportSettingsVetoDialog {

    /** Stable window identity for host-validation probes and tests. */
    public static final String DIALOG_NAME = "turboism.export-settings.veto";

    private ExportSettingsVetoDialog() {
    }

    /** Shows the diagnostic on the EDT and returns immediately. No-op when headless. */
    public static void present(final ExportSettingsVetoDiagnostic diagnostic) {
        Objects.requireNonNull(diagnostic, "diagnostic");
        if (GraphicsEnvironment.isHeadless()) {
            return;
        }
        final Runnable show = () -> show(diagnostic);
        if (SwingUtilities.isEventDispatchThread()) {
            show.run();
        } else {
            SwingUtilities.invokeLater(show);
        }
    }

    private static void show(final ExportSettingsVetoDiagnostic diagnostic) {
        final String detail = diagnostic.detail();
        final StringBuilder body = new StringBuilder(
            "The export was stopped by a Turboism export option.\n\n"
        ).append(diagnostic.key());
        if (detail != null && !detail.isBlank() && !detail.equals(diagnostic.key())) {
            body.append('\n').append(detail);
        }
        final JOptionPane pane = new JOptionPane(
            body.toString(), JOptionPane.WARNING_MESSAGE
        );
        final JDialog dialog = pane.createDialog(null, "Protected Export");
        dialog.setName(DIALOG_NAME);
        dialog.setModalityType(Dialog.ModalityType.MODELESS);
        dialog.setAlwaysOnTop(true);
        dialog.setLocationRelativeTo(null);
        dialog.setVisible(true);
        dialog.toFront();
    }
}
