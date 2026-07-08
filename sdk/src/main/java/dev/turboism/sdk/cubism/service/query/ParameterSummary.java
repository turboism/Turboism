package dev.turboism.sdk.cubism.service.query;

import dev.turboism.sdk.cubism.id.ParameterId;
import java.util.Objects;

public record ParameterSummary(
    ParameterId id,
    String name,
    double currentValue,
    ParameterBounds bounds,
    boolean visible,
    boolean editable
) {
    public ParameterSummary {
        id = Objects.requireNonNull(id, "id");
        name = Objects.requireNonNull(name, "name");
        bounds = Objects.requireNonNull(bounds, "bounds");
    }

    public double minValue() {
        return bounds.minValue();
    }

    public double maxValue() {
        return bounds.maxValue();
    }

    public double defaultValue() {
        return bounds.defaultValue();
    }
}
