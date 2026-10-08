package dev.turboism.sdk.cubism.model;

import dev.turboism.sdk.CubismEditor;

import java.util.List;

/**
 * A generation-bound, existing non-basic morph key on a root ArtMesh in Editor 5.3.02.
 * Stored positions are absolute values U, not a difference from the ordinary base B.
 * The implicit zero key is the exact ordinary default cell and is read-only here.
 * This initial surface requires normalized, non-repeating 0..1 morph parameters,
 * an unambiguous ordinary grid, and no source-level extended interpolation or constraints.
 *
 * <p>Membership changes invalidate previously acquired references. Undo, Redo and failure
 * compensation of a morph edit can replace every target record in the source, including
 * unrelated parameters' records: reacquire references after those operations. Detached
 * snapshots remain readable. Stale references throw {@link IllegalStateException}.</p>
 * <p>Loss of the supported authoring domain (for example locking the source or enabling
 * extended interpolation) throws {@link UnsupportedOperationException}; malformed stored
 * geometry is rejected by the snapshot contract with {@link IllegalArgumentException}.</p>
 */
@CubismEditor({"5.3.02", "5.3.03"})
public interface ArtMeshMorphKeyform {
    /** Returns the native parameter GUID, not its user-facing parameter ID. */
    String parameterGuid();

    /** Returns this exact positive non-basic key in (0, 1]. */
    float keyValue();

    /** Returns a detached copy of this target's absolute stored form. */
    ArtMeshAuthoringSnapshot.Form snapshot();

    /** Returns a detached copy of the exact ordinary default form; never a writable base. */
    ArtMeshAuthoringSnapshot.Form baseSnapshot();

    /**
     * Writes only this target's absolute positions, without changing source geometry or sliders.
     * Uses the authoring transaction coordinator; equal positions create no Undo or dirty change.
     * @param positions one finite point per existing vertex, in native stored-form coordinates
     * @throws IllegalArgumentException when the vertex count differs
     * @throws NullPointerException when the list or any point is null
     * @throws IllegalStateException when this reference or its layout is stale
     * @throws UnsupportedOperationException when the exact-version write protocol is unavailable
     */
    void setPositions(List<Point2> positions);

    /**
     * Writes only this target's absolute stored draw order, not an offset from the ordinary base
     * and never a slider-derived value. Equal orders create no Undo or dirty change.
     * @param drawOrder absolute stored order in 0..1000 inclusive
     * @throws IllegalArgumentException when the order is outside 0..1000; it is never clamped
     * @throws IllegalStateException when this reference or its layout is stale
     * @throws UnsupportedOperationException when the exact-version write protocol is unavailable
     */
    @CubismEditor({"5.3.02", "5.3.03"})
    default void setDrawOrder(final int drawOrder) {
        throw new UnsupportedOperationException("ArtMesh morph keyform draw-order editing is unavailable");
    }
}
