package dev.turboism.sdk.cubism.mesh;

import dev.turboism.sdk.PreviewApi;

/**
 * Called synchronously while the host is deleting mesh geometry, before anything is removed.
 *
 * <p>Whatever is returned joins the host's own undo group, so a single Undo reverts the host's
 * deletion and the contribution together. Returning {@link MeshEditContribution#none()} leaves
 * the host edit untouched, and so does throwing: failure is never allowed to block the host.</p>
 */
@PreviewApi
@FunctionalInterface
public interface MeshEditParticipant {

    MeshEditContribution onDeleting(MeshDeletion deletion);
}
