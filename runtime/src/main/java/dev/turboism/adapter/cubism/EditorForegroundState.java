package dev.turboism.adapter.cubism;

import java.awt.GraphicsEnvironment;
import java.awt.Window;

/**
 * Best-effort process-level desktop foreground probe.
 *
 * <p>The Cubism Editor is a Swing application, so every Editor window is an AWT
 * {@link Window} in this process. A window is active only while it owns the desktop focus;
 * when the user switches to another application the previously active Editor window reports
 * {@link Window#isActive()} false. Checking all live windows (not only the main frame) keeps
 * the signal true while an Editor dialog is focused.</p>
 *
 * <p>The probe never throws. In a headless JVM, or when AWT cannot enumerate windows, it
 * fails closed with {@code false}.</p>
 */
public final class EditorForegroundState {

    private EditorForegroundState() {}

    /** Returns whether any showing top-level window in this process is active. */
    public static boolean isForeground() {
        if (GraphicsEnvironment.isHeadless()) {
            return false;
        }
        try {
            for (Window window : Window.getWindows()) {
                if (window != null && window.isShowing() && window.isActive()) {
                    return true;
                }
            }
        } catch (ThreadDeath | VirtualMachineError fatal) {
            throw fatal;
        } catch (RuntimeException ignored) {
            return false;
        }
        return false;
    }
}
