package dev.turboism.automesh;

/**
 * Host-supplied precondition that reports whether the Editor holds an open native Undo group.
 *
 * <p>An auto-mesh run commits its own authoring transaction, and the host must see one Undo entry
 * for that run alone. Opening that transaction inside a group the host already began would merge two
 * unrelated edits, so the dispatcher refuses the run instead of nesting. The milestone that binds
 * the host supplies the real probe; a host-free environment reports the closed state.</p>
 */
@FunctionalInterface
public interface AutoMeshUndoGroupProbe {

    /** Probe of an environment without host Undo state: no group is ever open. */
    AutoMeshUndoGroupProbe CLOSED = () -> false;

    /** @return {@code true} while a native Editor Undo group is open on the UI thread */
    boolean undoGroupOpen();
}
