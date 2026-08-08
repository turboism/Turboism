package dev.turboism.sdk.cubism.id;

import dev.turboism.sdk.PreviewApi;

import java.util.Objects;

/** Stable identity for one parameter-binding point within a model generation. */
@PreviewApi
public record ParameterBindingPointId(String value) {
    public ParameterBindingPointId {
        value = Objects.requireNonNull(value, "value").trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException("value must not be blank");
        }
    }
}
