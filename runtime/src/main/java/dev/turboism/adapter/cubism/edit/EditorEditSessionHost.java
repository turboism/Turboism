package dev.turboism.adapter.cubism.edit;

import dev.turboism.adapter.cubism.editor.transaction.EditorAuthoringTransactionCoordinator;
import dev.turboism.sdk.cubism.edit.EditSessionException;
import dev.turboism.sdk.cubism.edit.EditUnavailableException;
import dev.turboism.sdk.cubism.history.HistorySnapshot;

import java.util.Optional;

/**
 * Native boundary for one external-application edit session (spec 046, T2).
 *
 * <p>The production implementation resolves every member through the verified member resolver;
 * session admission additionally requires the declared {@code cubism.editor-model.edit.session.*}
 * capability rows, so an unreviewed host build fails closed before any of the mutating members can
 * be reached. Fake implementations drive the manager contract tests without host access.</p>
 *
 * <p>All methods except {@link #dispatch} are invoked on the host UI thread: callers reach that
 * thread exclusively through {@link #dispatch}.</p>
 */
public interface EditorEditSessionHost {

    /**
     * Resolves the current plugin/document/model/thread binding for one owning plugin.
     *
     * @param pluginId owning plugin identity
     * @return the current binding, or empty when no stable modeling document is active
     */
    Optional<EditorAuthoringTransactionCoordinator.Binding> currentBinding(String pluginId);

    /**
     * Returns whether the supplied binding still names the active document, model, generation,
     * and host thread.
     */
    boolean isCurrent(EditorAuthoringTransactionCoordinator.Binding binding);

    /**
     * Returns whether verified host bindings admit an edit session on this binding — the
     * declared session capability rows authorize every member the session needs. A missing or
     * unverified row yields {@code false}; admission never guesses.
     */
    boolean admits(EditorAuthoringTransactionCoordinator.Binding binding);

    /**
     * Captures the pre-session native Undo history for the binding, or {@link
     * HistorySnapshot#unavailable()} when it cannot be read.
     */
    HistorySnapshot history(EditorAuthoringTransactionCoordinator.Binding binding);

    /**
     * Opens the native session edit ({@code ACEditMode.beginEdit}) and returns the opaque
     * session undo token (the official {@code GroupUndo}).
     */
    Object beginEdit(EditorAuthoringTransactionCoordinator.Binding binding, String label);

    /**
     * Closes the native session edit ({@code ACEditMode.endEdit(Z, callback)}).
     *
     * <p>OPEN: the meaning of the first argument is inferred from the 5.4 alpha2 port and from
     * the existing {@code VerifiedEditorAuthoringTransactionHost} usage ({@code true} = abort).
     * Direct host evidence is deferred to T7; this member must not be described as verified.</p>
     */
    void endEdit(EditorAuthoringTransactionCoordinator.Binding binding, Object edit, boolean cancel);

    /**
     * Undoes the session's active {@code GroupUndo} in place ({@code GroupUndo.undo()}),
     * restoring every model mutation captured on the session token without touching history.
     * The compensating recovery runs this before {@code endEdit(true)} discards the group —
     * discarding alone abandons the undoables without applying them.
     */
    void undoEditGroup(EditorAuthoringTransactionCoordinator.Binding binding, Object edit);

    /**
     * The edit mode's current undo group ({@code ACEditMode.getCurrentUndo}), or {@code null}
     * when no edit is open. A host-side {@code beginEdit} replaces the current group without
     * closing the session's — recoveries compare the returned token against the session's own
     * to detect the displacement before deciding how to close.
     */
    Object currentEditGroup(EditorAuthoringTransactionCoordinator.Binding binding);

    /**
     * Undoes an arbitrary {@code GroupUndo} in place ({@code GroupUndo.undo()}) — the
     * recovery-only variant of {@link #undoEditGroup} that also accepts a foreign group which
     * displaced the session's token, so its model mutations are restored before the group is
     * discarded.
     */
    void undoGroup(EditorAuthoringTransactionCoordinator.Binding binding, Object group);

    /**
     * Moves the native Undo history cursor to {@code position} ({@code
     * CUndoManager.undoRedoTo}), undoing or redoing every entry between the current position
     * and the target. The cursor-level reconciler for entries a displaced host edit already
     * committed during the session.
     */
    void undoRedoTo(
        EditorAuthoringTransactionCoordinator.Binding binding,
        int position
    );

    /**
     * Returns whether the {@code cubism.editor-model.undo.revert} capability row is verified on
     * this host — the precondition for the official {@code CUndoManager.revert()} cancel path.
     */
    boolean undoRevertVerified(EditorAuthoringTransactionCoordinator.Binding binding);

    /**
     * Invokes the host {@code CUndoManager.revert()} path: undoes the session's history entry and
     * removes it without leaving an undo record. Only called when {@link
     * #undoRevertVerified} returned {@code true} for this binding.
     */
    void revert(EditorAuthoringTransactionCoordinator.Binding binding);

    /**
     * Returns the main window handle to disable while the session is open, or empty when the
     * verified window chain is unavailable on this host.
     */
    Optional<Object> mainWindow(EditorAuthoringTransactionCoordinator.Binding binding);

    /**
     * Performs the official post-session cleanup (model-instance update, canvas repaint,
     * dirty-state work). Best-effort: invoked after a committed close or a recovered cancel.
     */
    void refreshAfterSession(EditorAuthoringTransactionCoordinator.Binding binding);

    /**
     * Runs work on the host UI thread with bounded synchronous waiting. A caller already on the
     * host thread runs inline; otherwise the task is dispatched and the caller waits a bounded
     * time — a timeout raises {@link EditSessionException}, never a hung plugin thread.
     */
    <T> T dispatch(String label, HostTask<T> task) throws EditSessionException;

    /**
     * Opens the session-scoped verified member surface the operation families orchestrate
     * through (spec 046, T3). The returned accessor is bound to this exact binding: every member
     * call re-validates staleness on the verified host, and every alias it invokes is resolved
     * through the verified member resolver. The default is fail-closed — hosts that cannot
     * supply a verified surface report typed unavailability instead of an accessor.
     *
     * @throws EditSessionException when no verified member surface exists for this binding
     */
    default EditSessionOpsAccess opsAccess(
        final EditorAuthoringTransactionCoordinator.Binding binding
    ) throws EditSessionException {
        throw new EditUnavailableException(
            "cubism.edit.ops-access",
            "Editor edit session member surface is unavailable on this host"
        );
    }

    /** Records or derives an opaque diagnostic identity for a terminal outcome. */
    String diagnosticId(String code, Throwable failure);

    /** Work executed by {@link #dispatch} on the host UI thread. */
    @FunctionalInterface
    interface HostTask<T> {
        /**
         * Runs the dispatched work on the host UI thread.
         *
         * @throws EditSessionException propagated back to the dispatching caller
         */
        T run() throws EditSessionException;
    }
}
