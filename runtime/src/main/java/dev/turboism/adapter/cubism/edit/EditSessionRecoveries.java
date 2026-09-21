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
     *
     * <p>OPEN: the {@code endEdit} first-argument meaning is inferred ({@code false} = keep the
     * session's group entry for revert to consume); direct host evidence is deferred to T7.</p>
     */
    public static EditSessionRecovery reverting() {
        return Reverting.INSTANCE;
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
            verifyHistoryRestored(host, request);
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
            host.endEdit(request.binding(), request.editToken(), false);
            host.revert(request.binding());
            verifyHistoryRestored(host, request);
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
