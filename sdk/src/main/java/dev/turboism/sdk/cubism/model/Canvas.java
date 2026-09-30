package dev.turboism.sdk.cubism.model;

import dev.turboism.sdk.CubismEditor;

/** Immutable canvas metrics for one Cubism model generation. */
@CubismEditor({
    "5.0.00", "5.0.01", "5.0.02", "5.0.03", "5.0.04", "5.0.05", "5.0.06", "5.1.00", "5.1.01", "5.1.02", "5.1.03",
    "5.2.00", "5.2.01", "5.2.02", "5.2.03", "5.3.00", "5.3.01", "5.3.02", "5.3.03", "5.3.04"
})
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
