package dev.turboism.sdk.cubism.mesh;

import dev.turboism.sdk.Incubating;

/**
 * Runtime-owned selection brush bound to one mesh-tool activation.
 *
 * <p>Radius is measured in active overlay-component pixels. An in-flight stroke retains the
 * radius observed at primary press. Closing is idempotent and cancels without committing.</p>
 *
 * <p><strong>Preview API:</strong> this contract may evolve before stabilization.</p>
 */
@Incubating
public interface MeshBrush extends AutoCloseable {
    /** Smallest supported radius, in active component pixels. */
    int MIN_RADIUS_PIXELS = 8;
    /** Largest supported radius, in active component pixels. */
    int MAX_RADIUS_PIXELS = 128;

    /** Returns the radius used by the next primary-button stroke. */
    int radiusPixels();

    /**
     * Sets the radius used by the next primary-button stroke.
     *
     * @param radiusPixels radius in active component pixels, from 8 through 128
     * @throws IllegalArgumentException when the radius is outside the supported range
     */
    void setRadiusPixels(int radiusPixels);

    /** Cancels any in-flight stroke and releases the activation-owned overlay and listeners. */
    @Override
    void close();
}
