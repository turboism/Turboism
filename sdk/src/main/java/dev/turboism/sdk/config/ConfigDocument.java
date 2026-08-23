package dev.turboism.sdk.config;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A persisted config file as read from disk: a schema version plus the raw encoded values.
 *
 * <p>The compact constructor defensively copies {@code encodedValues} into an unmodifiable
 * {@link java.util.LinkedHashMap} so declaration order is preserved and the record cannot be
 * mutated through the caller's map. A {@code null} map is passed through unchanged rather than
 * rejected, so migrations may observe {@code null} for a document that carries no values.
 *
 * @param schemaVersion the version the stored document was written under, used to select migrations
 * @param encodedValues encoded value per key name, unmodifiable and insertion-ordered when non-null
 */
public record ConfigDocument(
    int schemaVersion,
    Map<String, String> encodedValues
) {
    public ConfigDocument {
        if (encodedValues != null) {
            encodedValues = java.util.Collections.unmodifiableMap(
                new LinkedHashMap<>(encodedValues)
            );
        }
    }
}
