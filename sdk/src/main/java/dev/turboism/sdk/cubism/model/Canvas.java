package dev.turboism.sdk.cubism.model;

import dev.turboism.sdk.PreviewApi;

/** Immutable canvas metrics for one Cubism model generation. */
@PreviewApi
public interface Canvas {

    float widthPixels();

    float heightPixels();

    float originXPixels();

    float originYPixels();

    float pixelsPerUnit();
}
