package dev.turboism.shell;

import dev.turboism.sdk.ui.window.TurboismWindowFactory;
import dev.turboism.ui.host.EdtDispatch;
import java.awt.Frame;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
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
        final Rectangle usable = usableBounds();
        final int fittedWidth = Math.min(width, usable.width);
        final int fittedHeight = Math.min(height, usable.height);
        dialog.setSize(fittedWidth, fittedHeight);
        dialog.setLocation(
                usable.x + Math.max(0, (usable.width - fittedWidth) / 2),
                usable.y + Math.max(0, (usable.height - fittedHeight) / 2));
        return dialog;
    }

    static void show(final JDialog dialog) {
        if (dialog == null) return;
        dialog.setLocationRelativeTo(dialog.getOwner());
        fitIntoUsableArea(dialog);
        dialog.setVisible(true);
        dialog.toFront();
        dialog.requestFocus();
    }

    /**
     * Keeps a dialog inside the usable desktop area (screen minus taskbar).
     *
     * <p>High display scaling plus a maximized window can push a dialog footer - the install and
     * restart buttons of the update window, for example - behind the taskbar. Windows are resized
     * and repositioned, never re-laid out, so their content keeps its own scrolling behavior.</p>
     */
    static void fitIntoUsableArea(final JDialog dialog) {
        final Rectangle usable = usableBounds();
        final Rectangle bounds = dialog.getBounds();
        final int width = Math.min(bounds.width, usable.width);
        final int height = Math.min(bounds.height, usable.height);
        final int x = Math.min(Math.max(bounds.x, usable.x), usable.x + usable.width - width);
        final int y = Math.min(Math.max(bounds.y, usable.y), usable.y + usable.height - height);
        if (!bounds.equals(new Rectangle(x, y, width, height))) dialog.setBounds(x, y, width, height);
    }

    /** Usable desktop rectangle for the default screen; falls back to the full screen when unknown. */
    static Rectangle usableBounds() {
        final Rectangle usable =
                GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
        if (usable == null || usable.width <= 0 || usable.height <= 0) {
            return new Rectangle(java.awt.Toolkit.getDefaultToolkit().getScreenSize());
        }
        return usable;
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
            final Window owner, final String title, final String message, final String accept, final String keep) {
        final javax.swing.JTextArea text = new javax.swing.JTextArea(message, 18, 72);
        text.setEditable(false);
        text.setLineWrap(true);
        text.setWrapStyleWord(true);
        text.setCaretPosition(0);
        final JOptionPane pane = new JOptionPane(
                new javax.swing.JScrollPane(text),
                JOptionPane.QUESTION_MESSAGE,
                JOptionPane.YES_NO_OPTION,
                null,
                new Object[] {accept, keep},
                keep);
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
