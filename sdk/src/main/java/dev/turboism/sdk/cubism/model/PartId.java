package dev.turboism.sdk.cubism.model;

import java.util.Objects;

/** Stable ID of one Cubism Part. */
public record PartId(String value) {

    public PartId {
        value = requireText(value);
    }

    private static String requireText(final String value) {
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) {
            throw new IllegalArgumentException("value must not be blank");
        }
        return value;
    }
}
