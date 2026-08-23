package dev.turboism.sdk.cubism.model;


/** Immutable canvas metrics for one Cubism model generation. */
public interface Canvas {

    float widthPixels();

    float heightPixels();

    float originXPixels();

    float originYPixels();

    float pixelsPerUnit();
}
