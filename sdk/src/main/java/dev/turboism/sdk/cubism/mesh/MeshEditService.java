package dev.turboism.sdk.cubism.mesh;

import dev.turboism.sdk.PreviewApi;

import java.util.List;

/**
 * Plugin-initiated mesh authoring.
 *
 * <p>Each call is one undoable step of its own. To fold changes into an edit the host started,
 * use {@link MeshEditParticipation} instead — that is what joins the host's undo group.</p>
 *
 * <p>Every operation dispatches to the host thread, validates before mutating, and rejects
 * stale references rather than guessing a replacement.</p>
 */
@PreviewApi
public interface MeshEditService {

    /** Adds points at the given positions; ids are assigned by the host. */
    MeshEditResult addPoints(List<MeshPointRef> points);

    MeshEditResult deletePoints(List<MeshPointRef> points);

    /** Moves each point to the position carried by its reference. */
    MeshEditResult movePoints(List<MeshPointRef> points);

    MeshEditResult addEdges(List<MeshEdgeRef> edges);

    MeshEditResult deleteEdges(List<MeshEdgeRef> edges);

    /** The current mesh, or an empty snapshot when no mesh is being edited. */
    MeshSnapshot snapshot();
}
