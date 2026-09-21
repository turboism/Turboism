package dev.turboism.adapter.cubism.edit;

import dev.turboism.sdk.cubism.edit.EditSessionException;
import dev.turboism.sdk.cubism.edit.EditUnavailableException;
import dev.turboism.sdk.cubism.history.HistorySnapshot;

import java.util.Objects;

/** The supported {@link EditSessionRecovery} strategies and their selection policy. */
public final class EditSessionRecoveries {

    /** Diagnostic code carried by a cancellation whose restore could not be verified. */
    public static final String RECOVERY_FAILED_CODE = "cubism.edit.recovery-failed";

    /**
     * The always-compensating selection policy — kept for tests and for hosts whose record
     * drops the revert row.
     */
    public static final EditSessionRecovery.Selector ALWAYS_COMPENSATING =
        (host, binding) -> compensating();

    /**
     * The capability-driven selection policy: the official {@code CUndoManager.revert()} path
     * when {@link EditorEditSessionHost#undoRevertVerified} reports the {@code
     * cubism.editor-model.undo.revert} capability row verified on the connected host, and the
     * compensating path otherwise. The row is bound on all three reviewed records — the
     * compensating path remains the fallback for unverified records.
     */
    public static final EditSessionRecovery.Selector PREFER_REVERT_WHEN_VERIFIED =
        (host, binding) -> host.undoRevertVerified(binding) ? reverting() : compensating();

    private EditSessionRecoveries() {
    }

    /**
     * Returns the compensating recovery: undo the session's active {@code GroupUndo} in place
     * so every captured mutation is restored, then abort the native session edit ({@code
     * endEdit(true)} discards the group without pushing it) and verify the native Undo
     * history is exactly the pre-session snapshot — no new entry may remain.
     */
    public static EditSessionRecovery compensating() {
        return Compensating.INSTANCE;
    }

    /**
     * Returns the official {@code CUndoManager.revert()} recovery: end the native session edit
     * so the group undo lands as one history entry, then revert it — the official cancel path
     * undoes the top entry and removes the range, restoring the model without leaving an undo
     * record. Only selected when the revert capability row is verified.
     */
    public static EditSessionRecovery reverting() {
        return Reverting.INSTANCE;
    }

    /**
     * Returns whether the session's edit token still owns the edit mode's current undo group.
     * A host-side {@code beginEdit} (deferred palette/selection callbacks included) replaces
     * {@code currentUndo} without closing the session's group — closing then would commit or
     * discard the foreign group instead of the session's. A read failure is reported as not
     * current: the displaced-session reconciliation is safe whether or not the group was
     * actually displaced.
     */
    static boolean sessionGroupIsCurrent(
        final EditorEditSessionHost host,
        final EditSessionRecoveryRequest request
    ) {
        final Object current;
        try {
            current = host.currentEditGroup(request.binding());
        } catch (RuntimeException failure) {
            request.diagnose(
                "edit-session recovery: current undo group unreadable ("
                    + failure.getMessage() + "); reconciling conservatively");
            return false;
        }
        if (current == request.editToken()) {
            return true;
        }
        request.diagnose(
            "edit-session recovery: the session undo group was displaced by a host-side edit"
                + (current == null ? " (no group is current)" : " (a foreign group is current)"));
        return false;
    }

