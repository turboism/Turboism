package dev.turboism.sdk.cubism.core;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable adapter-owned projection of one evaluated Core parameter.
 *
 * <p>The numeric Core parameter-type code is preserved verbatim; the adapter does not
 * invent enum names or clamp the current value.</p>
 *
 * <p>This record belongs to the detached {@link OwnedModel} plane — a Turboism-owned model that
 * is not attached to an Editor document. It is not the write handle for the open Editor model;
 * use {@link dev.turboism.sdk.cubism.model.Parameter} for that.
 */
public record OwnedParameter(
        String id,
        int typeNumber,
        float minimumValue,
        float maximumValue,
        float defaultValue,
        float currentValue,
        List<Float> keyValues,
        Optional<Boolean> repeat) {

    public OwnedParameter {
        Objects.requireNonNull(id, "id");
        if (id.isBlank()) {
            throw new IllegalArgumentException("id must not be blank");
        }
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

    private static void requireFinite(final float value, final String name) {
        if (!Float.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite");
        }
    }
}
