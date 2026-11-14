package dev.turboism.adapter.cubism.core;

import dev.turboism.internal.Requirements;
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
        Optional<Boolean> repeat) {

    CoreParameterDefinition {
        id = Requirements.text(id, "id");
        Requirements.finite(minimumValue, "minimumValue");
        Requirements.finite(maximumValue, "maximumValue");
        Requirements.finite(defaultValue, "defaultValue");
        Requirements.finite(currentValue, "currentValue");
        keyValues = List.copyOf(Objects.requireNonNull(keyValues, "keyValues"));
        for (Float keyValue : keyValues) {
            Objects.requireNonNull(keyValue, "keyValues element");
            Requirements.finite(keyValue, "keyValues element");
        }
        repeat = Objects.requireNonNull(repeat, "repeat");
    }
}
