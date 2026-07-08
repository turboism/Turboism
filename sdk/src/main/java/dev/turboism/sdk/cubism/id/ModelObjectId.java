package dev.turboism.sdk.cubism.id;

import java.util.Objects;

public record ModelObjectId(String value) {
    public ModelObjectId {
        value = Objects.requireNonNull(value, "value");
    }
}
