package dev.turboism.sdk.cubism.mesh;

import dev.turboism.sdk.PreviewApi;

import java.util.List;

/**
 * Plugin-initiated mesh authoring.
 *
 * <p>Each mutation call is one host undo step of its own. To fold changes into an edit the host
 * started, use {@link MeshEditParticipation} instead — that joins the host's existing group.</p>
 *
 * <p>Every operation dispatches to the host thread, validates before mutating, and rejects stale
 * references rather than guessing a replacement. Cubism assigns identities to added points; call
 * {@link #snapshot()} after the write to observe them.</p>
 *
 * <p>Cubism has no standalone edge-move operation: an edge is a pair of point ids, so moving one
 * or both endpoints through {@link #movePoints(List)} moves every connected edge.</p>
 */
@PreviewApi
public interface MeshEditService {

    /** Adds points at the given positions; Cubism assigns their ids. */
    MeshEditResult addPoints(List<MeshPointPosition> points);

    MeshEditResult deletePoints(List<MeshPointRef> points);

    /** Moves each live point id to the position carried by its reference. */
    MeshEditResult movePoints(List<MeshPointRef> points);

    MeshEditResult addEdges(List<MeshEdgeRef> edges);

    MeshEditResult deleteEdges(List<MeshEdgeRef> edges);

    /** The current mesh, or an empty snapshot when no mesh is being edited. */
    MeshSnapshot snapshot();
}
