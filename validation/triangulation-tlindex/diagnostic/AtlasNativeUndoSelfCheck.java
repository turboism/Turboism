import java.io.File;
import java.util.ArrayList;
import java.util.List;
import javax.swing.SwingUtilities;

public final class AtlasNativeUndoSelfCheck {
    static int checks;
    static final File FIXTURE = new File("/tmp/task-owned-atlas.cmo3");
    public static final class Manager {
        final List<Object> entries = new ArrayList<>(); int position; boolean enabled = true;
        public int getCurrentPos() { return position; }
        public List<Object> getUndoList() { return entries; }
        public boolean canUndo() { return enabled && position > 0; }
        void add() { entries.add(new Object()); position++; }
    }
    public static final class Mode {
        final Manager manager = new Manager();
        public Manager getUndoManager() { return manager; }
    }
    public static final class Doc {
        final Mode mode = new Mode(); File file = FIXTURE;
        public Doc getFileContent() { return this; }
        public File getFile() { return file; }
        public Mode getCurrentEditMode() { return mode; }
    }
    public static final class Controller {
        final Doc doc = new Doc(); int calls; boolean fail;
        public Doc getCurrentDoc() { return doc; }
        public void command_undo(Doc target) {
            if (!SwingUtilities.isEventDispatchThread() || target != doc) throw new AssertionError();
            calls++;
            if (fail) throw new IllegalArgumentException("native failure");
            target.mode.manager.position--;
        }
    }
    interface Action { void run() throws Exception; }
    static void reject(Action action, String message) throws Exception {
        try { action.run(); } catch (IllegalStateException e) {
            if (!e.getMessage().contains(message)) throw e;
            checks++; return;
        }
        throw new AssertionError("expected rejection " + message);
    }
    static void onEdt() throws Exception {
        Controller c = new Controller();
        var before = AtlasNativeUndo.snapshot(c, FIXTURE);
        c.doc.mode.manager.add(); var applied = AtlasNativeUndo.snapshot(c, FIXTURE);
        c.doc.file = new File("/tmp/unrelated.cmo3");
        reject(() -> AtlasNativeUndo.undo(c, FIXTURE, before, applied), "task fixture");
        c.doc.file = FIXTURE; c.doc.mode.manager.enabled = false;
        reject(() -> AtlasNativeUndo.undo(c, FIXTURE, before, applied), "cannot undo");
        c.doc.mode.manager.enabled = true;
        c.doc.mode.manager.add();
        reject(() -> AtlasNativeUndo.undo(c, FIXTURE, before, applied), "changed before dispatch");
        if (c.calls != 0) throw new AssertionError("rejected request mutated host"); checks++;
        c.doc.mode.manager.entries.remove(1); c.doc.mode.manager.position--;
        c.fail = true;
        try { AtlasNativeUndo.undo(c, FIXTURE, before, applied); throw new AssertionError(); }
        catch (IllegalArgumentException e) {
            if (!e.getMessage().equals("native failure")) throw e; checks++;
        }
        c.fail = false;
        AtlasNativeUndo.undo(c, FIXTURE, before, applied);
        if (c.doc.mode.manager.position != 0 || c.calls != 2) throw new AssertionError(); checks++;
    }
    public static void main(String[] args) throws Exception {
        reject(() -> AtlasNativeUndo.snapshot(new Controller(), FIXTURE), "EDT");
        SwingUtilities.invokeAndWait(() -> {
            try { onEdt(); } catch (Exception e) { throw new RuntimeException(e); }
        });
        System.out.println("AtlasNativeUndo " + checks + " checks PASS (synthetic only)");
    }
}
