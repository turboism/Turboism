package dev.turboism.adapter.cubism.core;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Adapter-owned projection of one public Cubism Core parameter definition and value.
 *
 * <p>The numeric Core parameter-type code is preserved verbatim. The adapter does not invent
 * enum names, normalize ranges, or clamp the current value.</p>
 */
record CoreParameterDefinition(
    String id,
    int typeNumber,
    float minimumValue,
    float maximumValue,
    float defaultValue,
    float currentValue,
    List<Float> keyValues,
    Optional<Boolean> repeat
) {

    CoreParameterDefinition {
        id = requireText(id, "id");
        requireFinite(minimumValue, "minimumValue");
        requireFinite(maximumValue, "maximumValue");
        requireFinite(defaultValue, "defaultValue");
        requireFinite(currentValue, "currentValue");
        keyValues = List.copyOf(Objects.requireNonNull(keyValues, "keyValues"));
        for (Float keyValue : keyValues) {
            Objects.requireNonNull(keyValue, "keyValues element");
            requireFinite(keyValue, "keyValues element");
        }
        repeat = Objects.requireNonNull(repeat, "repeat");
    }

    private static String requireText(final String value, final String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private static void requireFinite(final float value, final String name) {
        if (!Float.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite");
        }
    }
}
