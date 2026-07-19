package dev.turboism.plugin.uitheme.b1.domain;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public record ThemePackageData(
    ThemePackageMetadata metadata,
    Map<String, String> colors,
    Map<String, String> generatorMetadata,
    String readme,
    String license
) {
    public ThemePackageData {
        metadata = Objects.requireNonNull(metadata, "metadata");
        colors = immutableMap(colors);
        generatorMetadata = immutableMap(generatorMetadata);
    }

    private static Map<String, String> immutableMap(final Map<String, String> source) {
        final LinkedHashMap<String, String> copy = new LinkedHashMap<>();
        if (source != null) {
            copy.putAll(source);
        }
        return Collections.unmodifiableMap(copy);
    }
}
