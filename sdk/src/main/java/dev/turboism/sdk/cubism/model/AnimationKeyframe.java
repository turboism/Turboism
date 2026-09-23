package dev.turboism.sdk.cubism.model;

import java.util.Optional;
import java.util.OptionalDouble;

/** One keyframe on an {@link AnimationAttribute} curve. */
public interface AnimationKeyframe {

    /** Frame position of this key in scene frames. */
    int frame();

    /**
     * Scalar value at this key. Present for {@link AnimationAttributeKind#FLOAT}
     * and {@link AnimationAttributeKind#INTEGER} attributes.
     */
    OptionalDouble value();

    /**
     * Two-dimensional value at this key. Present for
     * {@link AnimationAttributeKind#POINT} attributes.
     */
    Optional<Point2> pointValue();

    /**
     * Curve type recorded for this key's outgoing segment. Empty when the
     * backing sequence does not carry per-key curve types.
     */
    Optional<AnimationCurveType> curveType();

    /**
     * Incoming bezier handle, when the attribute sequence stores bezier
     * control points.
     */
    Optional<AnimationBezierHandle> inHandle();

    /** Outgoing bezier handle, when stored. */
    Optional<AnimationBezierHandle> outHandle();
}
