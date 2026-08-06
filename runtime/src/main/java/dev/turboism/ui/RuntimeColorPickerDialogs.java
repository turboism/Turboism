package dev.turboism.ui;

import dev.turboism.sdk.ui.ColorPickerResultListener;

import javax.swing.JColorChooser;
import javax.swing.JDialog;
import javax.swing.SwingUtilities;
import java.awt.Color;
import java.awt.Dialog;
import java.awt.Frame;
import java.awt.Window;
import java.util.Locale;

/** Runtime-owned Swing rendering for the bounded SDK color picker. */
final class RuntimeColorPickerDialogs {

    private RuntimeColorPickerDialogs() {
    }

    static void openAsync(
        final String id,
        final String title,
        final String initialColorHex,
        final ColorPickerResultListener listener
    ) {
        if (java.awt.GraphicsEnvironment.isHeadless()) {
            listener.onResult(false, null);
            return;
        }
        final Runnable showTask = () -> show(title, initialColorHex, listener);
        if (SwingUtilities.isEventDispatchThread()) {
            showTask.run();
        } else {
            SwingUtilities.invokeLater(showTask);
        }
    }

    private static void show(
        final String title,
        final String initialColorHex,
        final ColorPickerResultListener listener
    ) {
        final Window owner = activeOwner();
        final Color initial = parseHex(initialColorHex);
        final Color chosen = JColorChooser.showDialog(
            owner instanceof Frame frame
                ? frame
                : owner instanceof Dialog parent
                    ? parent
                    : (Frame) null,
            title,
            initial == null ? Color.WHITE : initial
        );
        listener.onResult(chosen != null, chosen == null ? null : hex(chosen));
    }

    private static Window activeOwner() {
        final Window[] windows = Window.getWindows();
        for (int index = windows.length - 1; index >= 0; index--) {
            final Window window = windows[index];
            if (window.isShowing()) {
                return window;
            }
        }
        return null;
    }

    private static Color parseHex(final String hex) {
        if (hex == null || !hex.matches("#[0-9A-Fa-f]{6}")) {
            return null;
        }
        try {
            final int value = Integer.parseInt(hex.substring(1), 16);
            return new Color((value >> 16) & 0xFF, (value >> 8) & 0xFF, value & 0xFF);
        } catch (NumberFormatException invalid) {
            return null;
        }
    }

    private static String hex(final Color color) {
        return String.format(Locale.ROOT, "#%02X%02X%02X",
            color.getRed(), color.getGreen(), color.getBlue());
    }
}
