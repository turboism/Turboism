package dev.turboism.plugin.frameanimation.domain;

/**
 * Keyform binding strategy governing how many key points are bound to each ArtMesh.
 */
public enum KeyformBindingMode {
    /**
     * Standard full-range mode (Recommended): each frame mesh is bound to every integer
     * frame point in the parameter range.
     */
    FULL_RANGE,

    /**
     * Lightweight neighborhood mode: each frame mesh binds to its immediate neighbors
     * and boundary keys.
     */
    LOCAL_NEIGHBORHOOD
}
