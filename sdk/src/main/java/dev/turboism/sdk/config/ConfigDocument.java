package dev.turboism.sdk.config;

import java.util.LinkedHashMap;
import java.util.Map;

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
