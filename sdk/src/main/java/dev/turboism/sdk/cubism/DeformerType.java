package dev.turboism.sdk.cubism;

/**
 * Kind of deformer node reported by the Cubism host.
 *
 * <p>{@link #OTHER} is the deliberate catch-all: the SDK admits only Cubism 5.2.03 and 5.3.02, and
 * any deformer kind outside the enumerated set is mapped to {@code OTHER} rather than rejected, so
 * an unfamiliar host object never fails a whole snapshot.</p>
 */
public enum DeformerType {
    /** Synthetic root of the deformer tree; not a deformer the user can edit. */
    ROOT,
    /** Warp deformer, which distorts geometry through a control-point grid. */
    WARP,
    /** Rotation deformer, which pivots its children about an origin. */
    ROTATION,
    /** Translation deformer, which offsets its children. */
    TRANSLATION,
    /** A deformer kind this SDK version does not model explicitly. */
    OTHER
}
