package dev.turboism.sdk.cubism.modeling;

import dev.turboism.sdk.Incubating;

/** Runtime-owned point selection brush for one ordinary modeling-tool activation. */
@Incubating
public interface ModelingBrush extends AutoCloseable {
    /** Minimum radius in active component pixels. */
    int MIN_RADIUS_PIXELS = 8;
    /** Maximum radius in active component pixels. */
    int MAX_RADIUS_PIXELS = 128;

    /** Returns the radius used at the next primary press. */
    int radiusPixels();

    /** Sets the next stroke radius (8–128); an in-flight stroke retains its press-time radius. */
    void setRadiusPixels(int radiusPixels);

    /** Idempotently cancels the stroke and removes this activation's overlay and input listeners. */
    @Override
    void close();
}
