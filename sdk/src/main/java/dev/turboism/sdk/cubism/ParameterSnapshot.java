package dev.turboism.sdk.cubism;

import java.util.Objects;

public record ParameterSnapshot(
    String id,
    String name,
    double value,
    double defaultValue,
    double minValue,
    double maxValue,
    boolean visible,
    boolean editable
) implements ModelObjectSnapshot {
    public ParameterSnapshot {
        id = Objects.requireNonNull(id, "id");
        name = Objects.requireNonNull(name, "name");
    }
}
