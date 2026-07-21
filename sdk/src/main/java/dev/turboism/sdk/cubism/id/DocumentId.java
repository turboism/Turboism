package dev.turboism.sdk.cubism.id;

import java.util.Objects;

/** Opaque identity of one Cubism Editor document. */
public record DocumentId(String value) {
    public DocumentId {
        value = Objects.requireNonNull(value, "value");
    }
}
