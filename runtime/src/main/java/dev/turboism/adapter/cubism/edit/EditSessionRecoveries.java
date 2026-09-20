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
