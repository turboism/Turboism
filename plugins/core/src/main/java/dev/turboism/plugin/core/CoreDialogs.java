package dev.turboism.plugin.core;

import javax.swing.JDialog;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;
import java.awt.Dialog;
import java.awt.Frame;
import java.awt.Window;

/** Shared window construction and message behavior for Turboism core UI. */
final class CoreDialogs {
    private CoreDialogs() { }

    static JDialog create(final String title, final int width, final int height) {
        final JDialog dialog = new JDialog(owner(), title, Dialog.ModalityType.MODELESS);
        dialog.setDefaultCloseOperation(WindowConstants.HIDE_ON_CLOSE);
        dialog.setSize(width, height);
        dialog.setLocationRelativeTo(dialog.getOwner());
        return dialog;
    }

    static void show(final JDialog dialog) {
        dialog.setLocationRelativeTo(dialog.getOwner());
        dialog.setVisible(true);
        dialog.toFront();
        dialog.requestFocus();
    }

    static void message(final Window owner, final String title, final String message) {
        final JOptionPane pane = new JOptionPane(message, JOptionPane.INFORMATION_MESSAGE);
        final JDialog dialog = pane.createDialog(owner, title);
        dialog.setVisible(true);
        dialog.dispose();
    }

    static boolean confirm(final Window owner, final String title, final String message) {
        final JOptionPane pane = new JOptionPane(
            message, JOptionPane.QUESTION_MESSAGE, JOptionPane.YES_NO_OPTION
        );
        final JDialog dialog = pane.createDialog(owner, title);
        dialog.setVisible(true);
        dialog.dispose();
        return Integer.valueOf(JOptionPane.YES_OPTION).equals(pane.getValue());
    }

    static void onEdt(final Runnable action) {
        if (SwingUtilities.isEventDispatchThread()) action.run(); else SwingUtilities.invokeLater(action);
    }

    private static Frame owner() {
        for (Frame frame : Frame.getFrames()) {
            if (frame.isVisible() && frame.getTitle() != null
                && frame.getTitle().contains("Live2D Cubism Editor")) return frame;
        }
        for (Frame frame : Frame.getFrames()) if (frame.isVisible()) return frame;
        return null;
    }
}
