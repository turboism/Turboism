package dev.turboism.sdk.cubism.mesh;

import dev.turboism.sdk.PreviewApi;

import java.util.List;

/**
 * Plugin-initiated mesh authoring.
 *
 * <p>Adding and moving geometry are deliberately absent for now. The host exposes no
 * undo-aware add or move: it snapshots the whole mesh into an undo entry and attaches an
 * undo/redo listener before mutating, and that listener's behaviour is not yet understood well
 * enough to replicate. Shipping those operations without it would leave Undo unable to restore
 * what they changed, which is worse than not offering them.</p>
 *
 * <p>Each call is one undoable step of its own. To fold changes into an edit the host started,
 * use {@link MeshEditParticipation} instead — that is what joins the host's undo group.</p>
 *
 * <p>Every operation dispatches to the host thread, validates before mutating, and rejects
 * stale references rather than guessing a replacement.</p>
 */
@PreviewApi
public interface MeshEditService {

    MeshEditResult deletePoints(List<MeshPointRef> points);

    MeshEditResult deleteEdges(List<MeshEdgeRef> edges);

    /** The current mesh, or an empty snapshot when no mesh is being edited. */
    MeshSnapshot snapshot();
}
