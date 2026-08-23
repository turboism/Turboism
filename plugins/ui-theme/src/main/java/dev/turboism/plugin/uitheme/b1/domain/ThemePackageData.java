package dev.turboism.plugin.uitheme.b1.domain;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * A decoded theme package: its metadata plus the content the palette is built from.
 *
 * <p>Both maps are defensively copied into unmodifiable, insertion-ordered maps, and a null
 * map becomes an empty one, so the record is immutable and its accessors never return null
 * maps. Key order is preserved because it is the order the source file declared. The readme
 * and license text are optional and may be null.
 *
 * @param metadata the package header; must not be null
 * @param colors the package's raw color values keyed by legacy color key, in declaration order
 * @param generatorMetadata values recorded by the palette generator that produced this
 *                          package, in declaration order
 * @param readme the package's readme text, or null when it has none
 * @param license the package's license text, or null when it has none
 */
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
