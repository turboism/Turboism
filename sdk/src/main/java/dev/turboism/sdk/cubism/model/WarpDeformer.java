package dev.turboism.sdk.cubism.model;


/** One Warp Deformer exposed through Editor authoring semantics. */
public interface WarpDeformer extends Deformer {

    WarpGrid grid();

    void replaceGrid(WarpGrid grid);

    /** Detached stored ordinary forms. Does not read a calculated/current form. */
    @dev.turboism.sdk.CubismEditor({"5.3.02", "5.3.03"})
    default WarpAuthoringSnapshot authoringSnapshot() {
        throw new UnsupportedOperationException("stored Warp authoring reads are unavailable");
    }

    /** Compare-and-set controls in existing non-default ordinary forms, in one authoring operation.
     * Complete GUID coordinates and independent stored form identities are required.
     * Grid dimensions, default form, bindings and other forms are preserved. */
    @dev.turboism.sdk.CubismEditor({"5.3.02", "5.3.03"})
    default void setOrdinaryKeyformPositions(WarpAuthoringSnapshot expected,
            java.util.List<WarpKeyformPositions> edits) {
        throw new UnsupportedOperationException("stored Warp keyform editing is unavailable");
    }
}
