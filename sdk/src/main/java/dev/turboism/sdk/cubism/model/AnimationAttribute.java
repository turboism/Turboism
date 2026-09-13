package dev.turboism.sdk.cubism.model;

import dev.turboism.sdk.cubism.id.ParameterId;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * One animated attribute on an {@link AnimationTrack}: an effect slot that
 * carries a keyed value sequence.
 */
public interface AnimationAttribute {

    /** Host attribute identifier, opaque and stable within the scene. */
    String id();

    /** Display name of the attribute. */
    String name();

    /** Host-assigned guid string for this attribute. */
    String guid();

    /** Identifier of the effect that owns this attribute. */
    String effectId();

    /**
     * The model parameter this attribute animates, when it belongs to the
     * track's Live2D parameter effect. Joined by the host's
     * {@code live2dParam_}-prefixed attribute id convention.
     */
    Optional<ParameterId> parameterId();

    AnimationAttributeKind kind();

    boolean active();

    boolean editable();

    /** Keyframes in frame order; empty when the attribute carries no keys. */
    List<AnimationKeyframe> keyframes();

    /**
     * Inserts or overwrites a scalar keyframe on a {@link AnimationAttributeKind#FLOAT}
     * or {@link AnimationAttributeKind#INTEGER} attribute. The existing curve type is
     * kept when the key already exists on a float attribute.
     *
     * @throws IllegalArgumentException when the value is not finite or the
     *         attribute is not scalar
     * @throws UnsupportedOperationException when timeline writes lack exact
     *         verified host evidence
     */
    default void setKeyframe(final int frame, final double value) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("keyframe value must be finite");
        }
        throw unavailable("Animation attribute keyframe writing");
    }

    /**
     * Inserts or overwrites a scalar keyframe with an explicit curve type on a
     * {@link AnimationAttributeKind#FLOAT} attribute.
     */
    default void setKeyframe(
        final int frame,
        final double value,
        final AnimationCurveType curveType
    ) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("keyframe value must be finite");
        }
        Objects.requireNonNull(curveType, "curveType");
        throw unavailable("Animation attribute keyframe writing");
    }

    /**
     * Inserts or overwrites a keyframe on a
     * {@link AnimationAttributeKind#POINT} attribute.
     */
    default void setKeyframe(final int frame, final float x, final float y) {
        if (!Float.isFinite(x) || !Float.isFinite(y)) {
            throw new IllegalArgumentException("keyframe coordinates must be finite");
        }
        throw unavailable("Animation attribute keyframe writing");
    }

    /** Removes the keyframe at {@code frame}; absent keys are ignored. */
    default void removeKeyframe(final int frame) {
        throw unavailable("Animation attribute keyframe removal");
    }

    /**
     * Shifts every keyframe by {@code frameDelta} frames in one undo step.
     * Curve types and stored bezier handles move with their keys. Returns the
     * number of keys moved.
     */
    default int offsetKeyframes(final int frameDelta) {
        throw unavailable("Animation attribute keyframe offset");
    }

    /**
     * Rescales every keyframe position around {@code originFrame} in one undo
     * step: {@code frame' = origin + round((frame - origin) * factor)}. Keys
     * that collide on a target frame collapse in insertion order. Returns the
     * number of source keys processed.
     */
    default int scaleKeyframeTimes(final double factor, final int originFrame) {
        if (!Double.isFinite(factor) || factor <= 0.0) {
            throw new IllegalArgumentException("scale factor must be positive and finite");
        }
        throw unavailable("Animation attribute keyframe scaling");
    }

    /**
     * Rounds every keyframe position onto the {@code stepFrames} grid in one
     * undo step. Colliding keys collapse in insertion order. Returns the number
     * of source keys processed.
     */
    default int quantizeKeyframes(final int stepFrames) {
        if (stepFrames <= 0) {
            throw new IllegalArgumentException("step must be positive");
        }
        throw unavailable("Animation attribute keyframe quantization");
    }

    /**
     * Copies every keyframe (value, curve type, stored bezier handles) from
     * {@code source} onto this attribute in one undo step. Both attributes must
     * share the same {@link AnimationAttributeKind}. When {@code replace} is
     * true the target's existing keys are removed first. Returns the number of
     * keys copied.
     */
    default int copyKeyframesFrom(final AnimationAttribute source, final boolean replace) {
        Objects.requireNonNull(source, "source");
        throw unavailable("Animation attribute keyframe copying");
    }

    /**
     * Applies {@code curveType} to every keyframe on this
     * {@link AnimationAttributeKind#FLOAT} attribute in one undo step. Returns
     * the number of keys retargeted.
     */
    default int applyCurveType(final AnimationCurveType curveType) {
        Objects.requireNonNull(curveType, "curveType");
        throw unavailable("Animation attribute curve type assignment");
    }

    /**
     * Applies {@code curveType} to every keyframe whose frame lies inside
     * {@code [fromFrame, toFrame]} in one undo step. Returns the number of keys
     * retargeted.
     */
    default int applyCurveType(
        final AnimationCurveType curveType,
        final int fromFrame,
        final int toFrame
    ) {
        Objects.requireNonNull(curveType, "curveType");
        if (fromFrame > toFrame) {
            throw new IllegalArgumentException("fromFrame must be <= toFrame");
        }
        throw unavailable("Animation attribute curve type assignment");
    }

    /**
     * Writes a keyframe at {@code frame} holding the parameter's current
     * evaluated value — recording-style input from preview adjustments. Only
     * {@link AnimationAttributeKind#FLOAT} attributes joined to a model
     * parameter can record.
     *
     * @throws IllegalStateException when the attribute has no parameter
     *         binding or the scene has no live instance
     * @throws UnsupportedOperationException when evaluated-value recording
     *         lacks exact verified host evidence
     */
    default void recordKeyframe(final int frame, final AnimationCurveType curveType) {
        Objects.requireNonNull(curveType, "curveType");
        throw unavailable("Animation attribute evaluated-value recording");
    }

    /**
     * Bakes the parameter's evaluated values into keyframes over
     * {@code [fromFrame, toFrame]} stepping by {@code stepFrames}, in one undo
     * step. Each step positions the scene instance at the frame, evaluates the
     * track tree (physics and paramCtrl included), and writes the resulting
     * parameter value. Returns the number of keys written.
     *
     * @throws IllegalArgumentException when the range is inverted or the step
     *         is not positive
     * @throws IllegalStateException when the attribute has no parameter
     *         binding or the scene has no live instance
     * @throws UnsupportedOperationException when evaluated baking lacks exact
     *         verified host evidence
     */
    default int bakeEvaluated(
        final int fromFrame,
        final int toFrame,
        final int stepFrames,
        final AnimationCurveType curveType
    ) {
        Objects.requireNonNull(curveType, "curveType");
        if (fromFrame > toFrame) {
            throw new IllegalArgumentException("fromFrame must be <= toFrame");
        }
        if (stepFrames <= 0) {
            throw new IllegalArgumentException("step must be positive");
        }
        throw unavailable("Animation attribute evaluated baking");
    }

    private static UnsupportedOperationException unavailable(final String feature) {
        return new UnsupportedOperationException(
            feature + " is unavailable without exact verified host evidence."
        );
    }
}
