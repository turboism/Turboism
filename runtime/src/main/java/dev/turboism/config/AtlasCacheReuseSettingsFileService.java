package dev.turboism.config;

import com.fasterxml.jackson.databind.JsonNode;
import dev.turboism.plugin.core.AtlasCacheReuseSettingsService;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Persists the atlas cache-reuse preference in the canonical runtime configuration.
 *
 * <p>Mirrors the atlas tile-bbox service: the same repository, the same write path, and the
 * same rule that a persistence failure reaches the caller instead of being swallowed.</p>
 */
public final class AtlasCacheReuseSettingsFileService
        implements AtlasCacheReuseSettingsService, AutoCloseable {

    private final RuntimeConfigRepository config;

    public AtlasCacheReuseSettingsFileService(final Path turboismHome) {
        this(new RuntimeConfigRepository(turboismHome, ignored -> { }));
    }

    AtlasCacheReuseSettingsFileService(final RuntimeConfigRepository config) {
        this.config = Objects.requireNonNull(config, "config");
    }

    @Override
    public boolean read() {
        try {
            final JsonNode value = config.read().get(AtlasCacheReusePreference.KEY);
            if (value == null || !value.isBoolean()) return DEFAULT_ENABLED;
            return value.booleanValue();
        } catch (RuntimeException unreadable) {
            return DEFAULT_ENABLED;
        }
    }

    @Override
    public boolean save(final boolean value) {
        config.update(root -> {
            root.put(AtlasCacheReusePreference.KEY, value);
            return root;
        });
        return value;
    }

    @Override
    public void close() {
        // The repository holds no OS resources; the config file is the durable state.
    }
}
