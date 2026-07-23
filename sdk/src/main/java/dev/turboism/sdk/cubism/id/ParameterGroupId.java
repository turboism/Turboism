package dev.turboism.sdk.cubism.id;

import java.util.Objects;

/** Stable Editor parameter-group identifier. */
public record ParameterGroupId(String value) {
    public ParameterGroupId {
        value = Objects.requireNonNull(value, "value");
    }
}
