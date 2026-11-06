package dev.turboism.adapter.cubism.editor.transaction;

/**
 * Runs one {@link EditorAuthoringWrite} on the Editor host thread.
 *
 * <p>The writer is the single authoring envelope. It owns the EDT dispatch, the
 * ambient-versus-standalone decision (including the {@code turboism.editorAmbientEnvelopeJoin}
 * kill switch and the verified native group-undo row rollback depends on), the root
 * {@code edit-mode} bracket, native Undo admission, history preparation and verification, refresh
 * and dirty marking, and rollback with receipts. Access families contribute one write and nothing
 * else.</p>
 */
public interface EditorAuthoringWriter {

    /**
     * Runs one authoring write.
     *
     * @param write changed authoring primitive
     * @throws IllegalStateException when the subject is stale, the postcondition fails, the
     *     standalone commit cannot be verified, or the write cannot join the open ambient root
     *     (see {@code EditorAmbientTransactionRejection})
     */
    void apply(EditorAuthoringWrite write);
}
