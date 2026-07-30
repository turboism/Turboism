package dev.turboism.sdk.cubism.model;

import dev.turboism.sdk.PreviewApi;

/** One Warp Deformer exposed through Editor authoring semantics. */
@PreviewApi
public interface WarpDeformer extends Deformer {

    WarpGrid grid();

    void replaceGrid(WarpGrid grid);
}
