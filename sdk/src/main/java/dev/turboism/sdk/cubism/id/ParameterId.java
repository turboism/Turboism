package dev.turboism.sdk.cubism.id;

import java.util.Objects;

public record ParameterId(String value) {
    public ParameterId {
        value = Objects.requireNonNull(value, "value");
    }
}
