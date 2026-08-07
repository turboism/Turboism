package dev.turboism.sdk.cubism.id;

import java.util.Objects;

public record ModelImageId(String value) {
    public ModelImageId {
        value = Objects.requireNonNull(value, "value");
    }
}
