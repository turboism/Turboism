package dev.turboism.adapter.cubism.editor;

/**
 * Typed rejection raised when a hand-written native Undo envelope is invoked while an authoring
 * transaction ambient scope is active on the host thread.
 *
 * <p>Those envelopes open their own {@code edit-mode} session, which would create a detached
 * Undo group the root transaction can neither join nor roll back (ARCHITECTURE §10 atomicity).
 * The failure is raised before any native edit begins, so the rejected write leaves no Undo
 * entry and no open edit session.</p>
 */
final class EditorAmbientTransactionRejection extends IllegalStateException {

    EditorAmbientTransactionRejection(final String writeLabel) {
        super(
            writeLabel + " cannot run inside an active authoring transaction: its hand-written"
                + " Undo envelope would create a detached Undo group outside transaction control."
                + " Use a coordinator-backed SDK write (for example Parameter.setValue) or move"
                + " this operation outside the transaction callback."
        );
    }
}
