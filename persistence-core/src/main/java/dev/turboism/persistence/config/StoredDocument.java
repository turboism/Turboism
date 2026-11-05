package dev.turboism.persistence.config;

import java.util.Map;

/**
 * One persisted typed-config document: the schema version it was written under, the store
 * revision it holds and the encoded value per key name.
 *
 * <p>The encoded values are copied into an immutable map, so a document that passed the
 * metadata check cannot be mutated afterwards. A document outside the accepted metadata range
 * is rejected at construction instead of being written to disk: the schema version must be
 * positive and the revision must not be negative.
 *
 * @param schemaVersion the schema version the stored bytes were written under, at least 1
 * @param revision the optimistic-concurrency revision of the document, not negative
 * @param encodedValues encoded value text per key name, immutable
 */
public record StoredDocument(int schemaVersion, long revision, Map<String, String> encodedValues) {
    public StoredDocument {
        if (schemaVersion < 1 || revision < 0) {
            throw new IllegalArgumentException("typed config document metadata is invalid");
        }
        encodedValues = Map.copyOf(encodedValues);
    }
}
