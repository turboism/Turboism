package dev.turboism.sdk.cubism.model;

import dev.turboism.sdk.CubismEditor;

/** Immutable canvas metrics for one Cubism model generation. */
@CubismEditor({"5.2.03", "5.3.02", "5.3.03"})
public interface Canvas {

    float widthPixels();

    float heightPixels();

    float originXPixels();

    float originYPixels();

    float pixelsPerUnit();
}
