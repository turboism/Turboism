package dev.turboism.sdk.cubism.model;

import dev.turboism.sdk.PreviewApi;
import dev.turboism.sdk.cubism.id.ParameterBindingPointId;

import java.util.Objects;

/** One immutable coordinate in an Editor parameter binding. */
@PreviewApi
public record ParameterBindingPoint(ParameterBindingPointId id, float value) {
    public ParameterBindingPoint {
        id = Objects.requireNonNull(id, "id");
        if (!Float.isFinite(value)) {
            throw new IllegalArgumentException("value must be finite");
        }
    }
}
