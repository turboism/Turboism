package dev.turboism.adapter.cubism.edit;

import dev.turboism.adapter.cubism.editor.transaction.EditorAuthoringTransactionCoordinator;
import dev.turboism.sdk.cubism.edit.EditSessionException;

/**
 * One cancellation-recovery strategy for an edit session (spec 046, T6).
 *
 * <p>Both supported strategies expose identical external semantics — the model is restored to
 * its pre-session state, no new history entry remains, and subsequent session operations fail
 * as cancelled. The official {@code CUndoManager.revert()} path is used only on hosts where the
 * {@code cubism.editor-model.undo.revert} capability row is verified; every other host falls back
 * to the compensating path that mirrors {@link EditorAuthoringTransactionCoordinator}'s recovery
 * (abort the native edit, then verify history equality).</p>
 *
 * <p>Known limitation: the strategies can differ on non-model side effects (the official path
 * restores editor-side snapshots the compensating path never captured). That gap is tracked with
 * the capability row's metadata.</p>
 */
public interface EditSessionRecovery {

    /**
     * Restores the pre-session state on the host UI thread.
     *
     * @throws EditSessionException when recovery could not be verified — the caller reports the
     *     session close as {@code FAILED} rather than claiming a clean cancellation
     */
    void recover(EditorEditSessionHost host, EditSessionRecoveryRequest request)
        throws EditSessionException;

    /**
     * Chooses the recovery strategy for one admitted session, evaluated at cancel time so the
     * decision follows the host's verified capability state.
     */
    @FunctionalInterface
    interface Selector {
        /**
         * Selects the recovery strategy for one session, evaluated at cancel time so the
         * decision follows the host's current verified capability state.
         */
        EditSessionRecovery select(
            EditorEditSessionHost host,
            EditorAuthoringTransactionCoordinator.Binding binding
        );
    }
}
