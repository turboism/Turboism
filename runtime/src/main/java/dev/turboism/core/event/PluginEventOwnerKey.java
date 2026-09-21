package dev.turboism.core.event;

import java.util.Objects;

/**
 * Identifies one admitted generation of a plugin inside the session event broker.
 *
 * <p>Admitted plugin generations are always {@code >= 0}. The reserved value
 * {@code -1} marks a runtime-internal pseudo-owner that is never admitted —
 * used only for diagnostic attribution of broker-internal failures.</p>
 */
public record PluginEventOwnerKey(String pluginId, long generation) {

    /** Generation reserved for runtime-internal pseudo-owners; never admitted. */
    public static final long INTERNAL_GENERATION = -1L;

    public PluginEventOwnerKey {
        Objects.requireNonNull(pluginId, "pluginId");
        if (pluginId.isBlank()) {
            throw new IllegalArgumentException("pluginId must not be blank");
        }
        if (generation < INTERNAL_GENERATION) {
            throw new IllegalArgumentException("generation must not be negative");
        }
    }
}
