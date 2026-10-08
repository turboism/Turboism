package dev.turboism.sdk.cubism.model;

import java.util.Objects;

/**
 * Native extended interpolation settings for one existing parameter binding.
 * angleScale converts parameter units to degrees for ellipse interpolation.
 * Reading a method does not imply the current backend authorizes writing it.
 */
public record ParameterInterpolation(Method method, int insertPointCount, float angleScale) {
    /** Interpolation curve family recorded by the host for a parameter binding. */
    public enum Method { LINEAR, ELLIPSE, SNS_CURVE }

    public ParameterInterpolation {
        Objects.requireNonNull(method, "method");
        if (insertPointCount < 0 || insertPointCount > 64) {
            throw new IllegalArgumentException("insertPointCount must be in 0..64");
        }
        if (!Float.isFinite(angleScale) || angleScale <= 0) {
            throw new IllegalArgumentException("angleScale must be finite and positive");
        }
    }
}
