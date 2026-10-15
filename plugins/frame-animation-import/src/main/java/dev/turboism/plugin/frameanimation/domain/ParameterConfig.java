package dev.turboism.plugin.frameanimation.domain;

import dev.turboism.sdk.cubism.id.ParameterId;
import dev.turboism.sdk.cubism.model.ParameterDefinition;
import dev.turboism.sdk.cubism.model.ParameterType;
import java.util.Objects;

/**
 * Configuration for creating or reusing a Cubism parameter for frame animation binding.
 */
public record ParameterConfig(
        ParameterId id, String name, float minimumValue, float defaultValue, float maximumValue, boolean repeat) {

    public ParameterConfig {
        Objects.requireNonNull(id, "id");
        name = Objects.requireNonNull(name, "name").strip();
        if (name.isEmpty()) {
            throw new IllegalArgumentException("parameter name must not be blank");
        }
        if (!Float.isFinite(minimumValue) || !Float.isFinite(defaultValue) || !Float.isFinite(maximumValue)) {
            throw new IllegalArgumentException("parameter bounds must be finite");
        }
        if (minimumValue > defaultValue || defaultValue > maximumValue) {
            throw new IllegalArgumentException("must satisfy minimum <= default <= maximum");
        }
    }

    /**
     * Creates a default parameter configuration for N frames (0 to N-1).
     *
     * @param id parameter ID
     * @param name parameter display name
     * @param frameCount total number of frames (>= 2)
     * @param repeat whether the parameter loops continuously
     */
    public static ParameterConfig ofFrames(
            final String id, final String name, final int frameCount, final boolean repeat) {
        if (frameCount < 1) {
            throw new IllegalArgumentException("frameCount must be positive: " + frameCount);
        }
        final float max = Math.max(1.0f, (float) (frameCount - 1));
        return new ParameterConfig(new ParameterId(id), name, 0.0f, 0.0f, max, repeat);
    }

    /** Converts this configuration into a typed Cubism parameter definition. */
    public ParameterDefinition toDefinition() {
        return new ParameterDefinition(
                id, name, minimumValue, defaultValue, maximumValue, ParameterType.NORMAL, repeat);
    }
}
