package dev.turboism.plugin.palettelabelstyle;

import dev.turboism.sdk.storage.StoragePath;
import dev.turboism.sdk.storage.StorageRoot;
import dev.turboism.sdk.ui.context.ContextMenuRegistry.Location;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Project-scoped persistence of label text/background colors.
 *
 * <p>One {@code palette-label-style/colors-<projectId>.properties} file per project
 * (blank projectId maps to {@value #DEFAULT_PROJECT_ID}) holds one
 * {@code <PALETTE>:<objectId>:<text|background>=#RRGGBB} entry per colored object.
 * The plugin keeps the authoritative entry map in memory and atomically rewrites
 * the whole file on every change; this class only owns the portable file format,
 * path, and key helpers.</p>
 */
public final class LabelStylePersistence {

    public static final String DEFAULT_PROJECT_ID = "default";
    public static final String PROPERTY_TEXT = "text";
    public static final String PROPERTY_BACKGROUND = "background";

    private LabelStylePersistence() {
    }

    /** Storage path of the color file for a project; blank ids fall back to {@value #DEFAULT_PROJECT_ID}. */
    public static StoragePath filePath(final String projectId) {
        return new StoragePath(
            StorageRoot.STATE,
            "palette-label-style/colors-" + safeProjectId(projectId) + ".properties"
        );
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
            return Optional.of(new StoredEntry(
                Location.valueOf(key.substring(0, firstColon)), objectId, property
            ));
        } catch (IllegalArgumentException malformedPalette) {
            return Optional.empty();
        }
    }

    /**
     * @param property candidate property segment of a stored entry key
     * @return whether it is one of the two styled properties, text or
     *     background; any other value marks the stored line as malformed
     */
    public static boolean isProperty(final String property) {
        return PROPERTY_TEXT.equals(property) || PROPERTY_BACKGROUND.equals(property);
    }

    /**
     * Parses properties text into entry-key to hex values, skipping malformed lines.
     * Keeps one entry per key; the file is written by {@link #serialize} so keys never
     * contain '=' or newlines.
     */
    public static Map<String, String> parse(final String content) {
        final TreeMap<String, String> entries = new TreeMap<>();
        if (content == null || content.isBlank()) {
            return entries;
        }
        for (final String line : content.split("\\R")) {
            final String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            final int separator = trimmed.indexOf('=');
            if (separator <= 0) {
                continue;
            }
            final String entryKey = trimmed.substring(0, separator);
            final String hex = trimmed.substring(separator + 1);
            if (parseKey(entryKey).isPresent() && LabelStylePresets.parseHex(hex).isPresent()) {
                entries.put(entryKey, hex);
            }
        }
        return entries;
    }

    /** Serializes entry-key to hex entries into stable sorted properties text. */
    public static String serialize(final Map<String, String> entries) {
        Objects.requireNonNull(entries, "entries");
        final TreeMap<String, String> sorted = new TreeMap<>(entries);
        if (sorted.isEmpty()) {
            return "# palette-label-style colors (empty)\n";
        }
        final StringBuilder builder = new StringBuilder("# palette-label-style colors\n");
        for (final Map.Entry<String, String> entry : sorted.entrySet()) {
            builder.append(entry.getKey()).append('=').append(entry.getValue()).append('\n');
        }
        return builder.toString();
    }

    /** One stored entry: palette location, object id, and text/background property. */
    public record StoredEntry(Location palette, String objectId, String property) {
    }
}
