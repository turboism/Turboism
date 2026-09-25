package dev.turboism.adapter.cubism.editor;

import dev.turboism.adapter.cubism.editor.transaction.EditorAuthoringTransactionCoordinator;

/**
 * Shared fail-closed check for hand-written native Undo envelopes that cannot join the ambient
 * transaction (see {@code HostUndoMutationScope} for the envelope families that do join). When an
 * authoring transaction ambient scope is active on the host thread, such an envelope entry must
 * reject rather than open a detached {@code edit-mode} group the root transaction cannot join or
 * roll back.
 */
final class EditorAmbientTransactionGuard {

    private EditorAmbientTransactionGuard() {
    }

    /**
     * @param coordinator the model access's authoring coordinator, or {@code null} when the
     *     write path was built without one (no ambient scope can then exist)
     * @param writeLabel human-readable name of the rejected write for diagnostics
     */
    static void requireNoAmbientTransaction(
        final EditorAuthoringTransactionCoordinator coordinator,
        final String writeLabel
    ) {
        if (coordinator != null && coordinator.ambientScopeActive()) {
            throw new EditorAmbientTransactionRejection(writeLabel);
        }
    }
}
