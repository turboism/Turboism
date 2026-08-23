package dev.turboism.sdk.cubism.model;

import dev.turboism.sdk.cubism.id.ParameterId;

import java.util.Objects;

/** Atomic Editor authoring definition for one Cubism parameter. */
public record ParameterDefinition(
    ParameterId id,
    String name,
    float minimumValue,
    float defaultValue,
    float maximumValue,
    ParameterType type,
    boolean repeat
) {

    public ParameterDefinition {
        id = Objects.requireNonNull(id, "id");
        name = requireName(name);
        type = Objects.requireNonNull(type, "type");
        if (!Float.isFinite(minimumValue)
            || !Float.isFinite(defaultValue)
            || !Float.isFinite(maximumValue)) {
            throw new IllegalArgumentException("Parameter definition values must be finite.");
        }
        if (minimumValue > defaultValue || defaultValue > maximumValue) {
            throw new IllegalArgumentException(
                "Parameter definition must satisfy minimum <= default <= maximum."
            );
        }
        if (type == ParameterType.UNKNOWN) {
            throw new IllegalArgumentException(
                "Parameter definition type must be NORMAL or BLEND_SHAPE."
            );
        }
    }

    private static String requireName(final String value) {
        Objects.requireNonNull(value, "name");
        final String normalized = value.strip();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("Parameter definition name must not be blank.");
        }
        return normalized;
    }
}
