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
    /** Detached neutral Warp geometry and opacity blend shapes. Ordinary axes and extended interpolation are excluded. */
    @CubismEditor(from = "5.0.00")
    default WarpMorphSnapshot morphSnapshot() {
        throw new UnsupportedOperationException("Warp morph reads are unavailable");
    }

    /** Creates one absolute opacity key for a blend parameter with default zero.
     * Requires a neutral Warp with one ordinary base form and no constraints or extended interpolation.
     * The key must be nonzero and within the parameter range. Duplicate keys within the host snap tolerance are rejected.
     * Parent deformers are allowed. Controls, base opacity and parameter values are preserved.
     * The operation participates in the active authoring transaction and its Undo group. */
    @CubismEditor(from = "5.0.00")
    default void createOpacityMorphKeyform(
            dev.turboism.sdk.cubism.id.ParameterId parameterId, float keyValue, float absoluteOpacity) {
        throw new UnsupportedOperationException("Warp opacity morph creation is unavailable");
    }
    /** Creates an absolute control-point blend key on a neutral Warp with no ordinary axes.
     * The blend parameter default must be zero. Parent deformers are allowed.
     * Controls must match the current grid. All other stored forms and parameter values are preserved. */
    @CubismEditor(from = "5.0.00")
    default void createMorphKeyform(
            dev.turboism.sdk.cubism.id.ParameterId parameterId,
            float keyValue,
            java.util.List<Point2> absoluteControlPoints) {
        throw new UnsupportedOperationException("Warp control morph creation is unavailable");
    }
}
