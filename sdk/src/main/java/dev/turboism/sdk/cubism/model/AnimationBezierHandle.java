package dev.turboism.sdk.cubism.model;

/**
 * One bezier control handle attached to an {@link AnimationKeyframe}.
 *
 * @param frame the host-reported frame position of the control point
 * @param value the control point value
 * @param corner whether the handle is broken into a corner (non-continuous)
 */
public record AnimationBezierHandle(float frame, double value, boolean corner) {

    public AnimationBezierHandle {
        if (!Float.isFinite(frame)) {
            throw new IllegalArgumentException("frame must be finite");
        }
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("value must be finite");
        }
    }
}
