package dev.turboism.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Reads the persisted mesh-triangulation preference during premain.
 *
 * <p>The transformer must decide before the host class is defined, which is earlier than the plugin
 * runtime exists, so this deliberately reads only the one key it needs under the same bounds as
 * {@link RuntimeStartupConfig}: a size-capped, non-following read that never throws.</p>
 */
public final class MeshTriangulationPreference {

    /** Config key holding the preference. */
    public static final String KEY = "meshTriangulationHashFix";
    /** Default when nothing is persisted: the fix is on. */
    public static final boolean DEFAULT_ENABLED = true;

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long MAX_CONFIG_BYTES = 64L * 1024L;

    private MeshTriangulationPreference() {
    }

    /**
     * @param turboismHome Turboism home containing {@code config.json}
     * @return the persisted preference, or {@link #DEFAULT_ENABLED} when absent or unreadable
     */
    public static boolean read(final Path turboismHome) {
        if (turboismHome == null) return DEFAULT_ENABLED;
        final Path config = turboismHome.resolve("config.json").normalize();
        try {
            if (!config.startsWith(turboismHome.toAbsolutePath().normalize())
                || !Files.isRegularFile(config, LinkOption.NOFOLLOW_LINKS)
                || Files.size(config) > MAX_CONFIG_BYTES) {
                return DEFAULT_ENABLED;
            }
            final JsonNode root = JSON.readTree(Files.readAllBytes(config));
            final JsonNode value = root == null ? null : root.get(KEY);
            if (value == null || !value.isBoolean()) return DEFAULT_ENABLED;
            return value.booleanValue();
        } catch (Exception unreadable) {
            // An unreadable preference must not invert the default; the safety gates are separate.
            return DEFAULT_ENABLED;
        }
    }

    /** @return the value to persist for a proposed toggle state */
    public static boolean normalize(final Object proposed) {
        Objects.requireNonNull(proposed, "proposed");
        return proposed instanceof Boolean value ? value : DEFAULT_ENABLED;
    }
}
