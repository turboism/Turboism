package dev.turboism.sdk.cubism.model;


/** One Warp Deformer exposed through Editor authoring semantics. */
public interface WarpDeformer extends Deformer {

    WarpGrid grid();

    void replaceGrid(WarpGrid grid);
}
