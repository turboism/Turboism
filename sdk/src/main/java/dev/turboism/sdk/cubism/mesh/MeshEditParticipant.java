package dev.turboism.sdk.cubism.mesh;


/**
 * Called synchronously while the host is deleting mesh geometry, before anything is removed.
 *
 * <p>Whatever is returned joins the host's own undo group, so a single Undo reverts the host's
 * deletion and the contribution together. Returning {@link MeshEditContribution#none()} leaves
 * the host edit untouched, and so does throwing. Implementations must return promptly: the runtime
 * diagnoses elapsed-time budget violations, but cannot safely preempt arbitrary in-process Java 17
 * code while the callback has synchronous access to an edit backed by live host geometry.</p>
 */
@FunctionalInterface
public interface MeshEditParticipant {

    MeshEditContribution onDeleting(MeshDeletion deletion);
}
