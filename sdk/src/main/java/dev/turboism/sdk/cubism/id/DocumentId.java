package dev.turboism.sdk.cubism.id;

import java.util.Objects;

public record DocumentId(String value) {
    public DocumentId {
        value = Objects.requireNonNull(value, "value");
    }
}