    /**
     * Reconciles the host to the exact pre-session snapshot when the session token was never
     * committed — the normal compensating path and the safety net for a displaced session
     * group alike:
     *
     * <ol>
     *   <li>undo the group currently holding the bracket in place (a foreign group's pending
     *   mutations are restored before it is discarded),</li>
     *   <li>undo the session's own group in place when it isn't the current one (its undoables
     *   are still pending on the orphaned token),</li>
     *   <li>{@code endEdit(true)} to clear whatever group is current,</li>
     *   <li>reconcile committed entries back to {@code historyBefore.position} — {@code
     *   revert()} pops the entry at the cursor and truncates the tail, so each foreign entry a
     *   displaced edit pushed is both undone and removed; {@code undoRedoTo} restores the
     *   cursor (and the model) when revert isn't verified, leaving entry-level equality to the
     *   final verification,</li>
     *   <li>verify the exact pre-session snapshot.</li>
     * </ol>
     */
    static void reconcileToStart(
        final EditorEditSessionHost host,
        final EditSessionRecoveryRequest request
    ) throws EditSessionException {
        RuntimeException failure = null;
        Object current = null;
        try {
            current = host.currentEditGroup(request.binding());
        } catch (RuntimeException readFailure) {
            failure = readFailure;
            request.diagnose(
                "edit-session recovery: current undo group unreadable ("
                    + readFailure.getMessage() + ")");
        }
        if (current != null) {
            try {
                host.undoGroup(request.binding(), current);
            } catch (RuntimeException undoFailure) {
                failure = merge(failure, undoFailure);
                request.diagnose(
                    "edit-session recovery: could not undo the current undo group ("
                        + undoFailure.getMessage() + ")");
            }
        }
        if (current != request.editToken()) {
            try {
                host.undoGroup(request.binding(), request.editToken());
            } catch (RuntimeException undoFailure) {
                failure = merge(failure, undoFailure);
                request.diagnose(
                    "edit-session recovery: could not undo the session undo group ("
                        + undoFailure.getMessage() + ")");
            }
        }
        try {
            host.endEdit(request.binding(), request.editToken(), true);
        } catch (RuntimeException endFailure) {
            failure = merge(failure, endFailure);
            request.diagnose(
                "edit-session recovery: could not end the session edit ("
                    + endFailure.getMessage() + ")");
        }
        try {
            reconcileCommittedTail(host, request);
        } catch (RuntimeException tailFailure) {
            failure = merge(failure, tailFailure);
        }
        if (failure != null) {
            throw failure;
        }
        verifyHistoryRestored(host, request);
        host.refreshAfterSession(request.binding());
    }

    /**
     * Pops every history entry committed above the pre-session position. {@code revert()}
     * undoes the entry at the cursor and truncates the tail — the only verified member that
     * removes committed entries — so it restores both model state and entry-list equality.
     * Without a verified revert row the best available step is {@code undoRedoTo}, which moves
     * the cursor and undoes the entries' model effects but leaves the entries themselves; the
     * caller's exact-snapshot verification then reports the residual difference as a typed
     * recovery failure instead of claiming a clean cancel.
     */
    private static void reconcileCommittedTail(
        final EditorEditSessionHost host,
        final EditSessionRecoveryRequest request
    ) throws EditSessionException {
        final int start = request.historyBefore().position();
        HistorySnapshot snapshot = host.history(request.binding());
        if (snapshot.availability() != HistorySnapshot.Availability.AVAILABLE) {
            return;
        }
        int guard = snapshot.entries().size() + 1;
        while (snapshot.position() > start && snapshot.canUndo() && guard-- > 0) {
            if (host.undoRevertVerified(request.binding())) {
                try {
                    host.revert(request.binding());
                } catch (RuntimeException revertFailure) {
                    request.diagnose(
                        "edit-session recovery: revert of a committed entry failed ("
                            + revertFailure.getMessage() + "); moving the history cursor");
                    host.undoRedoTo(request.binding(), start);
                    return;
                }
            } else {
                host.undoRedoTo(request.binding(), start);
                return;
            }
            snapshot = host.history(request.binding());
            if (snapshot.availability() != HistorySnapshot.Availability.AVAILABLE) {
                return;
            }
        }
    }

    /**
     * Verifies the exact pre-session snapshot; on a mismatch first reconciles committed entries
     * above the start position through {@link #reconcileCommittedTail} and verifies once more.
     */
    private static void verifyOrReconcile(
        final EditorEditSessionHost host,
        final EditSessionRecoveryRequest request
    ) throws EditSessionException {
        try {
            verifyHistoryRestored(host, request);
            return;
        } catch (EditSessionException first) {
            request.diagnose(
                "edit-session recovery: history snapshot mismatch after close; "
                    + "reconciling the committed tail");
            try {
                reconcileCommittedTail(host, request);
            } catch (RuntimeException reconcileFailure) {
                first.addSuppressed(reconcileFailure);
                throw first;
            }
        }
        verifyHistoryRestored(host, request);
    }

