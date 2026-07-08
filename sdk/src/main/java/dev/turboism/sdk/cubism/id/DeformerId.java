package dev.turboism.sdk.cubism.id;

import java.util.Objects;

public record DeformerId(String value) {
    public DeformerId {
        value = Objects.requireNonNull(value, "value");
    }
}
