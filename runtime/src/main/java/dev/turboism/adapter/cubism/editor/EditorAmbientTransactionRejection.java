package dev.turboism.adapter.cubism.editor;

/**
 * Typed rejection raised when a hand-written native Undo envelope is invoked while an authoring
 * transaction ambient scope is active on the host thread.
 *
 * <p>Envelopes that can express their work as an admitted root-transaction contribution join
 * the ambient scope through {@code HostUndoMutationScope} instead of reaching this rejection.
 * The remaining sites — writes whose native Undo cannot be admitted to the modeling root edit
 * (animation timeline {@code edit-mode-base} envelopes, the host-internal parameter-definition
 * Undo) — open their own {@code edit-mode} session, which would create a detached Undo group
 * the root transaction can neither join nor roll back (ARCHITECTURE §10 atomicity). The
 * failure is raised before any native edit begins, so the rejected write leaves no Undo entry
 * and no open edit session.</p>
 */
final class EditorAmbientTransactionRejection extends IllegalStateException {

    EditorAmbientTransactionRejection(final String writeLabel) {
        super(
            writeLabel + " cannot run inside an active authoring transaction: this write cannot"
                + " join the ambient Undo group, and running it would create a detached Undo"
                + " group outside transaction control. Move this operation outside the"
                + " transaction callback."
        );
    }
}
