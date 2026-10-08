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
        CountingFile actual = new CountingFile(fixture.getPath());
        Controller controller = new Controller(actual);
        reject(() -> NativeAutoConnect.binding(controller));
        AtomicReference<NativeAutoConnect.Binding> ref = new AtomicReference<>();
        onEdt(() -> ref.set(NativeAutoConnect.binding(controller)));
        NativeAutoConnect.Binding binding = ref.get();
        onEdt(() -> reject(() -> NativeAutoConnect.boundDocument(controller, binding)));
        binding.verifyFixture(fixture);
        require(actual.canonicalCalls == 1);
        reject(() -> binding.verifyFixture(fixture));
        reject(() -> NativeAutoConnect.enter(controller, binding));
        onEdt(() -> {
            require(NativeAutoConnect.boundDocument(controller, binding) == controller);
            controller.file = new File(actual.getPath());
            require(NativeAutoConnect.boundDocument(controller, binding) == controller);
            controller.file = new File("/tmp/another.cmo3");
            reject(() -> NativeAutoConnect.boundDocument(controller, binding));
            controller.file = actual;
            reject(() -> NativeAutoConnect.boundDocument(new Controller(actual), binding));
            reject(() -> binding.verifyFixture(fixture));
            reject(() -> NativeAutoConnect.enter(controller, binding)); // wrong edit mode
            require(actual.canonicalCalls == 1);
        });
        onEdt(() -> ref.set(NativeAutoConnect.binding(new Controller(new File("/tmp/another.cmo3")))));
        reject(() -> ref.get().verifyFixture(fixture));
        System.out.println("Native auto-connect guard checks PASS: " + checks);
    }
    public static final class Controller {
        private File file;
        Controller(File file) { this.file = file; }
        public Controller getCurrentDoc() { return this; }
        public Controller getFileContent() { return this; }
        public File getFile() { return file; }
        public Object getCurrentEditMode() { return this; }
    }
    public static final class CountingFile extends File {
        private static final long serialVersionUID = 1L;
        int canonicalCalls;
        CountingFile(String path) { super(path); }
        @Override public File getCanonicalFile() throws java.io.IOException {
            if (SwingUtilities.isEventDispatchThread()) throw new AssertionError("filesystem I/O on EDT");
            canonicalCalls++;
            return super.getCanonicalFile();
        }
    }
    private static void onEdt(Action action) throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            try { action.run(); } catch (Throwable problem) { failure.set(problem); }
        });
        if (failure.get() != null) throw new AssertionError("EDT checks failed", failure.get());
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
