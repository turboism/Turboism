package dev.turboism.sdk.cubism.mesh;


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
public interface MeshEditService {

    /** Adds points at the given positions; Cubism assigns their ids. */
    MeshEditResult addPoints(List<MeshPointPosition> points);

    /** Deletes the referenced live points; stale references are rejected. */
    MeshEditResult deletePoints(List<MeshPointRef> points);

    /** Moves each live point id to the position carried by its reference. */
    MeshEditResult movePoints(List<MeshPointRef> points);

    /**
     * Adds edges between pairs of live point ids. A granted, non-empty edit is grouped as
     * one host undo step; a refused call — or a {@code null}/empty request, which still
     * returns an applied result — performs no edit and no undo entry.
     */
    MeshEditResult addEdges(List<MeshEdgeRef> edges);

    /** Deletes the referenced edges; stale references are rejected. */
    MeshEditResult deleteEdges(List<MeshEdgeRef> edges);

    /** The current mesh, or an empty snapshot when no mesh is being edited. */
    MeshSnapshot snapshot();

    /**
     * Reports whether a live runtime surface backs this instance.
     *
     * @return {@code false} only for the {@link #unavailable()} sentinel
     */
    default boolean isAvailable() {
        return true;
    }

    /**
     * Returns this service's fail-closed {@code Unavailable} sentinel.
     *
     * @return the shared singleton; {@link #isAvailable()} is {@code false} only for it
     */
    static MeshEditService unavailable() {
        return Unavailable.INSTANCE;
    }

    /** Sentinel returned by {@link #unavailable()}: calls refuse work without reaching the host. */
    enum Unavailable implements MeshEditService {
        INSTANCE;

        @Override public boolean isAvailable() {
            return false;
        }

        @Override public MeshEditResult addPoints(final List<MeshPointPosition> points) {
            return refused();
        }

        @Override public MeshEditResult deletePoints(final List<MeshPointRef> points) {
            return refused();
        }

        @Override public MeshEditResult movePoints(final List<MeshPointRef> points) {
            return refused();
        }

        @Override public MeshEditResult addEdges(final List<MeshEdgeRef> edges) {
            return refused();
        }

        @Override public MeshEditResult deleteEdges(final List<MeshEdgeRef> edges) {
            return refused();
        }

        @Override public MeshSnapshot snapshot() {
            return MeshSnapshot.empty();
        }

        private static MeshEditResult refused() {
            return MeshEditResult.refused("meshEdit service is not available");
        }
    }
}
