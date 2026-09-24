package dev.turboism.sdk.cubism.model;

/**
 * Interpolation curve type recorded for one keyframe's outgoing segment.
 *
 * <p>Mirrors the Cubism curve-type roster; names are compared verbatim.</p>
 */
public enum AnimationCurveType {
    LINEAR,
    BEZIER,
    SMOOTH,
    STEP,
    INVERSE_STEP
}
