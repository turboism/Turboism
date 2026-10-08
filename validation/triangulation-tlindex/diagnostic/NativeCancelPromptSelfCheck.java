import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.UIManager;

/** Real own JVM modal loops and negative controls; no official classes or Editor process. */
public final class NativeCancelPromptSelfCheck {
    private static final String MESSAGE = "Own fixture mesh cancellation";
    private static int checks;
    private NativeCancelPromptSelfCheck() {}

    private record Prompt(JOptionPane pane, JDialog dialog, JButton yes, JButton cancel) {}

    private static Prompt prompt(JFrame owner, AtomicInteger clicks, String message) {
        JButton yes = new JButton(UIManager.getString("OptionPane.yesButtonText") + " (Y)");
        JButton cancel = new JButton(UIManager.getString("OptionPane.cancelButtonText") + " (C)");
        JOptionPane pane = new JOptionPane(message, JOptionPane.PLAIN_MESSAGE, JOptionPane.OK_CANCEL_OPTION,
            null, new Object[] {yes, cancel}, yes);
        yes.addActionListener(event -> { clicks.incrementAndGet(); pane.setValue(yes); });
        cancel.addActionListener(event -> { clicks.addAndGet(100); pane.setValue(cancel); });
        return new Prompt(pane, pane.createDialog(owner, "Own modal fixture"), yes, cancel);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }

    private static void modalCase(JFrame owner, JFrame foreign, String kind) {
        AtomicInteger clicks = new AtomicInteger();
        Prompt before = kind.equals("baseline") ? prompt(owner, clicks, MESSAGE) : null;
        NativeCancelPrompt responder = new NativeCancelPrompt(owner, MESSAGE, () -> {
            if (kind.equals("context")) throw new IllegalStateException("own document changed");
            if (kind.equals("context-error")) throw new AssertionError("own observer error");
        }, kind.equals("foreign") || kind.equals("baseline") ? 100 : 1_000);
        Prompt p = before != null ? before : prompt(kind.equals("foreign") ? foreign : owner, clicks,
            kind.equals("unknown") ? "Own unsaved-changes prompt" : MESSAGE);
        Prompt duplicate = kind.equals("duplicate-dialogs") ? prompt(owner, clicks, MESSAGE) : null;
        if (kind.equals("disabled")) p.cancel().setEnabled(false);
        if (kind.equals("wrong-default")) p.pane().setInitialValue(p.cancel());
        if (kind.equals("wrong-type")) p.pane().setMessageType(JOptionPane.QUESTION_MESSAGE);
        if (kind.equals("duplicate-yes")) p.cancel().setText(p.yes().getText());
        if (kind.equals("extra-button")) p.pane().add(new JButton("extra"));
        if (kind.equals("duplicate-panes")) {
            JPanel panel = new JPanel();
            panel.add(p.pane());
            panel.add(new JOptionPane(MESSAGE));
            p.dialog().setContentPane(panel);
        }
        p.dialog().pack();
        // Test-only watchdog: the responder must never dismiss a refused/foreign dialog itself.
        Timer watchdog = new Timer(400, event -> {
            if (duplicate != null) duplicate.dialog().dispose();
            p.dialog().dispose();
        });
        watchdog.setRepeats(false);
        boolean success = false;
        int answers = -1;
        try (responder) {
            watchdog.start();
            if (duplicate != null) SwingUtilities.invokeLater(() -> duplicate.dialog().setVisible(true));
            p.dialog().setVisible(true);
            answers = responder.requireSuccess();
            success = true;
        } catch (IllegalStateException rejected) {
            check(!kind.equals("valid"), "valid prompt was refused: " + rejected.getMessage());
        } finally {
            watchdog.stop();
            p.dialog().dispose();
            if (duplicate != null) duplicate.dialog().dispose();
        }
        check(success == kind.equals("valid"), "unexpected acceptance: " + kind);
        check(clicks.get() == (kind.equals("valid") ? 1 : 0), "unexpected choice: " + kind);
        if (success) check(answers == 1, "exactly one Yes answer required");
        check(responder.released(), "callback/owner/context retained: " + kind);
    }

    private static void run() {
        JFrame owner = new JFrame("Own task fixture");
        JFrame foreign = new JFrame("Own foreign fixture");
        owner.setSize(280, 120); owner.setVisible(true);
        foreign.setSize(280, 120); foreign.setVisible(true);
        try {
            for (String kind : new String[] {"valid", "unknown", "foreign", "baseline", "disabled",
                    "wrong-default", "wrong-type", "duplicate-yes", "extra-button", "duplicate-panes",
                    "duplicate-dialogs", "context", "context-error"}) modalCase(owner, foreign, kind);
            NativeCancelPrompt noPrompt = new NativeCancelPrompt(owner, MESSAGE, () -> {}, 1_000);
            try (noPrompt) { check(noPrompt.requireSuccess() == 0, "no prompt must require no answer"); }
            check(noPrompt.released(), "no-prompt lifecycle");
            AssertionError marker = new AssertionError("own native failure");
            NativeCancelPrompt nativeFailure = new NativeCancelPrompt(owner, MESSAGE, () -> {}, 1_000);
            try (nativeFailure) {
                throw marker;
            } catch (AssertionError actual) {
                check(actual == marker, "original native throwable identity");
            }
            check(nativeFailure.released(), "native exception lifecycle");
        } finally {
            owner.dispose(); foreign.dispose();
        }
    }

    public static void main(String[] ignored) throws Exception {
        SwingUtilities.invokeAndWait(NativeCancelPromptSelfCheck::run);
        System.out.println("NATIVE_CANCEL_PROMPT_MODAL_SELFCHECK PASS checks=" + checks
            + " officialClassesExecuted=false nativeHostLaunched=false");
    }
}
