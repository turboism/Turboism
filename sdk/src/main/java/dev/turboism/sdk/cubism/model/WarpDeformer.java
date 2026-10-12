package dev.turboism.sdk.cubism.model;

import dev.turboism.sdk.CubismEditor;

/** One Warp Deformer exposed through Editor authoring semantics. */
@CubismEditor
public interface WarpDeformer extends Deformer {

    /** Returns the deformer's current warp grid dimensions. */
    WarpGrid grid();

    /** Replaces the deformer's warp grid through the Editor authoring path. */
    void replaceGrid(WarpGrid grid);

    /** Detached stored ordinary forms. Does not read a calculated/current form. */
    @dev.turboism.sdk.CubismEditor(from = "5.0.00")
    default WarpAuthoringSnapshot authoringSnapshot() {
        throw new UnsupportedOperationException("stored Warp authoring reads are unavailable");
    }

    /** Compare-and-set controls in existing non-default ordinary forms, in one authoring operation.
     * Complete GUID coordinates and independent stored form identities are required.
     * Grid dimensions, default form, bindings and other forms are preserved. */
    @dev.turboism.sdk.CubismEditor(from = "5.0.00")
    default void setOrdinaryKeyformPositions(
            WarpAuthoringSnapshot expected, java.util.List<WarpKeyformPositions> edits) {
        throw new UnsupportedOperationException("stored Warp keyform editing is unavailable");
    }
}
