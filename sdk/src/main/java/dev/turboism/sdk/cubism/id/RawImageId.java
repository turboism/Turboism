package dev.turboism.sdk.cubism.id;

import java.util.Objects;

public record RawImageId(String value) {
    public RawImageId {
        value = Objects.requireNonNull(value, "value");
    }
}
