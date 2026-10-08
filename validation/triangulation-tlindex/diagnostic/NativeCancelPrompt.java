import java.awt.Component;
import java.awt.Container;
import java.awt.Dialog;
import java.awt.Window;
import java.awt.event.ActionListener;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import javax.swing.JButton;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.UIManager;

/** Short-lived responder for the reviewed task-owned native mesh-cancel prompt only. */
final class NativeCancelPrompt implements AutoCloseable {
    @FunctionalInterface
    interface ContextCheck { void check() throws Exception; }

    private Window owner;
    private String message;
    private ContextCheck context;
    private final IdentityHashMap<Window, Boolean> baseline = new IdentityHashMap<>();
    private final long deadline;
    private final Timer timer;
    private String failure;
    private int answers;
    private boolean closed;

    NativeCancelPrompt(Window owner, String message, ContextCheck context, long timeoutMillis) {
        requireEdt();
        if (owner == null || !owner.isShowing() || message == null || message.isBlank()
                || context == null || timeoutMillis < 1 || timeoutMillis > 10_000) {
            throw new IllegalArgumentException("invalid task cancel responder binding");
        }
        this.owner = owner;
        this.message = message;
        this.context = context;
        deadline = System.nanoTime() + timeoutMillis * 1_000_000;
        for (Window window : Window.getWindows()) baseline.put(window, Boolean.TRUE);
        timer = new Timer(25, event -> poll());
        timer.start();
    }

    private void poll() {
        if (closed || answers != 0 || failure != null) return;
        if (System.nanoTime() >= deadline) {
            refuse("cancel prompt deadline expired");
            return;
        }
        try {
            context.check();
            List<Dialog> candidates = new ArrayList<>();
            for (Window window : Window.getWindows()) {
                if (!baseline.containsKey(window) && window.isShowing() && window.getOwner() == owner
                        && window instanceof Dialog dialog && dialog.isModal()) candidates.add(dialog);
            }
            if (candidates.isEmpty()) return;
            if (candidates.size() != 1) { refuse("ambiguous task modal dialogs"); return; }
            List<JOptionPane> panes = new ArrayList<>();
            collect(candidates.get(0), panes, new ArrayList<>(), new IdentityHashMap<>());
            if (panes.size() != 1) { refuse("cancel prompt pane count differs"); return; }
            JOptionPane pane = panes.get(0);
            if (!message.equals(pane.getMessage()) || pane.getOptionType() != JOptionPane.OK_CANCEL_OPTION
                    || pane.getMessageType() != JOptionPane.PLAIN_MESSAGE) {
                refuse("unreviewed cancel prompt message or type"); return;
            }
            Object[] options = pane.getOptions();
            if (options == null || options.length != 2 || !(options[0] instanceof JButton yes)
                    || !(options[1] instanceof JButton cancel) || yes == cancel
                    || pane.getInitialValue() != yes) {
                refuse("unreviewed cancel prompt options"); return;
            }
            List<JButton> buttons = new ArrayList<>();
            collect(pane, new ArrayList<>(), buttons, new IdentityHashMap<>());
            String yesLabel = normalize(UIManager.getString("OptionPane.yesButtonText"));
            String cancelLabel = normalize(UIManager.getString("OptionPane.cancelButtonText"));
            if (buttons.size() != 2 || !buttons.contains(yes) || !buttons.contains(cancel)
                    || yesLabel.isEmpty() || cancelLabel.isEmpty() || yesLabel.equals(cancelLabel)
                    || !yesLabel.equals(normalize(yes.getText()))
                    || !cancelLabel.equals(normalize(cancel.getText())) || !usable(yes) || !usable(cancel)) {
                refuse("unreviewed cancel prompt button shape"); return;
            }
            // Stop before entering the native action listener; never answer a second dialog.
            answers = 1;
            stopAndRelease();
            yes.doClick(0);
        } catch (Throwable failure) {
            refuse("cancel context or response failed: " + failure.getClass().getSimpleName());
        }
    }

    int requireSuccess() {
        requireEdt();
        if (failure != null) throw new IllegalStateException(failure);
        return answers;
    }

    boolean released() {
        requireEdt();
        return !timer.isRunning() && timer.getActionListeners().length == 0 && owner == null
            && message == null && context == null && baseline.isEmpty();
    }

    private void refuse(String reason) {
        failure = reason;
        stopAndRelease();
    }

    private void stopAndRelease() {
        timer.stop();
        for (ActionListener listener : timer.getActionListeners()) timer.removeActionListener(listener);
        owner = null;
        message = null;
        context = null;
        baseline.clear();
    }

    @Override public void close() {
        requireEdt();
        closed = true;
        stopAndRelease();
    }

    private static boolean usable(JButton button) {
        return button.isShowing() && button.isVisible() && button.isEnabled();
    }

    private static void collect(Component component, List<JOptionPane> panes, List<JButton> buttons,
                                IdentityHashMap<Component, Boolean> seen) {
        if (seen.put(component, Boolean.TRUE) != null) return;
        if (component instanceof JOptionPane pane) panes.add(pane);
        if (component instanceof JButton button) buttons.add(button);
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) collect(child, panes, buttons, seen);
        }
    }

    private static String normalize(String value) {
        if (value == null) return "";
        String trimmed = value.trim();
        int open = Math.max(trimmed.lastIndexOf('('), trimmed.lastIndexOf('\uFF08'));
        return open > 0 && (trimmed.endsWith(")") || trimmed.endsWith("\uFF09"))
            ? trimmed.substring(0, open).trim() : trimmed;
    }

    private static void requireEdt() {
        if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("cancel responder requires EDT");
    }
}
