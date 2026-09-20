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
     * The default selection policy: always the compensating path.
     *
     * <p>The official {@code CUndoManager.revert()} path is never enabled implicitly — it becomes
     * eligible only through an explicit selector once the {@code
     * cubism.editor-model.undo.revert} capability row is verified for the connected host
     * (spec 046, T6).</p>
     */
    public static final EditSessionRecovery.Selector ALWAYS_COMPENSATING =
        (host, binding) -> compensating();

    /**
     * The capability-driven selection policy: the official {@code CUndoManager.revert()} path
     * when {@link EditorEditSessionHost#undoRevertVerified} reports the {@code
     * cubism.editor-model.undo.revert} capability row verified on the connected host, and the
     * compensating path otherwise. Compensation stays the safe default while the row is
     * unverified — which is every currently supported host, since the row's verification
     * records do not yet exist.
     */
    public static final EditSessionRecovery.Selector PREFER_REVERT_WHEN_VERIFIED =
        (host, binding) -> host.undoRevertVerified(binding) ? reverting() : compensating();

    private EditSessionRecoveries() {
    }

    /**
     * Returns the compensating recovery: abort the native session edit (the existing
     * {@code endEdit} cancel semantics of {@code EditorAuthoringTransactionCoordinator}) and
     * verify the native Undo history is exactly the pre-session snapshot — no new entry may
     * remain.
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
            host.endEdit(request.binding(), request.editToken(), true);
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
