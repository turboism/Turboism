package dev.turboism.sdk.cubism.id;

import java.util.Objects;

public record ModelId(String value) {
    public ModelId {
        value = Objects.requireNonNull(value, "value");
    }
}
