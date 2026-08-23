package dev.turboism.core.event;

import java.util.Objects;

/** Identifies one admitted generation of a plugin inside the session event broker. */
public record PluginEventOwnerKey(String pluginId, long generation) {

    public PluginEventOwnerKey {
        Objects.requireNonNull(pluginId, "pluginId");
        if (pluginId.isBlank()) {
            throw new IllegalArgumentException("pluginId must not be blank");
        }
        if (generation < 0L) {
            throw new IllegalArgumentException("generation must not be negative");
        }
    }
}
