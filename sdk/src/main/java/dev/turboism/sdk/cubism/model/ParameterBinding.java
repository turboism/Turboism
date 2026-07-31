package dev.turboism.sdk.cubism.model;

import dev.turboism.sdk.PreviewApi;
import dev.turboism.sdk.cubism.id.ParameterId;

import java.util.List;
import java.util.Objects;

/** Immutable Editor authoring relationship between one model object and parameter. */
@PreviewApi
public record ParameterBinding(
    ParameterBindingTarget target,
    ParameterId parameterId,
    ParameterBindingFamily family,
    List<ParameterBindingPoint> points
) {
    public ParameterBinding {
        target = Objects.requireNonNull(target, "target");
        parameterId = Objects.requireNonNull(parameterId, "parameterId");
        family = Objects.requireNonNull(family, "family");
        points = List.copyOf(Objects.requireNonNull(points, "points"));
    }
}
