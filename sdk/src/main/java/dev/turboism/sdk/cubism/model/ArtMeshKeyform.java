package dev.turboism.sdk.cubism.model;

import dev.turboism.sdk.CubismEditor;
import java.util.List;

/**
 * A live reference to an existing, independently stored ordinary ArtMesh keyform.
 * Obtained from {@link Drawable#ordinaryKeyform(List)}, never from a detached snapshot.
 * Every operation checks the owning document/model generation and stored-form identity.
 * Replaced or deleted forms require a new lookup, even if their GUID is reused.
 */
@CubismEditor({"5.3.02", "5.3.03"})
public interface ArtMeshKeyform {
    /** Complete coordinates in parameter-GUID order; the returned list is detached. */
    List<ArtMeshAuthoringSnapshot.Coordinate> coordinates();

    /** Reads detached stored positions and draw order, not the evaluated current form. */
    ArtMeshAuthoringSnapshot.Form snapshot();

    /** Whether this tuple exactly matches every bound parameter's declared default. */
    boolean defaultKeyform();

    /**
     * Writes absolute stored positions to this non-default ordinary form only.
     * Source positions, UVs, indices, vertex count, draw order and other forms are unchanged.
     * Equal positions are a no-op. This operation participates in the Editor authoring transaction.
     *
     * @param positions finite positions in the existing vertex order
     * @throws IllegalArgumentException when the vertex count does not match
     * @throws IllegalStateException when the reference, grid or source geometry has changed
     * @throws UnsupportedOperationException for default forms or unavailable verified authoring
     */
    void setPositions(List<Point2> positions);

    /**
     * Writes only this non-default ordinary form's draw order without clamping.
     * Positions, source geometry and other forms are unchanged; equal values are a no-op.
     * Participates in the Editor authoring transaction and its native Undo/Redo history.
     *
     * @param drawOrder draw order from 0 through 1000, inclusive
     * @throws IllegalArgumentException when the requested order is outside that range
     * @throws IllegalStateException when the reference, grid or source geometry has changed
     * @throws UnsupportedOperationException for default forms or unavailable verified authoring
     */
    default void setDrawOrder(final int drawOrder) {
        throw new UnsupportedOperationException("stored keyform draw order editing is unavailable");
    }
}
