package dev.turboism.sdk.cubism.model;

import dev.turboism.sdk.PreviewApi;

import java.util.Objects;

/** Typed identity of one model object in the active document. */
@PreviewApi
public record ModelObjectReference(ModelObjectKind kind, String id) {

    public ModelObjectReference {
        kind = Objects.requireNonNull(kind, "kind");
        id = Objects.requireNonNull(id, "id").strip();
        if (id.isEmpty()) {
            throw new IllegalArgumentException("id must not be blank");
        }
        if (id.length() > 256) {
            throw new IllegalArgumentException("id must not exceed 256 characters");
        }
    }
}
