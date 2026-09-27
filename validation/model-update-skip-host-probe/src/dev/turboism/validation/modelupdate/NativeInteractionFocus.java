package dev.turboism.validation.modelupdate;

import java.awt.Component;
import java.awt.EventQueue;
import java.awt.KeyboardFocusManager;
import java.awt.Window;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.Supplier;
import javax.swing.JComponent;
import javax.swing.SwingUtilities;

/** Test-only task-window acquisition. Never sends input or repeatedly raises a window. */
final class NativeInteractionFocus {
    private NativeInteractionFocus() { }
    record State(boolean windowReady, boolean owned, String diagnostics) { }
    record Acquisition(boolean acquired, State last, String diagnostics) { }
    interface Driver {
        State observe() throws Exception;
        void requestWindow() throws Exception;
        boolean requestCanvas() throws Exception;
        void pause() throws Exception;
        long nanoTime();
    }

    static Acquisition acquire(Driver driver, long timeoutNanos) throws Exception {
        if (EventQueue.isDispatchThread()) throw new IllegalStateException("focus wait must not block EDT");
        if (timeoutNanos <= 0) throw new IllegalArgumentException("positive focus timeout required");
        long began = driver.nanoTime();
        State before = driver.observe();
        if (before.owned()) return result(before, before, null, 0);
        // A native activation request is asynchronous. requestFocusInWindow
        // cannot activate a window, so defer it until the window really owns
        // focus. Raise the task window only once; never chase external focus.
        driver.requestWindow();
        Boolean accepted = null;
        int polls = 0;
        while (true) {
            State after = driver.observe();
            if (after.owned()) return result(before, after, accepted, polls);
            if (accepted == null && after.windowReady()) {
                accepted = driver.requestCanvas();
                after = driver.observe();
                if (after.owned()) return result(before, after, accepted, polls);
            }
            if (driver.nanoTime() - began >= timeoutNanos) return result(before, after, accepted, polls);
            driver.pause();
            polls++;
        }
    }
    private static Acquisition result(State before, State after, Boolean accepted, int polls) {
        return new Acquisition(after.owned(), after,
            "schemaVersion=1\nrequestFocusInWindowAccepted=" + (accepted == null ? "not-requested" : accepted)
                + "\npolls=" + polls + "\nbefore." + before.diagnostics().replace("\n", "\nbefore.")
                + "\nafter." + after.diagnostics().replace("\n", "\nafter.") + "\n");
    }

    static boolean windowReady(Window window) {
        var manager = KeyboardFocusManager.getCurrentKeyboardFocusManager();
        return window != null && manager.getFocusedWindow() == window && manager.getActiveWindow() == window
            && window.isFocused() && window.isActive();
    }
    static boolean owns(JComponent canvas, Window window) {
        var manager = KeyboardFocusManager.getCurrentKeyboardFocusManager();
        Component owner = manager.getFocusOwner();
        return windowReady(window) && owner != null && canvas != null
            && SwingUtilities.getWindowAncestor(canvas) == window
            && (owner == canvas || SwingUtilities.isDescendingFrom(owner, canvas));
    }
    static State observe(JComponent canvas, Window window) {
        if (!EventQueue.isDispatchThread()) throw new IllegalStateException("focus observation requires EDT");
        var manager = KeyboardFocusManager.getCurrentKeyboardFocusManager();
        String diagnostics = "focusOwner=" + identity(manager.getFocusOwner())
            + "\nfocusedWindow=" + identity(manager.getFocusedWindow())
            + "\nactiveWindow=" + identity(manager.getActiveWindow())
            + "\ntaskWindow=" + identity(window) + "\ncanvas=" + identity(canvas)
            + "\nwindow.isFocused=" + (window != null && window.isFocused())
            + "\nwindow.isActive=" + (window != null && window.isActive())
            + "\ncanvas.isShowing=" + (canvas != null && canvas.isShowing())
            + "\ncanvas.isFocusable=" + (canvas != null && canvas.isFocusable())
            + "\ncanvas.isEnabled=" + (canvas != null && canvas.isEnabled())
            + "\ncanvas.window=" + identity(canvas == null ? null : SwingUtilities.getWindowAncestor(canvas))
            + "\ninputPath.preference=" + System.getProperty("turboism.optimization.inputPathElision", "unset")
            + "\nTURBOISM_PROTON=" + System.getenv("TURBOISM_PROTON") + "\n" + inputPathCounters();
        return new State(windowReady(window), owns(canvas, window), diagnostics);
    }
    private static String inputPathCounters() {
        try {
            Object slot = System.getProperties().get("turboism.input-path.stats");
            if (!(slot instanceof Supplier<?> supplier) || !(supplier.get() instanceof Map<?, ?> counters)) {
                return "inputPath.status=unavailable";
            }
            StringBuilder report = new StringBuilder("inputPath.status=available");
            for (String key : new String[] {"armed", "focusCalls", "focusElided", "focusPassed", "observerFailures"}) {
                Object value = counters.get(key);
                report.append("\ninputPath.").append(key).append('=')
                    .append(value instanceof Number n ? n.longValue() : "unknown");
            }
            return report.toString();
        } catch (Throwable unavailable) {
            return "inputPath.status=unavailable\ninputPath.observerFailure=" + unavailable.getClass().getName();
        }
    }
    static IllegalStateException refused(Path state, String preparation, State current) {
        String diagnostics = preparation + "\nfailure." + current.diagnostics().replace("\n", "\nfailure.") + "\n";
        IllegalStateException failure = new IllegalStateException(
            "task canvas does not own keyboard focus; refusing key input; "
                + current.diagnostics().replace('\n', ' '));
        try { Files.writeString(state.resolve("interaction-focus.txt"), diagnostics); }
        catch (Exception writeFailure) { failure.addSuppressed(writeFailure); }
        return failure;
    }
    private static String identity(Object value) {
        return value == null ? "none" : value.getClass().getName() + "@" + System.identityHashCode(value);
    }
}
