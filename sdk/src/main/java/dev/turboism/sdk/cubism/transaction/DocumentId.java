package dev.turboism.sdk.cubism.transaction;

/**
 * Opaque identifier for a Cubism editor document.
 * Wraps the underlying host document identity without exposing
 * the raw host object.
 */
public record DocumentId(String id) {

    public DocumentId {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("DocumentId must not be null or blank");
        }
    }

    @Override
    public String toString() {
        return "doc:" + id;
    }
}
