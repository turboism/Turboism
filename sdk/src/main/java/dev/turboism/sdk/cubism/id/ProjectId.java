package dev.turboism.sdk.cubism.id;

import java.util.Objects;

public record ProjectId(String value) {
    public ProjectId {
        value = Objects.requireNonNull(value, "value");
    }
}
