package dev.turboism.plugin.palettelabelstyle;

import dev.turboism.sdk.PreviewApi;
import dev.turboism.sdk.config.PluginConfigException;
import dev.turboism.sdk.config.PluginConfigRegistry;
import dev.turboism.sdk.ui.context.ContextMenuRegistry.Location;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Project-scoped persistence of label text/background colors.
 *
 * <p>Values live in one {@code palette-label-style/colors-<projectId>.properties}
 * config scope per project (blank projectId maps to {@value #DEFAULT_PROJECT_ID}).
 * Each entry key is {@code <PALETTE>:<objectId>:<text|background>} with a
 * {@code #RRGGBB} value. The SDK config surface offers no key enumeration, so a
 * companion {@value #INDEX_KEY} property tracks the entry keys of this scope and
 * is updated on every write/clear; cleared entries keep an empty tombstone value.</p>
 */
@PreviewApi
public final class LabelStylePersistence {

    public static final String DEFAULT_PROJECT_ID = "default";
    public static final String PROPERTY_TEXT = "text";
    public static final String PROPERTY_BACKGROUND = "background";

    private static final String INDEX_KEY = "index";
    private static final String TOMBSTONE = "";

    /** One stored entry: palette location, object id, and text/background property. */
    public record StoredEntry(Location palette, String objectId, String property) {
    }

    private LabelStylePersistence() {
    }

    /** Config scope path for a project; blank ids fall back to {@value #DEFAULT_PROJECT_ID}. */
    public static String scopePath(final String projectId) {
        return "palette-label-style/colors-" + safeProjectId(projectId) + ".properties";
    }

    /** Normalizes a project id, mapping blank values to {@value #DEFAULT_PROJECT_ID}. */
    public static String safeProjectId(final String projectId) {
        return projectId == null || projectId.isBlank() ? DEFAULT_PROJECT_ID : projectId;
    }

    /** Entry key: {@code <PALETTE>:<objectId>:<property>}. */
    public static String key(final Location palette, final String objectId, final String property) {
        return Objects.requireNonNull(palette, "palette").name() + ":"
            + Objects.requireNonNull(objectId, "objectId") + ":"
            + Objects.requireNonNull(property, "property");
    }

    /** Splits a stored entry key back into its parts; empty when malformed. */
    public static Optional<StoredEntry> parseKey(final String key) {
        if (key == null) {
            return Optional.empty();
        }
        final int firstColon = key.indexOf(':');
        final int lastColon = key.lastIndexOf(':');
        if (firstColon <= 0 || lastColon <= firstColon) {
            return Optional.empty();
        }
        final String objectId = key.substring(firstColon + 1, lastColon);
        final String property = key.substring(lastColon + 1);
        if (objectId.isBlank() || !isProperty(property)) {
            return Optional.empty();
        }
        try {
            return Optional.of(new StoredEntry(Location.valueOf(key.substring(0, firstColon)), objectId, property));
        } catch (IllegalArgumentException malformedPalette) {
            return Optional.empty();
        }
    }

    public static boolean isProperty(final String property) {
        return PROPERTY_TEXT.equals(property) || PROPERTY_BACKGROUND.equals(property);
    }

    /** Persists one entry and refreshes the scope index. */
    public static void write(
        final PluginConfigRegistry config,
        final String projectId,
        final Location palette,
        final String objectId,
        final String property,
        final String hex
    ) throws PluginConfigException {
        final String scope = scopePath(projectId);
        config.writeString(scope, key(palette, objectId, property), hex);
        updateIndex(config, scope, key(palette, objectId, property), true);
    }

    /** Clears one entry (tombstone value plus index removal). */
    public static void clear(
        final PluginConfigRegistry config,
        final String projectId,
        final Location palette,
        final String objectId,
        final String property
    ) throws PluginConfigException {
        final String scope = scopePath(projectId);
        config.writeString(scope, key(palette, objectId, property), TOMBSTONE);
        updateIndex(config, scope, key(palette, objectId, property), false);
    }

    /** Reads every stored {@code #RRGGBB} entry of a project: entry key to hex. */
    public static Map<String, String> readAll(final PluginConfigRegistry config, final String projectId) {
        final String scope = scopePath(projectId);
        final TreeMap<String, String> entries = new TreeMap<>();
        for (final String entryKey : readIndex(config, scope)) {
            final Optional<String> hex = config.readString(scope, entryKey);
            if (hex.flatMap(LabelStylePresets::parseHex).isPresent()) {
                entries.put(entryKey, hex.orElseThrow());
            }
        }
        return entries;
    }

    private static void updateIndex(
        final PluginConfigRegistry config,
        final String scope,
        final String entryKey,
        final boolean present
    ) throws PluginConfigException {
        final TreeSet<String> index = readIndex(config, scope);
        if (present) {
            index.add(entryKey);
        } else {
            index.remove(entryKey);
        }
        config.writeString(scope, INDEX_KEY, String.join(",", index));
    }

    private static TreeSet<String> readIndex(final PluginConfigRegistry config, final String scope) {
        final TreeSet<String> index = new TreeSet<>();
        config.readString(scope, INDEX_KEY).ifPresent(value -> {
            for (final String key : value.split(",")) {
                if (!key.isBlank()) {
                    index.add(key);
                }
            }
        });
        return index;
    }
}
