package dev.turboism.plugin.turbomesh.domain.regions;

/**
 * Shape family that drives the direction guides.
 *
 * <p>The family follows the principal-component aspect ratio of the solid pixels, refined by the
 * cross-section width at both ends. Consumers use it to explain why a mesh follows rings, arcs, or
 * parallel strips.</p>
 */
public enum ShapeFamily {
    /** Round or oval silhouette: nested homothetic rings. */
    CONCENTRIC_RINGS,
    /** Elongated silhouette with narrowing ends: arcs that connect both tips. */
    SPINDLE_ARCS,
    /** Elongated hard surface with blunt ends: parallel guide lines. */
    STRAIGHT_GUIDES,
    /** No guide was emitted, for example because the shape is degenerate. */
    NONE
}
