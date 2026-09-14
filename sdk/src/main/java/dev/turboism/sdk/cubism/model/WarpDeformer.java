package dev.turboism.sdk.cubism.model;


/** One Warp Deformer exposed through Editor authoring semantics. */
public interface WarpDeformer extends Deformer {

    /** Returns the deformer's current warp grid dimensions. */
    WarpGrid grid();

    /** Replaces the deformer's warp grid through the Editor authoring path. */
    void replaceGrid(WarpGrid grid);
}
