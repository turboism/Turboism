package dev.turboism.sdk.cubism.model;

import dev.turboism.sdk.CubismEditor;

/** Immutable canvas metrics for one Cubism model generation. */
@CubismEditor({"5.2.03", "5.3.02", "5.3.03"})
public interface Canvas {

    /** Returns the canvas width in pixels. */
    float widthPixels();

    /** Returns the canvas height in pixels. */
    float heightPixels();

    /** Returns the canvas origin X offset in pixels. */
    float originXPixels();

    /** Returns the canvas origin Y offset in pixels. */
    float originYPixels();

    /** Returns the canvas pixels-per-unit scale factor. */
    float pixelsPerUnit();
}
