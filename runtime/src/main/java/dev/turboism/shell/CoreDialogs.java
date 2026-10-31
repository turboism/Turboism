package dev.turboism.shell;

import dev.turboism.sdk.ui.window.TurboismWindowFactory;
import dev.turboism.ui.host.EdtDispatch;
import java.awt.Frame;
import java.awt.Window;
import javax.swing.JDialog;
import javax.swing.JOptionPane;
import javax.swing.WindowConstants;

/** Shared window construction and message behavior for Turboism core UI. */
final class CoreDialogs {
    private CoreDialogs() {}

    static JDialog create(final String title, final int width, final int height) {
        final JDialog dialog = TurboismWindowFactory.dialog(owner(), title, false);
        if (dialog == null) return null;
        dialog.setDefaultCloseOperation(WindowConstants.HIDE_ON_CLOSE);
        dialog.setSize(width, height);
        dialog.setLocationRelativeTo(dialog.getOwner());
        return dialog;
    }

    static void show(final JDialog dialog) {
        if (dialog == null) return;
        dialog.setLocationRelativeTo(dialog.getOwner());
        dialog.setVisible(true);
        dialog.toFront();
        dialog.requestFocus();
    }

    static void message(final Window owner, final String title, final String message) {
        final JOptionPane pane = new JOptionPane(message, JOptionPane.INFORMATION_MESSAGE);
        final JDialog dialog = pane.createDialog(owner, title);
        TurboismWindowFactory.style(dialog);
        dialog.setVisible(true);
        dialog.dispose();
    }

    /** Scrollable, selectable diagnostics that remain readable when all performance fields are shown. */
    static void scrollableMessage(final Window owner, final String title, final String message) {
        final javax.swing.JTextArea text = new javax.swing.JTextArea(message, 24, 88);
        text.setEditable(false);
        text.setLineWrap(true);
        text.setWrapStyleWord(true);
        text.setCaretPosition(0);
        final JOptionPane pane = new JOptionPane(new javax.swing.JScrollPane(text), JOptionPane.INFORMATION_MESSAGE);
        final JDialog dialog = pane.createDialog(owner, title);
        TurboismWindowFactory.style(dialog);
        dialog.setResizable(true);
        dialog.setVisible(true);
        dialog.dispose();
    }

    static boolean confirm(final Window owner, final String title, final String message) {
        final JOptionPane pane = new JOptionPane(message, JOptionPane.QUESTION_MESSAGE, JOptionPane.YES_NO_OPTION);
        final JDialog dialog = pane.createDialog(owner, title);
        TurboismWindowFactory.style(dialog);
        dialog.setVisible(true);
        dialog.dispose();
        return Integer.valueOf(JOptionPane.YES_OPTION).equals(pane.getValue());
    }

    static boolean confirmScrollable(
            final Window owner, final String title, final String message,
            final String accept, final String keep) {
        final javax.swing.JTextArea text = new javax.swing.JTextArea(message, 18, 72);
        text.setEditable(false);
        text.setLineWrap(true);
        text.setWrapStyleWord(true);
        text.setCaretPosition(0);
        final JOptionPane pane = new JOptionPane(new javax.swing.JScrollPane(text),
                JOptionPane.QUESTION_MESSAGE, JOptionPane.YES_NO_OPTION, null,
                new Object[] {accept, keep}, keep);
        final JDialog dialog = pane.createDialog(owner, title);
        TurboismWindowFactory.style(dialog);
        dialog.setResizable(true);
        dialog.setVisible(true);
        dialog.dispose();
        return accept.equals(pane.getValue());
    }

    static void onEdt(final Runnable action) {
        EdtDispatch.post("core shell dialog", action);
    }

    private static Frame owner() {
        for (Frame frame : Frame.getFrames()) {
            if (frame.isVisible()
                    && frame.getTitle() != null
                    && frame.getTitle().contains("Live2D Cubism Editor")) return frame;
        }
        for (Frame frame : Frame.getFrames()) if (frame.isVisible()) return frame;
        return null;
    }
}
