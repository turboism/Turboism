package dev.turboism.sdk.config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Declares one typed config file: its identity, location, current version and keys.
 *
 * <p>The compact constructor defensively copies {@code keys} into an unmodifiable list, so later
 * mutation of the caller's list cannot alter a registered schema. A {@code null} key list is passed
 * through unchanged; validity is enforced at registration, not here.
 *
 * @param configId stable identifier used by {@link ConfigKey#configId()} to bind keys to this schema
 * @param relativePath the config file location relative to the plugin's config root
 * @param version the schema version documents are written under
 * @param keys the declared keys, unmodifiable when non-null
 */
public record ConfigSchema(
    String configId,
    String relativePath,
    int version,
    List<ConfigKey<?>> keys
) {
    public ConfigSchema {
        if (keys != null) {
            keys = Collections.unmodifiableList(new ArrayList<>(keys));
        }
    }
}
