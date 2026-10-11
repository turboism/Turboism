package dev.turboism.sdk.cubism.model;

import dev.turboism.sdk.CubismEditor;

/** Immutable Editor model profile metrics for one Cubism model generation. */
@CubismEditor
public interface ModelProfile {

    /** Returns the Editor pixels-per-unit for the model canvas. */
    float pixelsPerUnit();

    /** Returns the canvas origin X in pixels (Editor origin-in-pixels). */
    float originXPixels();

    /** Returns the canvas origin Y in pixels (Editor origin-in-pixels). */
    float originYPixels();
}
