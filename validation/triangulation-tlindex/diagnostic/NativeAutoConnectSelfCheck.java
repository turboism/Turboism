import java.io.File;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;

/** Guard/dispatch checks only; real editor command behavior remains a host test. */
public final class NativeAutoConnectSelfCheck {
    private static int checks;
    private NativeAutoConnectSelfCheck() {}
    public static void main(String[] args) throws Exception {
        Object a = new String("same"), b = new String("same");
        require(NativeAutoConnect.sameIdentities(List.of(a, b), List.of(b, a)));
        require(!NativeAutoConnect.sameIdentities(List.of(a), List.of(b)));
        require(!NativeAutoConnect.sameIdentities(List.of(a, a), List.of(a, a)));
        require(!NativeAutoConnect.sameIdentities(List.of(a, b), List.of(a, a)));
        PanelMode panelMode = new PanelMode();
        require(NativeAutoConnect.callReturning(panelMode, "getToolPanel", Panel.class.getName()) == panelMode.panel);
        reject(() -> NativeAutoConnect.callReturning(panelMode, "getToolPanel", String.class.getName()));
        reject(() -> NativeAutoConnect.callReturning(panelMode, "getToolPanel", Object.class.getName()));
        Commands commands = new Commands();
        NativeAutoConnect.invokeNamed(commands, "option", true);
        require(commands.option);
        require(NativeAutoConnect.invokeNamed(commands, "number", 3).equals(3));
        reject(() -> NativeAutoConnect.invokeNamed(commands, "number", "wrong"));
        reject(() -> NativeAutoConnect.invokeNamed(commands, "ambiguous", "both"));
        try {
            NativeAutoConnect.invokeNamed(commands, "failure");
            throw new AssertionError("native error not propagated");
        } catch (UnsupportedOperationException expected) { checks++; }
        File fixture = new File("/tmp/autoconnect-owned.cmo3");
        reject(() -> NativeAutoConnect.enter(new Controller(fixture), fixture));
        AtomicReference<Throwable> failure = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            try {
                reject(() -> NativeAutoConnect.enter(new Controller(new File("/tmp/another.cmo3")), fixture));
                // Even a correctly bound file is insufficient when modeling mode is absent.
                reject(() -> NativeAutoConnect.enter(new Controller(fixture), fixture));
            } catch (Throwable problem) { failure.set(problem); }
        });
        if (failure.get() != null) throw new AssertionError("EDT checks failed", failure.get());
        System.out.println("Native auto-connect guard checks PASS: " + checks);
    }
    public static final class Controller {
        private final File file;
        Controller(File file) { this.file = file; }
        public Controller getCurrentDoc() { return this; }
        public Controller getFileContent() { return this; }
        public File getFile() { return file; }
        public Object getCurrentEditMode() { return this; }
    }
    public static final class Panel {}
    public static class BasePanelMode {
        public Object getToolPanel() { return null; }
    }
    public static final class PanelMode extends BasePanelMode {
        final Panel panel = new Panel();
        @Override public Panel getToolPanel() { return panel; }
    }
    public static final class Commands {
        boolean option;
        public void option(boolean value) { option = value; }
        public int number(int value) { return value; }
        public void ambiguous(CharSequence value) {}
        public void ambiguous(java.io.Serializable value) {}
        public void failure() { throw new UnsupportedOperationException("native"); }
    }
    private interface Action { void run() throws Exception; }
    private static void reject(Action action) throws Exception {
        try { action.run(); }
        catch (IllegalStateException expected) { checks++; return; }
        throw new AssertionError("expected rejection");
    }
    private static void require(boolean value) {
        if (!value) throw new AssertionError("check failed");
        checks++;
    }
}
