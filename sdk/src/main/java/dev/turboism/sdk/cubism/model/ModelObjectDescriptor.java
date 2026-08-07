package dev.turboism.sdk.cubism.model;

import dev.turboism.sdk.PreviewApi;

import java.util.Objects;
import java.util.Optional;

/** Immutable public description returned after a model-object operation. */
@PreviewApi
public record ModelObjectDescriptor(
    ModelObjectReference reference,
    String name,
    Optional<ModelObjectReference> parent
) {

    public ModelObjectDescriptor {
        reference = Objects.requireNonNull(reference, "reference");
        name = normalizeName(name);
        parent = Objects.requireNonNull(parent, "parent");
    }

    static String normalizeName(final String value) {
        final String normalized = Objects.requireNonNull(value, "name").strip();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        if (normalized.length() > 256) {
            throw new IllegalArgumentException("name must not exceed 256 characters");
        }
        return normalized;
    }
}
