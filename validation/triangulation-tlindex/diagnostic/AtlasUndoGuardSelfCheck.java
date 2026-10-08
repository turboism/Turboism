import java.util.List;

public final class AtlasUndoGuardSelfCheck {
    static int checks;
    static final Object DOC = new Object(), MANAGER = new Object();
    static AtlasUndoGuard.Snapshot snapshot(int position, Object... entries) {
        return new AtlasUndoGuard.Snapshot(DOC, MANAGER, position, List.of(entries));
    }
    static void reject(Runnable test) {
        try { test.run(); } catch (IllegalStateException expected) { checks++; return; }
        throw new AssertionError("unsafe undo accepted");
    }
    public static void main(String[] args) {
        Object initial = new Object(), edit = new Object(), redo = new Object();
        var before = snapshot(1, initial);
        var applied = snapshot(2, initial, edit);
        if (AtlasUndoGuard.requireSingleNewEdit(before, applied) != edit) throw new AssertionError();
        checks++;
        AtlasUndoGuard.requireUnchanged(applied, snapshot(2, initial, edit)); checks++;
        AtlasUndoGuard.requireRestored(before, applied, snapshot(1, initial, edit)); checks++;
        // Repeating an operation replaces the old redo branch with a genuinely new record.
        AtlasUndoGuard.requireSingleNewEdit(snapshot(1, initial, redo), applied); checks++;
        reject(() -> AtlasUndoGuard.requireSingleNewEdit(before, before));
        reject(() -> AtlasUndoGuard.requireSingleNewEdit(before, snapshot(3, initial, edit, redo)));
        reject(() -> AtlasUndoGuard.requireSingleNewEdit(snapshot(1, initial, edit), applied));
        reject(() -> AtlasUndoGuard.requireSingleNewEdit(before, snapshot(2, redo, edit)));
        reject(() -> AtlasUndoGuard.requireSingleNewEdit(before,
            new AtlasUndoGuard.Snapshot(new Object(), MANAGER, 2, List.of(initial, edit))));
        reject(() -> AtlasUndoGuard.requireSingleNewEdit(before,
            new AtlasUndoGuard.Snapshot(DOC, new Object(), 2, List.of(initial, edit))));
        reject(() -> AtlasUndoGuard.requireUnchanged(applied, snapshot(2, initial, redo)));
        reject(() -> AtlasUndoGuard.requireRestored(before, applied, applied));
        reject(() -> AtlasUndoGuard.requireRestored(before, applied, snapshot(1, initial, redo)));
        // Equality must never substitute for object identity.
        Object equal1 = new String("same"), equal2 = new String("same");
        reject(() -> AtlasUndoGuard.requireSingleNewEdit(snapshot(1, equal1), snapshot(2, equal2, edit)));
        System.out.println("AtlasUndoGuard " + checks + " checks PASS (offline only)");
    }
}
