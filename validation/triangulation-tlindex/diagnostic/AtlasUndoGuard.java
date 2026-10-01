import java.util.List;

/** Diagnostic-only guard; the caller must obtain snapshots and dispatch native undo on the EDT. */
final class AtlasUndoGuard {
    record Snapshot(Object document, Object manager, int position, List<?> entries) {
        Snapshot {
            if (document == null || manager == null || position < 0 || position > entries.size()) {
                throw new IllegalArgumentException("invalid undo snapshot");
            }
            entries = List.copyOf(entries);
        }
    }

    private AtlasUndoGuard() {}

    /** Requires exactly one new native edit after the recorded operation baseline. */
    static Object requireSingleNewEdit(Snapshot before, Snapshot after) {
        sameContext(before, after);
        if (after.position != before.position + 1 || after.entries.size() != after.position) {
            throw new IllegalStateException("operation did not create exactly one current edit");
        }
        prefix(before, after, before.position);
        Object edit = after.entries.get(before.position);
        // A no-op/redo of an existing record cannot prove a newly captured atlas operation.
        for (Object previous : before.entries) {
            if (edit == previous) throw new IllegalStateException("edit was already present");
        }
        return edit;
    }

    /** Check immediately before dispatch, so an intervening user edit cannot be undone. */
    static void requireUnchanged(Snapshot expected, Snapshot current) {
        sameContext(expected, current);
        if (expected.position != current.position || expected.entries.size() != current.entries.size()) {
            throw new IllegalStateException("undo history changed before dispatch");
        }
        prefix(expected, current, expected.entries.size());
    }

    /** Native undo retains its redo entry, moves the cursor back one, and preserves history identities. */
    static void requireRestored(Snapshot before, Snapshot applied, Snapshot restored) {
        requireSingleNewEdit(before, applied);
        sameContext(applied, restored);
        if (restored.position != before.position || restored.entries.size() != applied.entries.size()) {
            throw new IllegalStateException("native undo did not restore baseline position");
        }
        prefix(applied, restored, applied.entries.size());
    }

    private static void sameContext(Snapshot a, Snapshot b) {
        if (a.document != b.document || a.manager != b.manager) {
            throw new IllegalStateException("document or edit-mode undo manager changed");
        }
    }

    private static void prefix(Snapshot a, Snapshot b, int length) {
        for (int i = 0; i < length; i++) {
            if (a.entries.get(i) != b.entries.get(i)) {
                throw new IllegalStateException("undo history identity changed");
            }
        }
    }
}