    private static RuntimeException merge(final RuntimeException first, final RuntimeException next) {
        if (first == null) {
            return next;
        }
        first.addSuppressed(next);
        return first;
    }

    private enum Compensating implements EditSessionRecovery {
        INSTANCE;

        @Override
        public void recover(
            final EditorEditSessionHost host,
            final EditSessionRecoveryRequest request
        ) throws EditSessionException {
            Objects.requireNonNull(host, "host");
            Objects.requireNonNull(request, "request");
            if (!sessionGroupIsCurrent(host, request)) {
                reconcileToStart(host, request);
                return;
            }
            // The native edit bracket must close even when the group undo fails — otherwise
            // the host stays inside the session's edit mode and no later edit can begin.
            RuntimeException undoFailure = null;
            try {
                host.undoEditGroup(request.binding(), request.editToken());
            } catch (RuntimeException failure) {
                undoFailure = failure;
            }
            try {
                host.endEdit(request.binding(), request.editToken(), true);
            } catch (RuntimeException endFailure) {
                if (undoFailure != null) {
                    endFailure.addSuppressed(undoFailure);
                }
                throw endFailure;
            }
            if (undoFailure != null) {
                throw undoFailure;
            }
            verifyOrReconcile(host, request);
            host.refreshAfterSession(request.binding());
        }
    }

    private enum Reverting implements EditSessionRecovery {
        INSTANCE;

        @Override
        public void recover(
            final EditorEditSessionHost host,
            final EditSessionRecoveryRequest request
        ) throws EditSessionException {
            Objects.requireNonNull(host, "host");
            Objects.requireNonNull(request, "request");
            if (!sessionGroupIsCurrent(host, request)) {
                reconcileToStart(host, request);
                return;
            }
            try {
                host.endEdit(request.binding(), request.editToken(), false);
            } catch (RuntimeException endFailure) {
                // The session group never committed — fall back to the compensating
                // reconciliation (undo the pending group, abort the bracket, reconcile the
                // cursor) rather than reporting a bare close failure.
                request.diagnose(
                    "edit-session recovery: commit-for-revert failed ("
                        + endFailure.getMessage() + "); falling back to group undo");
                try {
                    reconcileToStart(host, request);
                } catch (EditSessionException | RuntimeException reconcileFailure) {
                    endFailure.addSuppressed(reconcileFailure);
                    throw endFailure;
                }
                return;
            }
            try {
                host.revert(request.binding());
            } catch (RuntimeException revertFailure) {
                // The session entry already committed — never re-undo the token's group
                // (its undoables live in the history entry now). Reconcile at the cursor.
                request.diagnose(
                    "edit-session recovery: revert failed ("
                        + revertFailure.getMessage() + "); reconciling the history cursor");
                try {
                    reconcileCommittedTail(host, request);
                } catch (RuntimeException reconcileFailure) {
                    revertFailure.addSuppressed(reconcileFailure);
                    throw revertFailure;
                }
            }
            verifyOrReconcile(host, request);
            host.refreshAfterSession(request.binding());
        }
    }

    static void verifyHistoryRestored(
        final EditorEditSessionHost host,
        final EditSessionRecoveryRequest request
    ) throws EditSessionException {
        final HistorySnapshot after;
        try {
            after = host.history(request.binding());
        } catch (RuntimeException failure) {
            throw new EditUnavailableException(
                RECOVERY_FAILED_CODE,
                "Cubism edit session recovery could not read the Undo history"
            );
        }
        if (after.availability() != HistorySnapshot.Availability.AVAILABLE
            || !request.historyBefore().equals(after)) {
            throw new EditUnavailableException(
                RECOVERY_FAILED_CODE,
                "Cubism edit session recovery left the Undo history changed"
            );
        }
    }
}
